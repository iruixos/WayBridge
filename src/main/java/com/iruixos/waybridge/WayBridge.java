package com.iruixos.waybridge;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import java.time.Duration;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class WayBridge extends JavaPlugin implements Listener {
  // Chunk refreshes are deferred and processed in a bounded main-thread budget.
  // The pending keys are deduplicated so repeated chunk load notifications cannot flood the queue.
  private record ChunkKey(UUID world, int x, int z) {}
  private final Deque<ChunkKey> pendingRefreshChunks = new ArrayDeque<>();
  private final Set<ChunkKey> queuedRefreshChunks = new HashSet<>();
  private final Deque<Store.Entry> pendingSignRefreshes = new ArrayDeque<>();
  // A changed target may be in an unloaded chunk. Retain its refresh request
  // independently of the bounded cosmetic chunk queue, until its chunk loads.
  private final Map<String,Store.Entry> dirtyTargets = new HashMap<>();
  private BukkitTask refreshWorker;
  private BukkitTask diagnosticWorker;
  private Logger diagnosticLogger;
  private FileHandler diagnosticFileHandler;
  private long chunkLookupNanos, signRefreshNanos, physicsLookupNanos;
  private long chunkLookups, signRefreshes, physicsLookups, skippedSignUpdates;
  private long lastQueueWarning;
  private Store store;
  private Migration migration;
  private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();
  // A player has at most one countdown/chunk-load/teleport operation in flight.
  // Generation tokens invalidate callbacks after disconnect, death or replacement.
  private final Map<UUID, UUID> activeTeleports = new HashMap<>();
  private final Map<UUID, Location> teleportOrigins = new HashMap<>();
  private final Set<UUID> waybridgeTeleportEvents = new java.util.HashSet<>();
  private final Set<String> signUpdateWarnings = new java.util.HashSet<>();
  private static final class PendingTeleport {
    final Location origin;
    final UUID targetOwner;
    final String warpName;
    BukkitTask task;
    int remaining;
    PendingTeleport(Location origin, UUID targetOwner, String warpName, int remaining) {
      this.origin=origin; this.targetOwner=targetOwner; this.warpName=warpName; this.remaining=remaining;
    }
  }
  private record PrivacySelection(boolean makePrivate, long expiresAt) {}
  private final Map<UUID,PrivacySelection> privacySelections=new HashMap<>();
  private final Map<UUID,Long> relinkSelections=new HashMap<>();
  private final Map<UUID,Long> cooldown=new HashMap<>();
  private record PendingReservation(String name,UUID owner,int links,UUID token,long expiresAt) {}
  private final Map<String,PendingReservation> pendingReservations=new HashMap<>();
  private static String reservationActor(org.bukkit.command.CommandSender sender) {
    return sender instanceof Player p?p.getUniqueId().toString():"console:"+sender.getName();
  }
  private void reservationMessage(org.bukkit.command.CommandSender sender,String message) {
    sender.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA).append(Component.text(message,NamedTextColor.WHITE)));
  }
  private void reservationCommand(org.bukkit.command.CommandSender sender,String[] args) {
    if(!sender.hasPermission("waybridge.admin")){sender.sendMessage("§cAdministrator permission required.");return;}
    if(args.length<2){reservationMessage(sender,"Usage: /waybridge reservation info <name> | clear <name> | confirm <token>");return;}
    String action=args[1].toLowerCase(Locale.ROOT);
    try {
      if(action.equals("confirm")){
        String actor=reservationActor(sender);
        PendingReservation pending=pendingReservations.remove(actor);
        if(pending==null||args.length!=3||!pending.token().toString().equals(args[2])||System.currentTimeMillis()>pending.expiresAt()){
          reservationMessage(sender,"Confirmation missing, expired, or invalid. Run /waybridge reservation clear <name> again.");return;
        }
        if(store.target(pending.name())!=null){reservationMessage(sender,"An active WarpTarget now uses this name; clearing refused.");return;}
        if(!store.clearReservation(pending.name(),pending.owner(),pending.links())){
          reservationMessage(sender,"Reservation changed since preview; no changes made. Inspect it again.");return;
        }
        getLogger().warning("Admin "+sender.getName()+" cleared reservation for '"+pending.name()+"' previously owned by "+pending.owner()+" ("+pending.links()+" orphaned signs retained).");
        reservationMessage(sender,"Cleared name reservation for '"+pending.name()+"'. Existing orphaned Warp signs were not modified or relinked.");
        return;
      }
      if(!(action.equals("info")||action.equals("clear"))||args.length<3){
        reservationMessage(sender,"Usage: /waybridge reservation info <name> | clear <name> | confirm <token>");return;
      }
      String name=String.join(" ",java.util.Arrays.copyOfRange(args,2,args.length)).trim();
      if(!NAME.matcher(name).matches()){reservationMessage(sender,"Invalid name (1–16 letters, digits, spaces, _ or -).");return;}
      Store.Reservation claim=store.reservation(name);
      Store.Entry active=store.target(name);
      if(claim==null){reservationMessage(sender,"No reservation found for '"+name+"'."+(active==null?"":" An active WarpTarget exists."));return;}
      String ownerName=Objects.requireNonNullElse(Bukkit.getOfflinePlayer(claim.owner()).getName(),claim.owner().toString());
      reservationMessage(sender,"Reservation '"+claim.name()+"': owner "+ownerName+" ("+claim.owner()+"), "+claim.linkedSigns()+" associated Warp sign(s), active target: "+(active==null?"no":"yes")+".");
      if(action.equals("info"))return;
      if(active!=null){reservationMessage(sender,"Cannot clear an active WarpTarget's reservation. Remove the target normally first.");return;}
      UUID token=UUID.randomUUID();
      pendingReservations.put(reservationActor(sender),new PendingReservation(claim.name(),claim.owner(),claim.linkedSigns(),token,System.currentTimeMillis()+30000));
      sender.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
          .append(Component.text("WARNING: Clearing permits another player to claim this name; "+claim.linkedSigns()+" orphaned sign(s) remain unchanged. ",NamedTextColor.GOLD))
          .append(Component.text("[Confirm Clear]",NamedTextColor.RED)
              .clickEvent(ClickEvent.runCommand("/waybridge reservation confirm "+token))
              .hoverEvent(HoverEvent.showText(Component.text("Confirm within 30 seconds")))));
      reservationMessage(sender,"Confirmation expires in 30 seconds. Nothing has been changed yet.");
    }catch(SQLException ex){error(ex);reservationMessage(sender,"Unable to inspect or update the reservation; check server logs.");}
  }

  private static final Pattern NAME=Pattern.compile("[A-Za-z0-9_ -]{1,16}");
  private static final Set<Material> EDIT_ITEMS=EnumSet.of(Material.GLOW_INK_SAC,Material.INK_SAC,Material.HONEYCOMB,Material.BLACK_DYE,Material.BLUE_DYE,Material.BROWN_DYE,Material.CYAN_DYE,Material.GRAY_DYE,Material.GREEN_DYE,Material.LIGHT_BLUE_DYE,Material.LIGHT_GRAY_DYE,Material.LIME_DYE,Material.MAGENTA_DYE,Material.ORANGE_DYE,Material.PINK_DYE,Material.PURPLE_DYE,Material.RED_DYE,Material.WHITE_DYE,Material.YELLOW_DYE);
  @Override public void onEnable() {
    saveDefaultConfig();
    try {store=new Store(getDataFolder());}catch(Exception e){getLogger().severe("SQLite unavailable: "+e);getServer().getPluginManager().disablePlugin(this);return;}
    initializeDiagnosticLogging();
    getServer().getPluginManager().registerEvents(this,this);
    refreshWorker=Bukkit.getScheduler().runTaskTimer(this,this::processRefreshQueue,1L,1L);
    diagnosticWorker=Bukkit.getScheduler().runTaskTimer(this,this::logPerformance,1200L,1200L);
    migration=new Migration(this,store);
    Objects.requireNonNull(getCommand("waybridge")).setExecutor((sender,cmd,label,args)->{
      if(args.length==0||args[0].equalsIgnoreCase("help")) {
        int page=1;
        if(args.length>1)try{page=Integer.parseInt(args[1]);}catch(NumberFormatException ignored){}
        sendHelp(sender,page);return true;
      }
      if(args[0].equalsIgnoreCase("relink")) {
        if(!(sender instanceof Player p)){sender.sendMessage("§cA player must select the sign.");return true;}
        privacySelections.remove(p.getUniqueId());
        relinkSelections.put(p.getUniqueId(),System.currentTimeMillis()+30000);
        p.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA).append(Component.text("Left-click an orphaned Warp sign within 30 seconds to relink it to the current WarpTarget.",NamedTextColor.WHITE)));
        return true;
      }
      if(args[0].equalsIgnoreCase("private")||args[0].equalsIgnoreCase("public")) {
        if(!(sender instanceof Player p)){sender.sendMessage("§cA player must select the sign.");return true;}
        relinkSelections.remove(p.getUniqueId());
        privacySelections.put(p.getUniqueId(),new PrivacySelection(args[0].equalsIgnoreCase("private"),System.currentTimeMillis()+30000));
        p.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA).append(Component.text("Left-click a WarpTarget within 30 seconds to make it "+(args[0].equalsIgnoreCase("private")?"private":"public")+".",NamedTextColor.WHITE)));
        return true;
      }
      if(args[0].equalsIgnoreCase("invite")||args[0].equalsIgnoreCase("uninvite")||args[0].equalsIgnoreCase("invites")) {
        if(!(sender instanceof Player p)){sender.sendMessage("§cPlayer command only.");return true;}
        boolean listing=args[0].equalsIgnoreCase("invites");
        if(args.length<(listing?2:3)){p.sendMessage("§cUsage: /waybridge "+args[0]+" <target>"+(listing?"":" <player>"));return true;}
        try {
          Store.Entry target=store.target(args[1]);
          if(target==null){p.sendMessage("§cWarpTarget not found.");return true;}
          if(!target.owner().equals(p.getUniqueId())&&!admin(p)){p.sendMessage("§cOnly the target owner may manage invitations.");return true;}
          if(listing){
            List<UUID> invited=store.invites(target);
            p.sendMessage("§b[WayBridge]: §fInvitations for §5\""+target.name()+"\"§f: "+invited.size());
            for(UUID id:invited)p.sendMessage("§7• §f"+Objects.requireNonNullElse(Bukkit.getOfflinePlayer(id).getName(),id.toString()));
          }else{
            org.bukkit.OfflinePlayer player=Bukkit.getOfflinePlayerIfCached(args[2]);
            if(player==null){p.sendMessage("§cPlayer not found in the server's profile cache. They must join first.");return true;}
            if(player.getUniqueId().equals(target.owner())){p.sendMessage("§cThe target owner already has access.");return true;}
            store.invite(target,player.getUniqueId(),args[0].equalsIgnoreCase("invite"));
            p.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA).append(Component.text((args[0].equalsIgnoreCase("invite")?"Invited ":"Removed ")+args[2]+(args[0].equalsIgnoreCase("invite")?" to ":" from "),NamedTextColor.WHITE)).append(Component.text("\""+target.name()+"\"",NamedTextColor.DARK_PURPLE)));
          }
        }catch(SQLException ex){error(ex);p.sendMessage("§cCould not update invitations.");}
        return true;
      }
      if(args[0].equalsIgnoreCase("reservation")) {
        reservationCommand(sender,args);return true;
      }
      if(args[0].equalsIgnoreCase("migrate")) {
        if(!sender.hasPermission("waybridge.admin")){sender.sendMessage("§cAdministrator permission required.");return true;}
        migration.command(sender,java.util.Arrays.copyOfRange(args,1,args.length));return true;
      }
      if(args[0].equalsIgnoreCase("reload")) {
        if(!sender.hasPermission("waybridge.admin")){sender.sendMessage("§cYou do not have permission to reload WayBridge.");return true;}
        reloadConfig();sender.sendMessage("§aWayBridge configuration reloaded.");return true;
      }
      if(args[0].equalsIgnoreCase("list")) {
        try {
          List<Store.Entry> targets=store.all().stream().filter(e->e.kind().equals("TARGET"))
              .sorted(Comparator.comparing(Store.Entry::name,String.CASE_INSENSITIVE_ORDER)).toList();
          sender.sendMessage(Component.text("[WayBridge] ",NamedTextColor.AQUA)
              .append(Component.text("Registered WarpTargets ("+targets.size()+"):",NamedTextColor.WHITE)));
          if(targets.isEmpty())sender.sendMessage("§7No WarpTargets are registered.");
          for(Store.Entry e:targets)sender.sendMessage("§7• §5"+e.name()+" §7("+e.x()+", "+e.y()+", "+e.z()+")");
        }catch(SQLException ex){error(ex);sender.sendMessage("§cUnable to list warp targets.");}
        return true;
      }
      if(args[0].equalsIgnoreCase("inspect")) {
        if(!(sender instanceof Player p)){sender.sendMessage("§cThis command requires a player.");return true;}
        Block b=p.getTargetBlockExact(6);
        if(b==null){p.sendMessage("§cLook at a registered sign within 6 blocks.");return true;}
        try{
          Store.Entry e=store.at(b.getLocation());
          if(e==null){p.sendMessage("§cNot a registered WayBridge sign.");return true;}
          p.sendMessage(Component.text("[WayBridge] ",NamedTextColor.AQUA)
              .append(Component.text((e.kind().equals("TARGET")?"WarpTarget":"Warp")+" ",NamedTextColor.WHITE))
              .append(Component.text("\""+e.name()+"\"",NamedTextColor.DARK_PURPLE)));
          p.sendMessage("§7Owner UUID: §f"+e.owner());
          p.sendMessage("§7Coordinates: §f"+e.x()+", "+e.y()+", "+e.z());
          if(e.kind().equals("WARP"))p.sendMessage("§7Destination: §f"+(store.target(e.name())!=null&&store.validLink(e,store.target(e.name()))?"Active":"Inactive"));
          else p.sendMessage("§7Linked Warp signs: §f"+store.linkedCount(e));
        }catch(SQLException ex){error(ex);p.sendMessage("§cUnable to inspect this sign.");}
        return true;
      }
      sender.sendMessage("§cUnknown WayBridge command. Use /waybridge help.");return true;
    });
    Objects.requireNonNull(getCommand("waybridge")).setTabCompleter(new WayBridgeTabCompleter(store));
    logStartupBanner();

  }
  private void helpLine(org.bukkit.command.CommandSender sender,String command,String description) {
    sender.sendMessage(Component.text("  "+command,NamedTextColor.AQUA)
        .append(Component.text("  — "+description,NamedTextColor.GRAY)));
  }
  private void sendHelp(org.bukkit.command.CommandSender sender,int requestedPage) {
    int page=Math.max(1,Math.min(2,requestedPage));
    sender.sendMessage(Component.text("──────── WayBridge Help ("+page+"/2) ────────",NamedTextColor.AQUA));
    if(page==1) {
      sender.sendMessage(Component.text("Create and use warp signs",NamedTextColor.WHITE));
      helpLine(sender,"[WarpTarget] / [WPT]","Line 1: create a destination (short form for hanging signs)");
      helpLine(sender,"[Warp] / [WP]","Line 1: create a connecting sign (short form for hanging signs)");
      sender.sendMessage(Component.text("  Line 2: warp name (1–16 letters, digits, spaces, _ or -)",NamedTextColor.GRAY));
      sender.sendMessage(Component.text("  WarpTarget line 3: Private (optional, case-insensitive)",NamedTextColor.GRAY));
      sender.sendMessage(Component.text("  Other lines are filled automatically",NamedTextColor.GRAY));
      sender.sendMessage(Component.text("  Right-click a linked [Warp] sign to teleport",NamedTextColor.GRAY));
      sender.sendMessage(Component.text("  Names are globally unique; create the target first",NamedTextColor.GRAY));
    } else {
      sender.sendMessage(Component.text("Commands and protection",NamedTextColor.WHITE));
      helpLine(sender,"/waybridge help [page]","Show these help pages");
      helpLine(sender,"/waybridge list","List registered destinations");
      helpLine(sender,"/waybridge inspect","Inspect the sign you're looking at");
      helpLine(sender,"/waybridge private | public","Then left-click your WarpTarget");
      helpLine(sender,"/waybridge relink","Left-click an orphaned Warp sign to reconnect");
      helpLine(sender,"/waybridge invite <target> <player>","Grant private access");
      helpLine(sender,"/waybridge uninvite <target> <player>","Revoke private access");
      helpLine(sender,"/waybridge invites <target>","List invited players");
      if(sender.hasPermission("waybridge.admin"))helpLine(sender,"/waybridge reservation info <name>","Inspect a name reservation (admin)");
      if(sender.hasPermission("waybridge.admin"))helpLine(sender,"/waybridge reservation clear <name>","Safely clear a name reservation with confirmation (admin)");
      if(sender.hasPermission("waybridge.admin"))helpLine(sender,"/waybridge reload","Reload configuration (admin)");
      if(sender.hasPermission("waybridge.admin"))helpLine(sender,"/waybridge migrate","SignWarpX migration and rollback wizards (admin)");
      sender.sendMessage(Component.text("  Registered signs cannot be edited; break and recreate them",NamedTextColor.GRAY));
      sender.sendMessage(Component.text("  Only owners and admins may break registered signs",NamedTextColor.GRAY));
    }
    Component navigation=Component.text("Page "+page+" of 2  ",NamedTextColor.GRAY);
    if(page>1)navigation=navigation.append(Component.text("◀ Previous",NamedTextColor.AQUA)
        .clickEvent(ClickEvent.runCommand("/waybridge help "+(page-1)))
        .hoverEvent(HoverEvent.showText(Component.text("Go to page "+(page-1)))));
    if(page<2)navigation=navigation.append(Component.text("  Next ▶",NamedTextColor.AQUA)
        .clickEvent(ClickEvent.runCommand("/waybridge help "+(page+1)))
        .hoverEvent(HoverEvent.showText(Component.text("Go to page "+(page+1)))));
    sender.sendMessage(navigation);
  }
  private void logStartupBanner() {
    String version = getPluginMeta().getVersion();
    String minecraft = getServer().getMinecraftVersion();
    String paper = Bukkit.getServer().getBukkitVersion().split("-")[0];
    // Paper builds may report versions such as 26.3.build.244.
    paper = paper.replaceFirst("\\.build\\..*$", "");
    String[] lines = {
      "[W] WayBridge v" + version,
      "by iruixos",
      "Paper " + paper + " / MC " + minecraft
    };
    int interior = Math.max(40, Arrays.stream(lines).mapToInt(String::length).max().orElse(0) + 2);
    String border = "+" + "-".repeat(interior) + "+";
    getLogger().info("");
    getLogger().info(border);
    for (String line : lines) {
      int spaces = interior - line.length();
      int left = spaces / 2;
      int right = spaces - left;
      getLogger().info("|" + " ".repeat(left) + line + " ".repeat(right) + "|");
    }
    getLogger().info(border);
    getLogger().info("");
    try {
      long targets = store.all().stream().filter(e -> e.kind().equals("TARGET")).count();
      long warps = store.all().stream().filter(e -> e.kind().equals("WARP")).count();
      getLogger().info("Loaded " + targets + " warp target" + (targets == 1 ? "" : "s")
        + ", " + warps + " warp sign" + (warps == 1 ? "" : "s"));
    } catch (SQLException ex) {
      getLogger().warning("Could not count registered signs: " + ex.getMessage());
    }
  }
  @Override public void onDisable(){if(refreshWorker!=null)refreshWorker.cancel();if(diagnosticWorker!=null)diagnosticWorker.cancel();pendingRefreshChunks.clear();queuedRefreshChunks.clear();pendingSignRefreshes.clear();dirtyTargets.clear();activeTeleports.clear();teleportOrigins.clear();waybridgeTeleportEvents.clear();for(UUID id:new ArrayList<>(pendingTeleports.keySet())) cancelCountdown(id,null);privacySelections.clear();relinkSelections.clear();pendingReservations.clear();if(migration!=null)migration.stop();if(store!=null)try{store.close();}catch(Exception e){getLogger().warning(e.toString());}closeDiagnosticLogging();}
  private void error(Exception ex){getLogger().severe("Database error: "+ex);}
  private boolean admin(Player p){return p.isOp()||p.hasPermission("waybridge.admin");}
  private boolean region(Player p,Location l){
    if(!getConfig().getBoolean("respect-worldguard",true)||!getServer().getPluginManager().isPluginEnabled("WorldGuard"))return true;
    try {
      var query=WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
      var local=WorldGuardPlugin.inst().wrapPlayer(p);
      return query.testState(BukkitAdapter.adapt(l),local,Flags.BUILD);
    }catch(Exception e){getLogger().warning("WorldGuard check failed: "+e);return false;}
  }
  /**
   * Public integration API for other plugins, including VanillaPlusSuite.
   * Returns true for any registered WayBridge sign, whether its destination
   * is currently active or missing. This is a read-only database lookup.
   *
   * Call on the primary server thread. If the database cannot be queried,
   * fail closed so integrations do not accidentally modify protected signs.
   *
   * @param block the block to check; null is treated as unregistered
   * @return whether WayBridge has a registration at this block position
   */
  public boolean isRegisteredSign(Block block) {
    if (block == null || store == null) return false;
    try {
      return store.at(block.getLocation()) != null;
    } catch (SQLException ex) {
      getLogger().warning("Unable to check registered sign: " + ex.getMessage());
      return true;
    }
  }

  private boolean sign(Block b){return b.getState() instanceof Sign;}
  private Store.Entry at(Block b){try{return store.at(b.getLocation());}catch(SQLException e){error(e);return null;}}
  private void processRefreshQueue() {
    int chunkBudget=Math.max(1,Math.min(8,getConfig().getInt("performance.chunk-lookups-per-tick",1)));
    int signBudget=Math.max(1,Math.min(64,getConfig().getInt("performance.sign-refreshes-per-tick",4)));
    for(int i=0;i<chunkBudget&&!pendingRefreshChunks.isEmpty();i++) {
      ChunkKey key=pendingRefreshChunks.pollFirst();
      queuedRefreshChunks.remove(key);
      World world=Bukkit.getWorld(key.world());
      if(world==null||!world.isChunkLoaded(key.x(),key.z()))continue;
      long start=System.nanoTime();
      try {pendingSignRefreshes.addAll(store.inChunk(key.world(),key.x(),key.z()));}
      catch(SQLException ex){error(ex);}
      finally {chunkLookupNanos+=System.nanoTime()-start;chunkLookups++;}
    }
    for(int i=0;i<signBudget&&!pendingSignRefreshes.isEmpty();i++) {
      Store.Entry entry=pendingSignRefreshes.pollFirst();
      long start=System.nanoTime();
      write(entry);  // Re-reads the current row before rendering.
      signRefreshNanos+=System.nanoTime()-start;
      signRefreshes++;
    }
    int limit=Math.max(128,getConfig().getInt("performance.max-pending-signs",4096));
    if(pendingSignRefreshes.size()>limit) {
      // Bound memory if a mass chunk load outruns the tick budget. Any skipped
      // signs will be reconciled on a future chunk load or explicit refresh.
      int discarded=0;
      while(pendingSignRefreshes.size()>limit){pendingSignRefreshes.pollLast();discarded++;}
      if(System.currentTimeMillis()-lastQueueWarning>60000) {
        getLogger().warning("Deferred sign refresh queue exceeded limit; skipped "+discarded+" refreshes (access control remains database-authoritative).");
        lastQueueWarning=System.currentTimeMillis();
      }
    }
  }
  private void initializeDiagnosticLogging() {
    if (!getConfig().getBoolean("performance.diagnostics", false)) return;
    try {
      Path logDirectory = Path.of("logs", "WayBridge");
      Files.createDirectories(logDirectory);
      // Rotate at 2 MiB, retain three files and append across server restarts.
      diagnosticFileHandler = new FileHandler(logDirectory.resolve("diagnostics-%g.log").toString(), 2 * 1024 * 1024, 3, true);
      diagnosticFileHandler.setFormatter(new Formatter() {
        private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        @Override public String format(LogRecord record) {
          return "[" + LocalDateTime.now().format(dateFormat) + "] " + record.getMessage() + System.lineSeparator();
        }
      });
      diagnosticLogger = Logger.getLogger("WayBridge.Diagnostics." + getName());
      diagnosticLogger.setUseParentHandlers(false);
      diagnosticLogger.addHandler(diagnosticFileHandler);
      getLogger().info("Performance diagnostics enabled; writing to logs/WayBridge/diagnostics-0.log (rotated).");
    } catch (IOException | SecurityException exception) {
      getLogger().warning("Unable to initialize WayBridge diagnostic file: " + exception.getMessage());
      closeDiagnosticLogging();
    }
  }

  private void closeDiagnosticLogging() {
    if (diagnosticFileHandler != null) {
      if (diagnosticLogger != null) diagnosticLogger.removeHandler(diagnosticFileHandler);
      diagnosticFileHandler.flush();
      diagnosticFileHandler.close();
      diagnosticFileHandler = null;
    }
    diagnosticLogger = null;
  }

  private void logPerformance() {
    if(getConfig().getBoolean("performance.diagnostics",false) && diagnosticLogger != null) {
      diagnosticLogger.info(String.format(java.util.Locale.ROOT,
          "Perf (60s): chunk queries=%d (%.2f ms total), sign refreshes=%d (%.2f ms total, %d unchanged), physics queries=%d (%.2f ms total); queued chunks=%d, signs=%d",
          chunkLookups,chunkLookupNanos/1_000_000.0,signRefreshes,signRefreshNanos/1_000_000.0,skippedSignUpdates,
          physicsLookups,physicsLookupNanos/1_000_000.0,pendingRefreshChunks.size(),pendingSignRefreshes.size()));
    }
    chunkLookupNanos=signRefreshNanos=physicsLookupNanos=0;
    chunkLookups=signRefreshes=physicsLookups=skippedSignUpdates=0;
  }
  // Both full and short sign headers are first-class formats. Keep the header
  // chosen by the player so hanging signs never overflow their text area.
  static boolean targetHeader(String header) {
    return header.equalsIgnoreCase("[WarpTarget]") || header.equalsIgnoreCase("[WPT]");
  }
  static boolean warpHeader(String header) {
    return header.equalsIgnoreCase("[Warp]") || header.equalsIgnoreCase("[WP]");
  }
  static boolean matchesHeader(String header, String kind) {
    return kind.equals("TARGET") ? targetHeader(header) : warpHeader(header);
  }
  private static boolean signMaterial(Material material) {
    String name=material.name();
    return name.endsWith("_SIGN")||name.endsWith("_HANGING_SIGN");
  }
  void write(Store.Entry e){
    // Entries held by the cosmetic queue can be older than the database. Never
    // let an old queued sign render overwrite a newer registration or count.
    try {
      Location registeredLocation=e.location();
      if(registeredLocation==null||!registeredLocation.isChunkLoaded())return;
      Store.Entry current=store.at(registeredLocation);
      if(current==null||!current.equals(e))return;
      if(!Objects.equals(store.targetId(current),store.targetId(e)))return;
      e=current;
    }catch(SQLException ex){error(ex);return;}
    Location loc=e.location();if(loc==null||!loc.isChunkLoaded())return;
    if(!(loc.getBlock().getState() instanceof Sign sign)){
      warnSignUpdate(e.position(),"Registered sign block is missing; cannot refresh");return;
    }
    // Never rewrite an unrelated replacement sign at a stale registered location.
    String currentHeader=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(sign.getSide(org.bukkit.block.sign.Side.FRONT).line(0)).trim();
    String currentName=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(sign.getSide(org.bukkit.block.sign.Side.FRONT).line(1)).trim();
    if(!matchesHeader(currentHeader,e.kind()) || !currentName.equalsIgnoreCase(e.name())){
      warnSignUpdate(e.position(),"Registered sign differs from world block; refusing to rewrite it");
      return;
    }
    try {
      Store.Entry target=e.kind().equals("TARGET")?e:store.target(e.name());
      int linkCount=e.kind().equals("TARGET")?store.linkedCount(e):0;
      boolean connected=e.kind().equals("TARGET")?linkCount>0:target!=null&&store.validLink(e,target);
      boolean privateTarget=target!=null&&(e.kind().equals("TARGET")||connected)&&store.isPrivate(target);
      NamedTextColor color=(e.kind().equals("WARP")&&!connected)?NamedTextColor.RED:privateTarget?NamedTextColor.LIGHT_PURPLE:(e.kind().equals("TARGET")?(connected?NamedTextColor.GREEN:NamedTextColor.YELLOW):NamedTextColor.GREEN);
      var side=sign.getSide(org.bukkit.block.sign.Side.FRONT);
      List<Component> before=List.of(side.line(0),side.line(1),side.line(2),side.line(3));
      side.line(0,Component.text(currentHeader,color));
      side.line(1,Component.text(e.name()));
      side.line(2,Component.text(Objects.requireNonNullElse(Bukkit.getOfflinePlayer(e.owner()).getName(),"Unknown")));
      if(e.kind().equals("TARGET"))side.line(3,Component.text(privateTarget?"Private ("+linkCount+")":linkCount+" linked",privateTarget?NamedTextColor.DARK_PURPLE:NamedTextColor.BLACK));
      else {
        // A public Warp sign identifies the destination dimension/world, not its coordinates.
        // Private signs deliberately retain their existing privacy indicator.
        String destinationWorld = "World unavailable";
        if (target != null && connected) {
          World world = Bukkit.getWorld(target.world());
          if (world != null) destinationWorld = world.getName();
        }
        side.line(3,Component.text(privateTarget&&connected?"Private":!connected?"Target missing":destinationWorld,
            (privateTarget&&connected)?NamedTextColor.DARK_PURPLE:NamedTextColor.BLACK));
      }
      if(before.equals(List.of(side.line(0),side.line(1),side.line(2),side.line(3)))) {
        skippedSignUpdates++;
        signUpdateWarnings.remove(e.position());
        return;
      }
      if(!sign.update(false,false))warnSignUpdate(e.position(),"Sign update failed; will retry on chunk load");
      else signUpdateWarnings.remove(e.position());
    }catch(SQLException ex){error(ex);}
  }
  private void warnSignUpdate(String position,String reason) {
    // One warning per location until a successful refresh; avoid chunk-load spam.
    if(signUpdateWarnings.add(position))getLogger().warning(reason+" at "+position);
  }
  private void effect(Location loc, String kind) {
    if(loc==null||loc.getWorld()==null||!getConfig().getBoolean("effects.enabled",true))return;
    String key="effects."+kind+".";
    World world=loc.getWorld();Location center=loc.clone().add(0.5,0.5,0.5);
    String configuredSound = getConfig().getString(key+"sound", "BLOCK_NOTE_BLOCK_PLING");
    if (configuredSound != null && !configuredSound.equalsIgnoreCase("NONE")) {
      // Accept both Bukkit enum-style names (ENTITY_ENDERMAN_TELEPORT)
      // and canonical registry keys (minecraft:entity.enderman.teleport).
      String soundId = configuredSound.trim().toLowerCase(Locale.ROOT);
      if (soundId.startsWith("minecraft:")) soundId = soundId.substring("minecraft:".length());
      String normalized = soundId.replace('.', '_');
      Sound sound = null;
      for (Sound candidate : Registry.SOUNDS) {
        NamespacedKey candidateKey = Registry.SOUNDS.getKey(candidate);
        if (candidateKey != null && candidateKey.getNamespace().equals(NamespacedKey.MINECRAFT)
            && (candidateKey.getKey().equals(soundId)
                || candidateKey.getKey().replace('.', '_').equals(normalized))) {
          sound = candidate;
          break;
        }
      }
      if (sound == null) {
        getLogger().warning("Unknown WayBridge sound: " + configuredSound);
      } else {
        world.playSound(center, sound,
            (float)getConfig().getDouble(key+"volume",0.8),
            (float)getConfig().getDouble(key+"pitch",1.0));
      }
    }
    String particleName=getConfig().getString(key+"particle","HAPPY_VILLAGER");
    if(!particleName.equalsIgnoreCase("NONE"))try {
      world.spawnParticle(Particle.valueOf(particleName),center,getConfig().getInt(key+"count",12),0.3,0.35,0.3,0.02);
    }catch(IllegalArgumentException ex){getLogger().warning("Unknown WayBridge particle: "+particleName);}
  }
  private void rejectWarp(Block b, Player p, String message) {
    p.sendMessage("§c"+message);
    effect(b.getLocation(),"failure");
    // Registration occurs one tick after the sign editor closes. Return the sign item
    // and remove the invalid sign rather than leaving an apparently functional sign.
    if(!(b.getState() instanceof Sign))return;
    // Wall-sign and hanging-sign block materials are not inventory items.
    // Capture the actual vanilla drops before removing the invalid block.
    Collection<ItemStack> drops = new ArrayList<>(b.getDrops());
    Location dropLocation=b.getLocation().add(0.5,0.5,0.5);
    b.setType(Material.AIR,false);
    for(ItemStack drop:drops)if(!drop.getType().isAir())
      b.getWorld().dropItemNaturally(dropLocation,drop);

  }
  private Location safeArrival(Location signLoc) {
    Block block=signLoc.getBlock();
    if(!(block.getState() instanceof Sign))return null;
    org.bukkit.block.BlockFace facing=org.bukkit.block.BlockFace.NORTH;
    if(block.getBlockData() instanceof WallSign wall)facing=wall.getFacing();
    else if(block.getBlockData() instanceof org.bukkit.block.data.type.Sign standing)
      facing=standing.getRotation();
    // Check in front of the sign first; then adjacent alternatives. Never place
    // the player inside the sign block or its supporting wall/tree.
    List<org.bukkit.block.BlockFace> faces=new ArrayList<>();faces.add(facing);
    for(var face:List.of(org.bukkit.block.BlockFace.NORTH,org.bukkit.block.BlockFace.EAST,
        org.bukkit.block.BlockFace.SOUTH,org.bukkit.block.BlockFace.WEST))if(!faces.contains(face))faces.add(face);
    for(var face:faces)for(int distance=1;distance<=2;distance++)for(int dy=0;dy>=-2;dy--){
      int fx=block.getX()+face.getModX()*distance;
      int fz=block.getZ()+face.getModZ()*distance;
      // Never synchronously load a neighboring chunk during safe-arrival checks.
      if(!block.getWorld().isChunkLoaded(fx >> 4,fz >> 4))continue;
      Block feet=block.getWorld().getBlockAt(fx,block.getY()+dy,fz);
      Block head=feet.getRelative(org.bukkit.block.BlockFace.UP);
      Block ground=feet.getRelative(org.bukkit.block.BlockFace.DOWN);
      if(feet.getY()<=block.getWorld().getMinHeight()||head.getY()>=block.getWorld().getMaxHeight())continue;
      if(!feet.isPassable()||!head.isPassable()||feet.isLiquid()||head.isLiquid()||
          ground.isPassable()||ground.isLiquid())continue;
      Location arrival=feet.getLocation().add(0.5,0,0.5);
      orientTowardSign(arrival, signLoc);
      return arrival;
    }
    return null;
  }
  // Location#setDirection expects a direction from the camera (eyes), not the feet.
  // Aim at the visible face of a wall sign rather than the centre of its supporting block.
  private void orientTowardSign(Location arrival, Location signLocation) {
    org.bukkit.util.Vector focus = signLocation.toVector().add(new org.bukkit.util.Vector(0.5, 0.5, 0.5));
    if (signLocation.getBlock().getBlockData() instanceof WallSign wall) {
      org.bukkit.block.BlockFace face = wall.getFacing();
      focus.add(new org.bukkit.util.Vector(face.getModX() * 0.45, 0, face.getModZ() * 0.45));
    }
    org.bukkit.util.Vector eyes = arrival.toVector().add(new org.bukkit.util.Vector(0, 1.62, 0));
    org.bukkit.util.Vector direction = focus.subtract(eyes);
    if (direction.lengthSquared() > 0.0001) arrival.setDirection(direction);
  }
  private void registrationMessage(Player player, Store.Entry entry, boolean created) {
    String kind = entry.kind().equals("TARGET") ? "WarpTarget" : "Warp";
    player.sendMessage(Component.text("[WayBridge]: ", NamedTextColor.AQUA)
        .append(Component.text(kind + " ", NamedTextColor.WHITE))
        .append(Component.text("\"" + entry.name() + "\"", NamedTextColor.DARK_PURPLE))
        .append(Component.text(created ? " created." : " deregistered.", NamedTextColor.WHITE)));
  }
  private void refreshTargetSign(Store.Entry target) {
    if(target==null||!"TARGET".equals(target.kind()))return;
    // Keep requests for unloaded targets even if the normal sign queue fills.
    dirtyTargets.put(target.position(),target);
    Location loc=target.location();
    if(loc==null||!loc.isChunkLoaded())return;
    try {
      Store.Entry current=store.at(loc);
      if(current==null||!current.equals(target)
          ||!Objects.equals(store.targetId(current),store.targetId(target))) {
        dirtyTargets.remove(target.position());return;
      }
      // Evaluate the link count at render time, never when the request is queued.
      write(current);
      // Any failed sign writes are still eligible for reconciliation on chunk load.
      if(!signUpdateWarnings.contains(target.position()))dirtyTargets.remove(target.position());
    }catch(SQLException ex){error(ex);}
  }
  private void refreshDirtyTargetsInChunk(ChunkKey key) {
    if(dirtyTargets.isEmpty())return;
    for(Store.Entry target:new ArrayList<>(dirtyTargets.values())) {
      if(target.world().equals(key.world()) && (target.x()>>4)==key.x()
          && (target.z()>>4)==key.z())refreshTargetSign(target);
    }
  }
  void refresh(String name,UUID owner){
    try {
      Store.Entry target=store.target(name);
      if(target!=null&&target.owner().equals(owner))refreshTargetSign(target);
      for(Store.Entry link:store.links(name,owner))write(link);
    }catch(SQLException ex){error(ex);}
  }
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void create(SignChangeEvent event){
    if(event.getSide()!=org.bukkit.block.sign.Side.FRONT)return;
    String header=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.line(0)==null?Component.empty():event.line(0)).trim();
    boolean target=targetHeader(header);boolean warp=warpHeader(header);if(!target&&!warp)return;
    Player p=event.getPlayer();String name=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.line(1)==null?Component.empty():event.line(1)).trim();
    if(!NAME.matcher(name).matches()||name.length()>getConfig().getInt("max-name-length",16)){p.sendMessage("§cInvalid name: use up to 16 letters, digits, spaces, _ or -.");return;}
    if(!p.hasPermission(target?"waybridge.create.target":"waybridge.create.warp")||!region(p,event.getBlock().getLocation())){p.sendMessage("§cYou cannot register a sign here.");return;}
    // Delay until the sign change is committed, and verify it remains unchanged.
    boolean requestedPrivate=target&&net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.line(2)==null?Component.empty():event.line(2)).trim().equalsIgnoreCase("Private");
    Bukkit.getScheduler().runTask(this,()->{
      Block b=event.getBlock();if(!(b.getState() instanceof Sign sign))return;
      try {
        if(store.at(b.getLocation())!=null)return;
        Store.Entry t=store.target(name);UUID claim=store.claim(name);
        if(target&&(t!=null||(claim!=null&&!claim.equals(p.getUniqueId())))){p.sendMessage("§cThat warp name is already reserved.");return;}
        if(warp&&t==null){rejectWarp(b,p,"Create the WarpTarget first.");return;}
        if(warp&&!store.allowed(t,p.getUniqueId())&&!admin(p)){rejectWarp(b,p,"This WarpTarget is private.");return;}
        var front=sign.getSide(org.bukkit.block.sign.Side.FRONT);
        String actual=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(front.line(0)).trim();
        if(!actual.equalsIgnoreCase(header))return;
        Store.Entry e=new Store.Entry(target?"TARGET":"WARP",name,p.getUniqueId(),b.getWorld().getUID(),b.getX(),b.getY(),b.getZ(),target?null:t.owner());
        store.add(e,requestedPrivate);
        write(e);refresh(name,target?p.getUniqueId():t.owner());
        effect(b.getLocation(),target?"target-created":"link-created");
        if(warp)effect(t.location(),"linked-target");
        registrationMessage(p,e,true);
      }catch(SQLException ex){error(ex);p.sendMessage("§cRegistration failed; check server logs.");}
    });
  }
  @EventHandler(priority=EventPriority.LOWEST) public void protectBreak(BlockBreakEvent event){Store.Entry e=at(event.getBlock());if(e==null)return;Player p=event.getPlayer();if((!e.owner().equals(p.getUniqueId())&&!admin(p)&&!(e.kind().equals("WARP")&&isTargetOwner(e,p)))||!region(p,event.getBlock().getLocation())){event.setCancelled(true);p.sendMessage("§cThis WayBridge sign is protected.");}}
  private boolean isTargetOwner(Store.Entry warp,Player p){
    try{Store.Entry target=store.target(warp.name());return target!=null&&store.validLink(warp,target)&&target.owner().equals(p.getUniqueId());}
    catch(SQLException ex){error(ex);return false;}
  }
  @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void broke(BlockBreakEvent event){Store.Entry e=at(event.getBlock());if(e==null)return;Bukkit.getScheduler().runTask(this,()->{if(event.getBlock().getState() instanceof Sign)return;try{store.remove(e);refresh(e.name(),e.kind().equals("TARGET")?e.owner():e.targetOwner());
        registrationMessage(event.getPlayer(),e,false);
        effect(event.getBlock().getLocation(),"unlinked");
        if(e.kind().equals("TARGET")){for(Store.Entry link:store.links(e.name(),e.owner()))effect(link.location(),"unlinked");}
        else {Store.Entry t=store.target(e.name());if(t!=null&&t.owner().equals(e.targetOwner()))effect(t.location(),"unlinked");}}catch(SQLException ex){error(ex);}});}
  // Paper 26.3: prevent submitted edits with SignChangeEvent. Existing sign
  // interactions are cancelled below, preventing vanilla editor access.
  @EventHandler(priority=EventPriority.LOWEST) public void change(SignChangeEvent event){if(at(event.getBlock())!=null)event.setCancelled(true);}
  // A matching name is only a hint: never grant access or reconnect on name alone.
  private void missingWarpMessage(Player player, Store.Entry warp) throws SQLException {
    Store.Entry replacement=store.target(warp.name());
    if(replacement!=null && !store.validLink(warp,replacement)
        && replacement.owner().equals(warp.targetOwner())
        && (warp.owner().equals(player.getUniqueId())
            || replacement.owner().equals(player.getUniqueId()) || admin(player))
        && (store.allowed(replacement,player.getUniqueId()) || admin(player))
        && region(player,warp.location())) {
      player.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
          .append(Component.text("A WarpTarget named ",NamedTextColor.WHITE))
          .append(Component.text("\""+replacement.name()+"\"",NamedTextColor.DARK_PURPLE))
          .append(Component.text(" is registered, but this Warp sign is not linked to it.",NamedTextColor.WHITE)));
      player.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
          .append(Component.text("Use ",NamedTextColor.WHITE))
          .append(Component.text("/waybridge relink",NamedTextColor.GREEN)
              .clickEvent(ClickEvent.runCommand("/waybridge relink"))
              .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to begin relinking"))))
          .append(Component.text(" and left-click this sign to reconnect it.",NamedTextColor.WHITE)));
    } else {
      player.sendMessage("§cThis warp's target is missing.");
    }
  }
  @EventHandler(priority=EventPriority.LOWEST) public void interact(PlayerInteractEvent event){
    if(event.getClickedBlock()==null)return;
    Block clicked=event.getClickedBlock();
    if(event.getAction()==Action.LEFT_CLICK_BLOCK){
      Player p=event.getPlayer();
      Long relinkUntil=relinkSelections.get(p.getUniqueId());
      if(relinkUntil!=null){
        event.setCancelled(true);
        if(System.currentTimeMillis()>relinkUntil){relinkSelections.remove(p.getUniqueId());p.sendMessage("§cWarp relink selection expired.");return;}
        Store.Entry warp=at(clicked);
        if(warp==null||!warp.kind().equals("WARP")){p.sendMessage("§eSelect a registered orphaned Warp sign.");return;}
        try {
          Store.Entry target=store.target(warp.name());
          if(target==null){p.sendMessage("§cNo WarpTarget with this name exists.");return;}
          if(store.validLink(warp,target)){relinkSelections.remove(p.getUniqueId());p.sendMessage("§eThis Warp sign is already linked.");return;}
          if(!target.owner().equals(warp.targetOwner())){p.sendMessage("§cThe new WarpTarget has a different owner; relinking is prohibited.");return;}
          if(!warp.owner().equals(p.getUniqueId())&&!target.owner().equals(p.getUniqueId())&&!admin(p)){
            p.sendMessage("§cOnly the Warp sign owner, WarpTarget owner, or an administrator can relink this sign.");return;
          }
          if(!store.allowed(target,p.getUniqueId())&&!admin(p)){
            p.sendMessage("§cYou do not have access to this private WarpTarget.");return;
          }
          if(!region(p,clicked.getLocation())){p.sendMessage("§cRegion protection prevents relinking here.");return;}
          if(!(clicked.getState() instanceof Sign physical)){
            p.sendMessage("§cThe registered Warp sign no longer exists.");return;
          }
          var plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
          var front=physical.getSide(org.bukkit.block.sign.Side.FRONT);
          if(!warpHeader(plain.serialize(front.line(0)).trim())
              ||!plain.serialize(front.line(1)).trim().equalsIgnoreCase(warp.name())){
            p.sendMessage("§cThe sign no longer matches its registration.");return;
          }
          if(!store.relink(warp,target)){p.sendMessage("§cUnable to relink; the sign may already be connected.");return;}
          relinkSelections.remove(p.getUniqueId());
          refresh(target.name(),target.owner());
          p.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
              .append(Component.text("Relinked Warp sign to ",NamedTextColor.WHITE))
              .append(Component.text("\""+target.name()+"\"",NamedTextColor.DARK_PURPLE))
              .append(Component.text(".",NamedTextColor.WHITE)));
        }catch(SQLException ex){error(ex);p.sendMessage("§cCould not relink this Warp sign.");}
        return;
      }
      PrivacySelection selection=privacySelections.get(event.getPlayer().getUniqueId());
      if(selection!=null){
        event.setCancelled(true);
        if(System.currentTimeMillis()>selection.expiresAt()){privacySelections.remove(p.getUniqueId());p.sendMessage("§cWarpTarget selection expired.");return;}
        Store.Entry chosen=at(clicked);
        if(chosen==null||!chosen.kind().equals("TARGET")){p.sendMessage("§eSelect a registered WarpTarget sign.");return;}
        if(!chosen.owner().equals(p.getUniqueId())&&!admin(p)){p.sendMessage("§cYou do not own that WarpTarget.");return;}
        if(!region(p,clicked.getLocation())){p.sendMessage("§cRegion protection prevents this change.");return;}
        try{
          store.setPrivate(chosen,selection.makePrivate());
          privacySelections.remove(p.getUniqueId());refresh(chosen.name(),chosen.owner());
          p.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA).append(Component.text("WarpTarget ",NamedTextColor.WHITE)).append(Component.text("\""+chosen.name()+"\"",NamedTextColor.DARK_PURPLE)).append(Component.text(" is now "+(selection.makePrivate()?"private":"public")+".",NamedTextColor.WHITE)));
        }catch(SQLException ex){error(ex);p.sendMessage("§cUnable to change privacy.");}
        return;
      }
    }
    Store.Entry e=at(clicked);
    if(e==null) {
      if(event.getAction()==Action.RIGHT_CLICK_BLOCK && event.getHand()==EquipmentSlot.HAND
          && clicked.getState() instanceof Sign oldSign) {
        String header=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(oldSign.getSide(org.bukkit.block.sign.Side.FRONT).line(0)).trim();
        if(warpHeader(header)||targetHeader(header)) {
          event.setCancelled(true);
          String warpName=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(oldSign.getSide(org.bukkit.block.sign.Side.FRONT).line(1)).trim();
          String signType=(targetHeader(header))?"WarpTarget":"Warp";
          if(migration!=null&&migration.isLegacySign(oldSign)){
            event.getPlayer().sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
                .append(Component.text("Legacy SignWarpX sign detected for ",NamedTextColor.WHITE))
                .append(Component.text("\""+warpName+"\"",NamedTextColor.DARK_PURPLE))
                .append(Component.text(". Ask an administrator to run ",NamedTextColor.WHITE))
                .append(Component.text("/waybridge migrate",NamedTextColor.DARK_PURPLE))
                .append(Component.text(" to convert it.",NamedTextColor.WHITE)));
            return;
          }
          event.getPlayer().sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
              .append(Component.text(signType+" ",NamedTextColor.WHITE))
              .append(Component.text("\""+warpName+"\"",NamedTextColor.DARK_PURPLE))
              .append(Component.text(" is not registered. Break and recreate this sign to register it.",NamedTextColor.WHITE)));
        }
      }
      return;
    }
    if(event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
    // Cancels editor opening, dye/glow/wax changes, and other right-click edits.
    // Applies to either hand; teleportation itself is main-hand only.
    event.setCancelled(true);
    if(event.getHand()!=EquipmentSlot.HAND)return;
    if(e.kind().equals("TARGET")) {
      refreshTargetSign(e);
      showWarpTargetOptions(event.getPlayer(),e);
      return;
    }
    if(!e.kind().equals("WARP"))return;
    Player p=event.getPlayer();if(!p.hasPermission("waybridge.use")){p.sendMessage("§cNo permission to use WayBridge.");return;}
    long now=System.currentTimeMillis();if(now-cooldown.getOrDefault(p.getUniqueId(),0L)<getConfig().getLong("teleport-cooldown-seconds",3)*1000L)return;
    try{
      Store.Entry t=store.target(e.name());if(t==null||!store.validLink(e,t)){missingWarpMessage(p,e);write(e);return;}
      if(!store.allowed(t,p.getUniqueId())&&!admin(p)){p.sendMessage("§cYou do not have permission to use this private WarpTarget.");return;}
      if(activeTeleports.containsKey(p.getUniqueId())||pendingTeleports.containsKey(p.getUniqueId())) {
        p.sendMessage("§eYou already have a teleport pending.");return;
      }
      if(getConfig().getBoolean("teleport.countdown.enabled",true)
          && getConfig().getInt("teleport.countdown.seconds",5)>0) {
        beginCountdown(p,e,t);
      } else {
        performTeleport(p,e.name(),e.targetOwner());
      }
    }catch(SQLException ex){error(ex);p.sendMessage("§cWarp lookup failed.");}
  }
  private void showWarpTargetOptions(Player player, Store.Entry target) {
    Component prefix=Component.text("[WayBridge]: ",NamedTextColor.AQUA);
    Component warpName=Component.text("\""+target.name()+"\"",NamedTextColor.DARK_PURPLE);
    // Right-click never opens the vanilla editor on registered WayBridge signs.
    if(!target.owner().equals(player.getUniqueId())&&!admin(player)) {
      player.sendMessage(prefix.append(Component.text("WarpTarget ",NamedTextColor.WHITE))
          .append(warpName).append(Component.text(" is protected; only its owner or an administrator can manage it.",NamedTextColor.WHITE)));
      return;
    }
    try {
      boolean privateTarget=store.isPrivate(target);
      String command=privateTarget?"/waybridge public":"/waybridge private";
      String label=privateTarget?"Make Public":"Make Private";
      player.sendMessage(prefix.append(Component.text("WarpTarget ",NamedTextColor.WHITE))
          .append(warpName).append(Component.text(" is currently "+(privateTarget?"private":"public")+". Registered signs cannot be edited directly.",NamedTextColor.WHITE)));
      player.sendMessage(Component.text("  ",NamedTextColor.GRAY)
          .append(Component.text("["+label+"]",NamedTextColor.AQUA)
              .clickEvent(ClickEvent.runCommand(command))
              .hoverEvent(HoverEvent.showText(Component.text("Run "+command+", then left-click this WarpTarget"))))
          .append(Component.text("  Click, then left-click the WarpTarget to change privacy.",NamedTextColor.GRAY)));
      player.sendMessage(Component.text("  To change its name or other sign text, break and recreate the WarpTarget.",NamedTextColor.GRAY));
    } catch(SQLException ex) {
      error(ex);
      player.sendMessage(prefix.append(Component.text("Unable to retrieve WarpTarget privacy settings.",NamedTextColor.RED)));
    }
  }

  private void beginCountdown(Player player, Store.Entry warp, Store.Entry target) {
    int seconds=Math.max(1,Math.min(3600,getConfig().getInt("teleport.countdown.seconds",5)));
    UUID id=player.getUniqueId();
    PendingTeleport pending=new PendingTeleport(player.getLocation().clone(),target.owner(),warp.name(),seconds);
    pendingTeleports.put(id,pending);
    showCountdown(player,seconds);
    playCountdownClick(player);
    pending.task=Bukkit.getScheduler().runTaskTimer(this,()->{
      if(pendingTeleports.get(id)!=pending)return;
      if(!player.isOnline()||player.isDead()) {cancelCountdown(id,null);return;}
      if(getConfig().getBoolean("teleport.countdown.cancel-on-movement",true)
          && moved(pending.origin,player.getLocation())) {
        cancelCountdown(id,player,"§cTeleport cancelled: you moved.");return;
      }
      pending.remaining--;
      if(pending.remaining<=0) {
        cancelCountdown(id,null);
        performTeleport(player,pending.warpName,pending.targetOwner);
      } else {
        showCountdown(player,pending.remaining);
        playCountdownClick(player);
      }
    },20L,20L);
  }
  private boolean moved(Location original, Location current) {
    return original.getWorld()!=current.getWorld()
        || Math.abs(original.getX()-current.getX())>0.05
        || Math.abs(original.getY()-current.getY())>0.05
        || Math.abs(original.getZ()-current.getZ())>0.05;
  }
  private void playCountdownClick(Player player) {
    if (!getConfig().getBoolean("teleport.countdown.click-sound.enabled", true)) return;
    String sound = getConfig().getString("teleport.countdown.click-sound.sound", "minecraft:block.stone_button.click_on");
    if (sound == null || sound.isBlank() || sound.equalsIgnoreCase("NONE")) return;
    float volume = (float) Math.max(0.0, Math.min(10.0, getConfig().getDouble("teleport.countdown.click-sound.volume", 0.7)));
    float pitch = (float) Math.max(0.5, Math.min(2.0, getConfig().getDouble("teleport.countdown.click-sound.pitch", 1.2)));
    // Sound keys are passed directly to Bukkit; no deprecated Sound.valueOf lookup.
    player.playSound(player.getLocation(), sound, volume, pitch);
  }
  private void showCountdown(Player player,int seconds) {
    if(getConfig().getBoolean("teleport.countdown.show-title",true)) {
      player.showTitle(Title.title(Component.text("Teleporting...",NamedTextColor.GREEN),
          Component.text("Please wait "+seconds+" second"+(seconds==1?"":"s"),NamedTextColor.GRAY),
          Title.Times.times(Duration.ZERO,Duration.ofMillis(1200),Duration.ZERO)));
    }
    if(getConfig().getBoolean("teleport.countdown.show-actionbar",true))
      player.sendActionBar(Component.text("Teleporting in "+seconds+" second"+(seconds==1?"":"s")+"...",NamedTextColor.GREEN));
  }
  private void cancelCountdown(UUID id,Player player,String message) {
    PendingTeleport pending=pendingTeleports.remove(id);
    if(pending==null)return;
    if(pending.task!=null)pending.task.cancel();
    if(player!=null&&player.isOnline()) {
      player.clearTitle();
      player.sendActionBar(Component.empty());
      if(message!=null)player.sendMessage(message);
    }
  }
  private void cancelCountdown(UUID id,Player player) {cancelCountdown(id,player,null);}
  private void invalidateTeleport(UUID playerId) {
    activeTeleports.remove(playerId);
    teleportOrigins.remove(playerId);
    waybridgeTeleportEvents.remove(playerId);
  }
  private boolean currentTeleport(Player player, UUID token) {
    return isEnabled() && player.isOnline() && !player.isDead()
        && token.equals(activeTeleports.get(player.getUniqueId()));
  }
  private void failTeleport(Player player,UUID token,String message) {
    if(!currentTeleport(player,token))return;
    invalidateTeleport(player.getUniqueId());
    player.sendMessage(message);
  }
  private void performTeleport(Player player,String name,UUID expectedOwner) {
    if(!player.isOnline()||player.isDead())return;
    UUID playerId=player.getUniqueId();
    if(activeTeleports.containsKey(playerId)){
      player.sendMessage("§eYou already have a teleport pending.");return;
    }
    if(!player.hasPermission("waybridge.use")){player.sendMessage("§cNo permission to use WayBridge.");return;}
    try {
      Store.Entry target=store.target(name);
      if(target==null||!target.owner().equals(expectedOwner)) {
        player.sendMessage("§cThis warp's target is missing.");return;
      }
      String targetId=store.targetId(target);
      if(targetId==null){player.sendMessage("§cThis warp's target is missing.");return;}
      if(!store.allowed(target,playerId)&&!admin(player)){
        player.sendMessage("§cYou do not have permission to use this private WarpTarget.");return;
      }
      Location location=target.location();
      if(location==null){player.sendMessage("§cTarget world is unavailable.");return;}
      UUID token=UUID.randomUUID();
      activeTeleports.put(playerId,token);
      teleportOrigins.put(playerId,player.getLocation().clone());
      // All Bukkit/SQLite access happens on the server thread, not in the future callback.
      location.getWorld().getChunkAtAsync(location.getBlockX() >> 4, location.getBlockZ() >> 4, true)
          .whenComplete((chunk, loadError) -> {
            if(!isEnabled())return;
            Bukkit.getScheduler().runTask(this, () -> {
              if(!currentTeleport(player,token))return;
              if(loadError!=null||chunk==null){
                getLogger().warning("Could not load destination chunk for warp '"+name+"': "+loadError);
                failTeleport(player,token,"§cCould not load the destination chunk.");return;
              }
              try {
                Store.Entry current=store.target(name);
                if(current==null||!current.equals(target)||!targetId.equals(store.targetId(current))){
                  failTeleport(player,token,"§cThis warp's target has changed or is missing.");return;
                }
                if(!player.hasPermission("waybridge.use")||(!store.allowed(current,playerId)&&!admin(player))){
                  failTeleport(player,token,"§cYou no longer have permission to use this WarpTarget.");return;
                }
                if(!(location.getBlock().getState() instanceof Sign physical)
                    ||!matchesRegisteredSign(physical,current)){
                  failTeleport(player,token,"§cTarget sign is missing or has changed.");return;
                }
                refreshTargetSign(current);
                Location origin=teleportOrigins.get(playerId);
                if(origin==null || (getConfig().getBoolean("teleport.countdown.cancel-on-movement",true)
                    && moved(origin,player.getLocation()))) {
                  failTeleport(player,token,"§cTeleport cancelled: you moved while the destination was loading.");return;
                }
                Location arrival=safeArrival(location);
                if(arrival==null){
                  failTeleport(player,token,"§cTarget arrival is obstructed or its surrounding chunk is unloaded.");
                  effect(player.getLocation(),"failure");return;
                }
                orientTowardSign(arrival,location);
                Location departure=player.getLocation().clone();
                waybridgeTeleportEvents.add(playerId);
                player.teleportAsync(arrival).whenComplete((ok,teleportError)->{
                  if(!isEnabled())return;
                  Bukkit.getScheduler().runTask(this,()->{
                    if(!currentTeleport(player,token))return;
                    invalidateTeleport(playerId);
                    if(teleportError!=null||!Boolean.TRUE.equals(ok)){
                      player.sendMessage("§cTeleportation failed.");return;
                    }
                    cooldown.put(playerId,System.currentTimeMillis());
                    effect(departure,"teleport-departure");
                    effect(player.getLocation(),"teleport-arrival");
                  });
                });
              }catch(SQLException ex){
                error(ex);failTeleport(player,token,"§cWarp lookup failed.");
              }catch(RuntimeException ex){
                getLogger().warning("Teleport validation failed for '"+name+"': "+ex);
                failTeleport(player,token,"§cTeleportation could not be completed.");
              }
            });
          });
    }catch(SQLException ex){error(ex);player.sendMessage("§cWarp lookup failed.");}
  }
  private boolean matchesRegisteredSign(Sign sign,Store.Entry entry) {
    var plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
    var front=sign.getSide(org.bukkit.block.sign.Side.FRONT);
    return targetHeader(plain.serialize(front.line(0)).trim())
        && plain.serialize(front.line(1)).trim().equalsIgnoreCase(entry.name());
  }
  @EventHandler public void countdownMove(PlayerMoveEvent event) {
    if(!getConfig().getBoolean("teleport.countdown.cancel-on-movement",true))return;
    UUID id=event.getPlayer().getUniqueId();
    PendingTeleport pending=pendingTeleports.get(id);
    if(pending!=null&&event.getTo()!=null&&moved(pending.origin,event.getTo()))
      cancelCountdown(id,event.getPlayer(),"§cTeleport cancelled: you moved.");
    Location origin=teleportOrigins.get(id);
    if(origin!=null&&event.getTo()!=null&&moved(origin,event.getTo())
        && !waybridgeTeleportEvents.contains(id)){
      invalidateTeleport(id);
      event.getPlayer().sendMessage("§cTeleport cancelled: you moved.");
    }
  }
  @EventHandler(ignoreCancelled=true) public void countdownDamage(EntityDamageEvent event) {
    if(event.getEntity() instanceof Player player && getConfig().getBoolean("teleport.countdown.cancel-on-damage",true)) {
      cancelCountdown(player.getUniqueId(),player,"§cTeleport cancelled: you took damage.");
      if(activeTeleports.containsKey(player.getUniqueId())) {
        invalidateTeleport(player.getUniqueId());
        player.sendMessage("§cTeleport cancelled: you took damage.");
      }
    }
  }
  @EventHandler public void migrationJoin(org.bukkit.event.player.PlayerJoinEvent event) {
    if(migration!=null)migration.onPlayerJoin(event.getPlayer());
  }
  @EventHandler public void countdownQuit(PlayerQuitEvent event) {invalidateTeleport(event.getPlayer().getUniqueId());cancelCountdown(event.getPlayer().getUniqueId(),null);privacySelections.remove(event.getPlayer().getUniqueId());relinkSelections.remove(event.getPlayer().getUniqueId());}
  @EventHandler public void countdownDeath(PlayerDeathEvent event) {invalidateTeleport(event.getEntity().getUniqueId());cancelCountdown(event.getEntity().getUniqueId(),null);}
  @EventHandler(ignoreCancelled=true) public void countdownOtherTeleport(PlayerTeleportEvent event) {
    // Do not let a countdown complete after another plugin teleports the player.
    if(pendingTeleports.containsKey(event.getPlayer().getUniqueId()))
      cancelCountdown(event.getPlayer().getUniqueId(),event.getPlayer(),"§cTeleport cancelled: your location changed.");
    // Invalidate pending async chunk loads when another plugin teleports the player.
    // Ignore WayBridge's own teleport, which completes through teleportAsync.
    if(activeTeleports.containsKey(event.getPlayer().getUniqueId())
        && !waybridgeTeleportEvents.contains(event.getPlayer().getUniqueId()))
      invalidateTeleport(event.getPlayer().getUniqueId());
  }
  @EventHandler(ignoreCancelled=true) public void explode(BlockExplodeEvent event){event.blockList().removeIf(b->at(b)!=null);}
  @EventHandler(ignoreCancelled=true) public void entityExplode(org.bukkit.event.entity.EntityExplodeEvent event){event.blockList().removeIf(b->at(b)!=null);}
  @EventHandler(ignoreCancelled=true) public void piston(BlockPistonExtendEvent event){if(event.getBlocks().stream().anyMatch(b->at(b)!=null))event.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void pistonRetract(BlockPistonRetractEvent event){if(event.getBlocks().stream().anyMatch(b->at(b)!=null))event.setCancelled(true);}
  @EventHandler(ignoreCancelled=true) public void physics(BlockPhysicsEvent event){
    // The overwhelming majority of physics events are not signs; never query
    // SQLite for them. Wall and hanging signs remain protected.
    if(!signMaterial(event.getBlock().getType()))return;
    long started=System.nanoTime();
    try{if(at(event.getBlock())!=null)event.setCancelled(true);}
    finally{physicsLookupNanos+=System.nanoTime()-started;physicsLookups++;}
  }
  @EventHandler public void chunk(ChunkLoadEvent event){
    ChunkKey key=new ChunkKey(event.getWorld().getUID(),event.getChunk().getX(),event.getChunk().getZ());
    if(queuedRefreshChunks.add(key))pendingRefreshChunks.addLast(key);
    // Reconcile important target updates separately from the bounded cosmetic
    // refresh queue; this does not load any additional chunks.
    if(!dirtyTargets.isEmpty())Bukkit.getScheduler().runTask(this,()->refreshDirtyTargetsInChunk(key));
  }
}
