package com.iruixos.waybridge;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** One migration session per server; source DB never opened for writing. */
final class Migration {
  private record Legacy(String name, String world, int x, int y, int z, UUID owner) {}
  private record Candidate(String kind, String name, UUID world, int x, int y, int z, UUID owner) {
    Location location() { World w=Bukkit.getWorld(world);return w==null?null:new Location(w,x,y,z); }
    String key() { return world+":"+x+":"+y+":"+z; }
  }
  private record ChunkPos(UUID world, int x, int z) {}
  private final WayBridge plugin;
  private final Store store;
  private final List<Legacy> legacy=new ArrayList<>();
  private final Map<String,Boolean> sourcePrivacy=new HashMap<>();
  private final Map<String,Candidate> candidates=new HashMap<>();
  private final Set<String> names=new HashSet<>();
  private final AtomicBoolean enumerationCancelled=new AtomicBoolean();
  private List<ChunkPos> queue=List.of();
  private int index;
  private int completed;
  private int pending;
  private long scanGeneration;
  private final Set<String> inFlight = new HashSet<>();
  private boolean scanning;
  private boolean enumerating;
  private long started;
  private long lastConsoleProgress;
  private long bossBarTick;
  private UUID viewer;
  private long confirmUntil;
  private UUID confirmer;
  private BossBar bar;
  private BukkitTask ticker;
  private String sourceMessage="Not inspected";
  private int failures;
  private boolean bestEffort;
  private final Map<String,Legacy> legacyByName=new HashMap<>();
  private static final Pattern REGION=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
  private static final PlainTextComponentSerializer PLAIN=PlainTextComponentSerializer.plainText();

  Migration(WayBridge plugin,Store store){this.plugin=plugin;this.store=store;this.rollback=new Rollback(plugin,store);loadDetectionIndex();}
  private void loadDetectionIndex(){
    String configured=plugin.getConfig().getString("migration.signwarpx-database","plugins/SignWarpX/database/data.db");
    File file=new File(configured);
    if(!file.isAbsolute())file=new File(plugin.getServer().getWorldContainer(),configured);
    if(!file.isFile())return;
    try(Connection db=DriverManager.getConnection("jdbc:sqlite:file:"+file.getCanonicalPath().replace('\\','/')+"?mode=ro");
        Statement statement=db.createStatement();ResultSet rows=statement.executeQuery("SELECT name,world,x,y,z FROM warps")){
      while(rows.next()){
        String name=rows.getString("name"),world=rows.getString("world");
        if(name!=null&&world!=null)legacyByName.put(name.trim().toLowerCase(Locale.ROOT),new Legacy(name,world,rows.getInt("x"),rows.getInt("y"),rows.getInt("z"),null));
      }
    }catch(Exception ex){plugin.getLogger().warning("Legacy SignWarpX detection index unavailable: "+ex.getMessage());}
  }
  boolean isLegacySign(Sign sign){
    try { if(store.at(sign.getBlock().getLocation())!=null)return false; }
    catch(SQLException ex){plugin.getLogger().warning("Unable to check sign registration during legacy detection: "+ex.getMessage());return false;}
    return looksLegacy(sign);
  }
  private boolean matchesLegacyTargetPosition(Sign sign, Legacy record) {
    if(record==null)return false;
    Block b=sign.getBlock();
    return b.getWorld().getName().equals(record.world())
        && Math.abs(b.getX()-record.x())<=4 && Math.abs(b.getY()-record.y())<=4
        && Math.abs(b.getZ()-record.z())<=4;
  }
  private void say(CommandSender sender,String message){sender.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA).append(Component.text(message,NamedTextColor.WHITE)));}
  private void menuLine(CommandSender sender,String command,String description){
    sender.sendMessage(Component.text("  • ",NamedTextColor.DARK_PURPLE)
        .append(Component.text(command,NamedTextColor.AQUA)
            .clickEvent(ClickEvent.suggestCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text("Click to fill in this command",NamedTextColor.GRAY))))
        .append(Component.text("  — "+description,NamedTextColor.GRAY)));
  }
  // Provider registry: only implemented providers appear in the wizard.
  private enum SourceProvider {
    SIGNWARPX("SignWarpX", "signwarpx", "Import legacy [WPT] and [WP] signs");
    final String label, id, description;
    SourceProvider(String label,String id,String description){this.label=label;this.id=id;this.description=description;}
  }
  private SourceProvider selectedProvider;
  private boolean scanFinished;
  private boolean reviewReady;
  private boolean applied;
  private final Rollback rollback;
  // Every rendered action is tied to a particular session and scan revision.
  private UUID wizardSession = UUID.randomUUID();
  private long wizardRevision = 0;
  private UUID wizardOwner;
  private void advanceWizard(){wizardRevision++;}
  private void newWizardSession(){wizardSession=UUID.randomUUID();wizardRevision++;}
  private boolean ownsWizard(CommandSender sender){
    if(wizardOwner==null || !(sender instanceof Player p))return true;
    if(wizardOwner.equals(p.getUniqueId()))return true;
    say(sender,"Another administrator owns the active migration session. Ask them to finish or cancel it.");
    return false;
  }
  private String uiCommand(String command){
    String prefix="/waybridge migrate ";
    if(!command.startsWith(prefix))return command;
    return prefix+"ui "+wizardSession+" "+wizardRevision+" "+command.substring(prefix.length());
  }
  private void action(CommandSender sender,String label,String command,String description){
    sender.sendMessage(Component.text("  » ",NamedTextColor.DARK_PURPLE)
        .append(Component.text("["+label+"]",NamedTextColor.AQUA)
            .clickEvent(ClickEvent.runCommand(uiCommand(command)))
            .hoverEvent(HoverEvent.showText(Component.text(description,NamedTextColor.GRAY))))
        .append(Component.text("  "+description,NamedTextColor.GRAY)));
  }
  private void wizard(CommandSender sender){
    sender.sendMessage(Component.text("──── WayBridge · Migration Wizard ────",NamedTextColor.AQUA));
    if(selectedProvider==null){
      sender.sendMessage(Component.text("Step 1 — Select Source Plugin",NamedTextColor.DARK_PURPLE));
      sender.sendMessage(Component.text("Choose the plugin to migrate from:",NamedTextColor.WHITE));
      for(SourceProvider provider:SourceProvider.values())
        action(sender,provider.label,"/waybridge migrate select "+provider.id,provider.description);
      return;
    }
    if(applied){
      action(sender,"Rollback migration","/waybridge migrate rollback","Review available migration recovery snapshots");
      sender.sendMessage(Component.text("Migration completed",NamedTextColor.DARK_PURPLE));
      say(sender,"Migration has been applied. Review the report above.");
      action(sender,"Start another migration","/waybridge migrate reset","Return to plugin selection");
      return;
    }
    if(enumerating||scanning){
      sender.sendMessage(Component.text("Scanning — "+selectedProvider.label,NamedTextColor.DARK_PURPLE));
      status(sender);
      action(sender,"Refresh progress","/waybridge migrate","View current progress");
      action(sender,"Cancel scan","/waybridge migrate cancel","Stop the scan without changing signs");
      return;
    }
    if(System.currentTimeMillis()<confirmUntil){
      sender.sendMessage(Component.text("Step 2 — Full-world scan confirmation",NamedTextColor.DARK_PURPLE));
      sender.sendMessage(Component.text("This may take a long time and impact server performance.",NamedTextColor.GOLD));
      action(sender,"Confirm scan","/waybridge migrate confirm","Start scanning existing chunks");
      action(sender,"Cancel","/waybridge migrate cancel","Return to scan options");
      return;
    }
    if(scanFinished && !reviewReady){
      sender.sendMessage(Component.text("Step 3 — Scan Complete",NamedTextColor.DARK_PURPLE));
      say(sender,"Discovery finished. "+summary());
      say(sender,"No signs have been modified. Review the discovery counts before proceeding.");
      action(sender,"Continue to Review","/waybridge migrate next","Proceed to Step 4");
      action(sender,"Scan again","/waybridge migrate signwarpx scan","Rescan loaded chunks");
      action(sender,"Full-world scan","/waybridge migrate signwarpx scan all","Include existing unloaded chunks");
      return;
    }
    if(scanFinished){
      sender.sendMessage(Component.text("Step 4 — Confirm Migration",NamedTextColor.DARK_PURPLE));
      say(sender,"Ready to convert discovered "+selectedProvider.label+" signs into WayBridge signs.");
      say(sender,"A backup will be created before any changes are made.");
      if(bestEffort)say(sender,"Best-effort: targets without verified owner UUIDs will be skipped.");
      action(sender,"Apply migration","/waybridge migrate signwarpx apply","Convert validated signs in place");
      action(sender,"Return to Step 3","/waybridge migrate previous","Review scan results without rescanning");
      return;
    }
    sender.sendMessage(Component.text("Step 2 — Choose scan method",NamedTextColor.DARK_PURPLE));
    say(sender,"Selected source: "+selectedProvider.label);
    action(sender,"Scan loaded chunks","/waybridge migrate signwarpx scan","Fast, read-only scan");
    action(sender,"Scan entire world","/waybridge migrate signwarpx scan all","Includes unloaded chunks; confirmation required");
    action(sender,"Back","/waybridge migrate back","Choose another source plugin");
  }
  private void menu(CommandSender sender,int requestedPage){wizard(sender);}
  void command(CommandSender sender,String[] args){
    if(args.length>0 && args[0].equalsIgnoreCase("rollback")){rollback.command(sender,java.util.Arrays.copyOfRange(args,1,args.length));return;}
    if(args.length==0){wizard(sender);return;}
    if(args[0].equalsIgnoreCase("ui")){
      if(args.length<4){say(sender,"Invalid migration action.");wizard(sender);return;}
      UUID token;
      long revision;
      try{token=UUID.fromString(args[1]);revision=Long.parseLong(args[2]);}
      catch(IllegalArgumentException ex){say(sender,"Invalid migration action.");wizard(sender);return;}
      if(!token.equals(wizardSession)||revision!=wizardRevision){
        sender.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
            .append(Component.text("Outdated migration option",NamedTextColor.GOLD)));
        sender.sendMessage(Component.text("This option belongs to an earlier migration step and can no longer be used.",NamedTextColor.WHITE));
        action(sender,"Show Current Step","/waybridge migrate","Display the current migration wizard screen");return;
      }
      command(sender,java.util.Arrays.copyOfRange(args,3,args.length));return;
    }
    if(!ownsWizard(sender))return;
    if(args[0].equalsIgnoreCase("select") && args.length>=2){
      for(SourceProvider provider:SourceProvider.values())if(provider.id.equalsIgnoreCase(args[1])){
        if(scanning||enumerating){say(sender,"Finish or cancel the current scan first.");return;}
        if(sender instanceof Player p)wizardOwner=p.getUniqueId();
        selectedProvider=provider;scanFinished=false;reviewReady=false;applied=false;confirmUntil=0;
        newWizardSession();wizard(sender);return;
      }
      say(sender,"That migration provider is not supported.");return;
    }
    if(args[0].equalsIgnoreCase("back")||args[0].equalsIgnoreCase("reset")){
      if(scanning||enumerating){say(sender,"Cancel the active scan first.");return;}
      selectedProvider=null;scanFinished=false;reviewReady=false;applied=false;confirmUntil=0;
      wizardOwner=null;newWizardSession();wizard(sender);return;
    }
    if(args[0].equalsIgnoreCase("signwarpx") && (args.length==1 || args[1].equalsIgnoreCase("help"))){
      int page=1;
      if(args.length>=3)try{page=Integer.parseInt(args[2]);}catch(NumberFormatException ignored){}
      wizard(sender);return;
    }
    if(args[0].equalsIgnoreCase("next")){
      if(!scanFinished || scanning || enumerating){say(sender,"Finish discovery before continuing to review.");wizard(sender);return;}
      reviewReady=true;advanceWizard();wizard(sender);return;
    }
    if(args[0].equalsIgnoreCase("previous")){
      if(!scanFinished||!reviewReady||scanning||enumerating){say(sender,"Step 3 is not available yet.");wizard(sender);return;}
      reviewReady=false;advanceWizard();wizard(sender);return;
    }
    if(args[0].equalsIgnoreCase("status")){status(sender);wizard(sender);return;}
    if(args[0].equalsIgnoreCase("cancel")){
      enumerationCancelled.set(true);scanning=false;enumerating=false;scanGeneration++;confirmUntil=0;reviewReady=false;advanceWizard();
      if(ticker!=null){ticker.cancel();ticker=null;}if(queue!=null&&!queue.isEmpty())plugin.getLogger().info("Migration scan cancelled: "+completed+" / "+queue.size()+" chunks processed.");hide();queue=List.of();scanFinished=false;say(sender,"Scan cancelled. No signs modified.");wizard(sender);return;
    }
    if(args[0].equalsIgnoreCase("confirm")){
      if(!(sender instanceof Player p) || !p.getUniqueId().equals(confirmer) || System.currentTimeMillis()>confirmUntil){say(sender,"No pending full-scan confirmation. Use /waybridge migrate signwarpx scan all.");return;}
      confirmUntil=0;advanceWizard();startFull(p);return;
    }
    if(args.length>=2&&args[0].equalsIgnoreCase("signwarpx")){
      if(selectedProvider==null)selectedProvider=SourceProvider.SIGNWARPX;
      if(args[1].equalsIgnoreCase("scan")){
        if(scanning||enumerating){say(sender,"A scan is already running.");return;}
        if(!readSource(sender))return;
        candidates.clear();names.clear();scanFinished=false;reviewReady=false;applied=false;confirmUntil=0;advanceWizard();
        if(args.length>=3&&args[2].equalsIgnoreCase("all")){
          if(!(sender instanceof Player p)){say(sender,"Full scan confirmation requires an in-game administrator.");return;}
          confirmer=p.getUniqueId();confirmUntil=System.currentTimeMillis()+60_000;
          sender.sendMessage(Component.text("──── WayBridge · Full-World Scan ────",NamedTextColor.AQUA));
          sender.sendMessage(Component.text("  ⚠ This may take considerable time on large worlds.",NamedTextColor.GOLD));
          sender.sendMessage(Component.text("  CPU, disk, memory and server tick performance may be affected.",NamedTextColor.GRAY));
          sender.sendMessage(Component.text("  Existing chunks only; no terrain generated or signs changed.",NamedTextColor.GRAY));
          sender.sendMessage(Component.text("  Confirm within 60 seconds: ",NamedTextColor.WHITE)
              .append(Component.text("[Confirm scan]",NamedTextColor.AQUA)
                  .clickEvent(ClickEvent.runCommand(uiCommand("/waybridge migrate confirm")))
                  .hoverEvent(HoverEvent.showText(Component.text("Begin scanning existing chunks"))))
              .append(Component.text("  ",NamedTextColor.GRAY))
              .append(Component.text("[Cancel]",NamedTextColor.DARK_PURPLE)
                  .clickEvent(ClickEvent.runCommand(uiCommand("/waybridge migrate cancel")))));return;
        }
        scanLoaded(sender);scanFinished=true;advanceWizard();wizard(sender);return;
      }
      if(args[1].equalsIgnoreCase("apply")){
        if(!scanFinished || !reviewReady || scanning || enumerating){say(sender,"Complete Step 3 and select Continue to Review before applying.");wizard(sender);return;}
        apply(sender);return;
      }
    }
    say(sender,"Unknown migration option. Use /waybridge migrate.");
  }
  private boolean readSource(CommandSender sender){
    String configured=plugin.getConfig().getString("migration.signwarpx-database","plugins/SignWarpX/database/data.db");
    File file=new File(configured);
    if(!file.isAbsolute())file=new File(plugin.getServer().getWorldContainer(),configured);
    legacy.clear();legacyByName.clear();sourcePrivacy.clear();bestEffort=false;failures=0;
    if(!file.isFile()){
      bestEffort=true;sourceMessage="Best-effort mode: SignWarpX database not available.";
      say(sender,"SignWarpX database not found. Continuing in best-effort mode using recognizable legacy signs.");
      say(sender,"Original ownership and custom arrival positions may not be recoverable; uncertain signs will be skipped.");
      return true;
    }
    try(Connection db=DriverManager.getConnection("jdbc:sqlite:file:"+file.getCanonicalPath().replace('\\','/')+"?mode=ro")){
      Set<String> columns=new HashSet<>();
      try(Statement schema=db.createStatement();ResultSet info=schema.executeQuery("PRAGMA table_info(warps)")){
        while(info.next())columns.add(info.getString("name").toLowerCase(Locale.ROOT));
      }
      for(String required:List.of("name","world","x","y","z")){
        if(!columns.contains(required)){
          bestEffort=true;sourceMessage="Best-effort mode: incompatible SignWarpX database.";
          say(sender,"SignWarpX database lacks '"+required+"'. Falling back to sign-based discovery.");return true;
        }
      }
      String ownerColumn=columns.contains("creator_uuid")?"creator_uuid":columns.contains("creator_uid")?"creator_uid":null;
      String query="SELECT name,world,x,y,z,"+(ownerColumn==null?"NULL":""+ownerColumn)+" AS owner_uuid,"+(columns.contains("is_private")?"is_private":"0")+" AS legacy_private FROM warps";
      int invalid=0;
      try(Statement statement=db.createStatement();ResultSet rows=statement.executeQuery(query)){
        while(rows.next()){
          String name=rows.getString("name"), world=rows.getString("world"), uid=rows.getString("owner_uuid");
          if(name==null||name.isBlank()||name.length()>plugin.getConfig().getInt("max-name-length",16)||!name.matches("[A-Za-z0-9_ -]+")||world==null||world.isBlank()){invalid++;continue;}
          UUID owner=null;
          if(uid!=null)try{owner=UUID.fromString(uid);}catch(IllegalArgumentException ignored){}
          Legacy record=new Legacy(name.trim(),world,rows.getInt("x"),rows.getInt("y"),rows.getInt("z"),owner);
          legacy.add(record);legacyByName.putIfAbsent(name.trim().toLowerCase(Locale.ROOT),record);sourcePrivacy.put(name.trim().toLowerCase(Locale.ROOT),rows.getInt("legacy_private")!=0);
        }
      }
      failures=invalid;sourceMessage=file.getAbsolutePath();
      say(sender,"Read "+legacy.size()+" destinations from SignWarpX (read-only)."+(ownerColumn==null?" Owner UUIDs unavailable.":""));
      if(invalid>0)say(sender,"Skipped "+invalid+" invalid database records.");
      return true;
    }catch(Exception ex){
      bestEffort=true;sourceMessage="Best-effort mode: source database unreadable.";
      legacy.clear();legacyByName.clear();
      plugin.getLogger().warning("SignWarpX database could not be read: "+ex.getMessage());
      say(sender,"Source database unreadable; continuing with best-effort sign discovery.");return true;
    }
  }
  // This is only a classification hint. It never authorizes automatic conversion.
  boolean looksLegacy(Sign sign){
    var side=sign.getSide(Side.FRONT);
    String header=PLAIN.serialize(side.line(0)).trim();
    String name=PLAIN.serialize(side.line(1)).trim();
    String third=PLAIN.serialize(side.line(2)).trim().toLowerCase(Locale.ROOT);
    // [WP] and [WPT] are also native WayBridge sign headers. Never infer
    // legacy provenance from the header or destination name alone.
    if(header.equalsIgnoreCase("[WPT]") || header.equalsIgnoreCase("[WarpTarget]"))
      return third.startsWith("creator:") ||
          matchesLegacyTargetPosition(sign,legacyByName.get(name.toLowerCase(Locale.ROOT)));
    if(header.equalsIgnoreCase("[WP]") || header.equalsIgnoreCase("[Warp]"))
      return third.startsWith("world:");
    return false;
  }
  private UUID resolveOwner(String creator){
    if(creator==null||creator.isBlank())return null;
    // Only use a known cached profile; never fabricate an offline-mode UUID.
    for(OfflinePlayer player:Bukkit.getOfflinePlayers())
      if(player.getName()!=null&&player.getName().equalsIgnoreCase(creator)&&player.hasPlayedBefore())return player.getUniqueId();
    return null;
  }
  private void scanLoaded(CommandSender sender){
    for(World world:Bukkit.getWorlds())for(Chunk chunk:world.getLoadedChunks())inspect(chunk);
    say(sender,"Loaded-chunk scan complete. "+summary());
  }
  private void startFull(Player p){
    enumerating=true;enumerationCancelled.set(false);viewer=p.getUniqueId();started=System.currentTimeMillis();lastConsoleProgress=started;bossBarTick=0;
    plugin.getLogger().info("Migration full-world scan: enumerating existing region chunks (initiated by "+p.getName()+").");
    final long generation=++scanGeneration;
    say(p,"Enumerating existing region-file chunks; the blue progress bar will appear when enumeration finishes.");
    // Region-file header enumeration happens off-thread. Never access Bukkit block states here.
    List<World> worlds=new ArrayList<>(Bukkit.getWorlds());
    CompletableFuture.supplyAsync(()->{
      List<ChunkPos> found=new ArrayList<>();
      for(World world:worlds){
        if(enumerationCancelled.get())break;
        Path region=world.getWorldFolder().toPath().resolve("region");
        if(!Files.isDirectory(region))continue;
        try(var paths=Files.list(region)){
          for(Path path:paths.filter(f->REGION.matcher(f.getFileName().toString()).matches()).toList()){
            if(enumerationCancelled.get())break;
            Matcher matcher=REGION.matcher(path.getFileName().toString());if(!matcher.matches())continue;
            int rx=Integer.parseInt(matcher.group(1)),rz=Integer.parseInt(matcher.group(2));
            try(var input=Files.newInputStream(path)){
              byte[] header=input.readNBytes(4096);if(header.length!=4096)continue;
              ByteBuffer data=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
              for(int slot=0;slot<1024;slot++){
                int location=data.getInt(slot*4);
                if((location>>>8)!=0&&(location&255)!=0)
                  found.add(new ChunkPos(world.getUID(),rx*32+(slot&31),rz*32+(slot>>5)));
              }
            }catch(IOException ex){plugin.getLogger().warning("Cannot inspect region "+path+": "+ex.getMessage());}
          }
        }catch(IOException ex){plugin.getLogger().warning("Cannot list regions in "+region+": "+ex.getMessage());}
      }
      return found;
    }).whenComplete((found,error)->Bukkit.getScheduler().runTask(plugin,()->{
      if(!enumerating || generation!=scanGeneration)return;
      enumerating=false;
      if(error!=null){say(p,"Region enumeration failed: "+error.getMessage());plugin.getLogger().warning("Migration region enumeration failed: "+error);return;}
      queue=found;index=0;completed=0;pending=0;inFlight.clear();scanning=true;lastConsoleProgress=System.currentTimeMillis();show(p);
      plugin.getLogger().info("Migration full-world scan started: "+queue.size()+" existing chunks across "+worlds.size()+" loaded worlds.");
      say(p,"Scanning "+queue.size()+" existing chunks. Use /waybridge migrate to view progress.");
      ticker=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1L,1L);
    }));
  }
  private void tick(){
    if(!scanning)return;
    reportConsoleProgress(false);
    int budget=Math.max(1,Math.min(16,plugin.getConfig().getInt("migration.scan.chunks-per-tick",2)));
    int limit=Math.max(1,Math.min(32,plugin.getConfig().getInt("migration.scan.max-concurrent-chunk-loads",4)));
    final long generation=scanGeneration;
    for(int i=0;i<budget && index<queue.size() && pending<limit;i++){
      ChunkPos pos=queue.get(index++);World world=Bukkit.getWorld(pos.world());
      if(world==null){failures++;completed++;continue;}
      // Paper loads chunks asynchronously; block-state inspection runs on the main thread.
      pending++;
      String requestKey=pos.world()+":"+pos.x()+":"+pos.z();
      inFlight.add(requestKey);
      boolean wasLoaded=world.isChunkLoaded(pos.x(),pos.z());
      world.getChunkAtAsync(pos.x(),pos.z(),false).whenComplete((chunk,err)->{
        if(!plugin.isEnabled())return;
        Bukkit.getScheduler().runTask(plugin,()->{
          if(generation!=scanGeneration || !scanning)return;
          try {
            if(err!=null)failures++;
            else if(chunk!=null)inspect(chunk);
          } finally {
            inFlight.remove(requestKey);pending--;completed++;
            // Never unload chunks that were already loaded by players or other systems.
            // Chunks loaded by the scanner are left to Paper's normal lifecycle.
          }
          if(index>=queue.size()&&pending==0)finish();
        });
      });
    }
    if(index>=queue.size()&&pending==0)finish();
    else if(++bossBarTick%Math.max(1,plugin.getConfig().getInt("migration.scan.bossbar.update-interval-ticks",20))==0)updateBar();
  }
  private void finish(){
    if(!scanning)return;
    scanning=false;scanFinished=true;advanceWizard();if(ticker!=null){ticker.cancel();ticker=null;}
    updateBar();
    reportConsoleProgress(true);
    plugin.getLogger().info("Migration full-world scan complete. "+summary());
    Player p=Bukkit.getPlayer(viewer);if(p!=null){say(p,"Full scan complete. "+summary());wizard(p);}
    Bukkit.getScheduler().runTaskLater(plugin,this::hide,100L);
  }
  private void inspect(Chunk chunk){
    for(org.bukkit.block.BlockState state:chunk.getTileEntities()){
      if(!(state instanceof Sign sign)||!looksLegacy(sign))continue;
      // [WP]/[WPT] are also native WayBridge aliases; exclude existing registrations.
      try { if(store.at(sign.getBlock().getLocation())!=null)continue; }
      catch(SQLException ex){plugin.getLogger().warning("Could not check registered sign during migration scan: "+ex.getMessage());failures++;continue;}
      var side=sign.getSide(Side.FRONT);
      String header=PLAIN.serialize(side.line(0)).trim();
      String name=PLAIN.serialize(side.line(1)).trim();
      if(name.isBlank()||name.length()>plugin.getConfig().getInt("max-name-length",16)||!name.matches("[A-Za-z0-9_ -]+")){failures++;continue;}
      Legacy record=legacyByName.get(name.toLowerCase(Locale.ROOT));
      if(!bestEffort&&record==null)continue;
      Block block=sign.getBlock();
      boolean target=header.equalsIgnoreCase("[WPT]")||header.equalsIgnoreCase("[WarpTarget]");
      if(target&&record!=null){
        if(!block.getWorld().getName().equals(record.world())||Math.abs(block.getX()-record.x())>4||Math.abs(block.getY()-record.y())>4||Math.abs(block.getZ()-record.z())>4)continue;
      }
      UUID owner=record==null?null:record.owner();
      if(owner==null&&target){
        String third=PLAIN.serialize(side.line(2)).trim();
        if(third.toLowerCase(Locale.ROOT).startsWith("creator:"))owner=resolveOwner(third.substring(8).trim());
      }
      Candidate candidate=new Candidate(target?"TARGET":"WARP",record==null?name:record.name(),block.getWorld().getUID(),block.getX(),block.getY(),block.getZ(),owner);
      candidates.putIfAbsent(candidate.key(),candidate);
    }
  }
  private String summary(){
    long targets=candidates.values().stream().filter(c->c.kind().equals("TARGET")).count();
    return (bestEffort?"Best-effort scan":legacy.size()+" source destinations")+"; "+targets+" targets and "+(candidates.size()-targets)+" connecting signs discovered; "+failures+" warnings.";
  }
  private void status(CommandSender sender){
    say(sender,sourceMessage);say(sender,summary());
    if(enumerating)say(sender,"Enumerating region files...");
    else if(scanning)say(sender,"Scan: "+completed+" / "+queue.size()+" chunks processed, "+pending+" loading ("+(queue.isEmpty()?100:(100*completed/queue.size()))+"%).");
    else say(sender,"No scan currently running.");
  }
  /** Restores the owner's progress display after reconnect; does not grant scan control. */
  void onPlayerJoin(Player player){
    if(!scanning || viewer==null || !viewer.equals(player.getUniqueId()))return;
    if(!player.isOp() && !player.hasPermission("waybridge.admin"))return;
    UUID id=player.getUniqueId();
    long generation=scanGeneration;
    Bukkit.getScheduler().runTaskLater(plugin,()->{
      Player online=Bukkit.getPlayer(id);
      if(online==null || !online.isOnline() || !scanning || generation!=scanGeneration || !id.equals(viewer))return;
      if(!online.isOp() && !online.hasPermission("waybridge.admin"))return;
      if(bar==null)show(online);
      else online.showBossBar(bar);
      updateBar();
    },20L);
  }
  private void reportConsoleProgress(boolean force){
    long now=System.currentTimeMillis();
    int interval=Math.max(0,plugin.getConfig().getInt("migration.scan.console-progress-interval-seconds",60));
    if(!force && (interval==0 || now-lastConsoleProgress < interval*1000L))return;
    lastConsoleProgress=now;
    int total=queue.size();
    double percent=total==0?100.0:completed*100.0/total;
    long elapsed=Math.max(0,(now-started)/1000);
    plugin.getLogger().info(String.format(Locale.ROOT,
        "Migration scan: %,d / %,d chunks (%.1f%%), %d loading, %d warnings, elapsed %dm %02ds",
        completed,total,percent,pending,failures,elapsed/60,elapsed%60));
  }
  private void show(Player player){
    if(!plugin.getConfig().getBoolean("migration.scan.bossbar.enabled",true))return;
    BossBar.Color color;
    try{color=BossBar.Color.valueOf(plugin.getConfig().getString("migration.scan.bossbar.color","BLUE").toUpperCase(Locale.ROOT));}
    catch(IllegalArgumentException ex){color=BossBar.Color.BLUE;}
    if(bar==null)bar=BossBar.bossBar(Component.text("WayBridge ",NamedTextColor.AQUA).append(Component.text("— SignWarpX Migration",NamedTextColor.DARK_PURPLE)),0f,color,BossBar.Overlay.PROGRESS);
    player.showBossBar(bar);
    updateBar();
  }
  private void updateBar(){
    if(bar==null)return;
    float progress=queue.isEmpty()?1f:Math.min(1f,(float)completed/queue.size());
    bar.progress(progress);
    bar.name(Component.text("WayBridge ",NamedTextColor.AQUA).append(Component.text("— "+completed+" / "+queue.size()+" chunks ("+(int)(progress*100)+"%)",NamedTextColor.DARK_PURPLE)));
  }
  private void hide(){if(bar!=null){Player p=viewer==null?null:Bukkit.getPlayer(viewer);if(p!=null)p.hideBossBar(bar);bar=null;}}
  void stop(){scanGeneration++;enumerationCancelled.set(true);scanning=false;enumerating=false;if(ticker!=null)ticker.cancel();hide();}
  private void apply(CommandSender sender){
    if(scanning||enumerating){say(sender,"Wait until scanning completes before applying.");return;}
    if(plugin.getServer().getPluginManager().isPluginEnabled("SignWarpX")){say(sender,"Disable SignWarpX before applying migration; both plugins must not manage the same signs.");return;}
    if(!scanFinished){say(sender,"Complete a discovery scan before applying.");return;}
    if(candidates.isEmpty()){say(sender,"No candidates found. Run a scan first.");return;}
    List<Candidate> targets=candidates.values().stream().filter(c->c.kind().equals("TARGET")).sorted(Comparator.comparing(Candidate::name)).toList();
    List<Candidate> links=candidates.values().stream().filter(c->c.kind().equals("WARP")).toList();
    // Preflight every candidate before writing a single block or registration.
    Set<String> targetNames=new HashSet<>();
    int conflicts=0;
    for(Candidate candidate:targets){
      String key=candidate.name().toLowerCase(Locale.ROOT);
      if(!targetNames.add(key))conflicts++;
      if(candidate.owner()==null)conflicts++;
    }
    for(Candidate candidate:candidates.values()){
      Location loc=candidate.location();
      if(loc==null || !loc.isChunkLoaded()){conflicts++;continue;}
      if(!(loc.getBlock().getState() instanceof Sign sign) || !looksLegacy(sign)){conflicts++;continue;}
      String header=PLAIN.serialize(sign.getSide(Side.FRONT).line(0)).trim();
      boolean targetHeader=header.equalsIgnoreCase("[WPT]")||header.equalsIgnoreCase("[WarpTarget]");
      if(targetHeader!=candidate.kind().equals("TARGET") || !PLAIN.serialize(sign.getSide(Side.FRONT).line(1)).trim().equalsIgnoreCase(candidate.name())){conflicts++;continue;}
      try{
        if(store.at(loc)!=null){conflicts++;continue;}
        if(candidate.kind().equals("TARGET") && store.target(candidate.name())!=null)conflicts++;
        UUID claimed=store.claim(candidate.name());
        if(candidate.kind().equals("TARGET") && claimed!=null && !claimed.equals(candidate.owner()))conflicts++;
        if(candidate.kind().equals("WARP") && !targetNames.contains(candidate.name().toLowerCase(Locale.ROOT)) && store.target(candidate.name())==null)conflicts++;
      }catch(SQLException ex){conflicts++;}
    }
    if(conflicts>0){say(sender,"Migration aborted: "+conflicts+" conflicts or stale/unverifiable signs. Rescan and resolve them before applying; no signs were modified.");return;}
    File backupDir=new File(plugin.getDataFolder(),"migration-backups/"+Instant.now().toEpochMilli());
    if(!backupDir.mkdirs()){say(sender,"Could not create backup directory; migration aborted.");return;}
    try{store.backup(new File(backupDir,"waybridge-before.db"));}
    catch(SQLException ex){say(sender,"Database backup failed; migration aborted: "+ex.getMessage());return;}
    // Log original sign lines BEFORE touching world blocks, allowing manual restoration.
    Path log=backupDir.toPath().resolve("migration-journal.tsv");
    try{Files.writeString(log,"# WayBridge migration journal: position, state, original front/back Adventure JSON (Base64 UTF-8)\n",StandardOpenOption.CREATE_NEW);}catch(IOException ex){say(sender,"Cannot create recovery journal; migration aborted: "+ex.getMessage());return;}
    int imported=0,skipped=0;
    List<Candidate> successfullyApplied=new ArrayList<>();
    List<Candidate> ordered=new ArrayList<>(targets);ordered.addAll(links);
    for(Candidate candidate:ordered){
      Location loc=candidate.location();if(loc==null){skipped++;continue;}
      Block block=loc.getBlock();if(!(block.getState() instanceof Sign sign)){skipped++;continue;}
      if(!looksLegacy(sign)){skipped++;continue;}
      String actualHeader=PLAIN.serialize(sign.getSide(Side.FRONT).line(0)).trim();
      boolean actualTarget=actualHeader.equalsIgnoreCase("[WPT]")||actualHeader.equalsIgnoreCase("[WarpTarget]");
      if(actualTarget!=candidate.kind().equals("TARGET")){skipped++;continue;}
      if(!PLAIN.serialize(sign.getSide(Side.FRONT).line(1)).trim().equalsIgnoreCase(candidate.name())){skipped++;continue;}
      try{
        if(store.at(loc)!=null){skipped++;continue;}
        if(candidate.kind().equals("TARGET")&&candidate.owner()==null){skipped++;continue;}
        Store.Entry target=store.target(candidate.name());
        if(candidate.kind().equals("TARGET") && (target!=null || (store.claim(candidate.name())!=null&&!store.claim(candidate.name()).equals(candidate.owner())))){skipped++;continue;}
        if(candidate.kind().equals("WARP")&&target==null){skipped++;continue;}
        UUID effectiveOwner=candidate.kind().equals("WARP")?target.owner():candidate.owner();
        StringBuilder before=new StringBuilder(candidate.key()).append("\tPREPARED");
        for(Side side:List.of(Side.FRONT,Side.BACK))for(int line=0;line<4;line++){
          String json=GsonComponentSerializer.gson().serialize(sign.getSide(side).line(line));
          before.append('\t').append(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        }
        Files.writeString(log,before+System.lineSeparator(),StandardOpenOption.APPEND);
        Store.Entry entry=new Store.Entry(candidate.kind(),candidate.name(),effectiveOwner,candidate.world(),candidate.x(),candidate.y(),candidate.z(),candidate.kind().equals("TARGET")?null:target.owner());
        store.add(entry);
        if(candidate.kind().equals("TARGET") && (bestEffort || sourcePrivacy.getOrDefault(candidate.name().toLowerCase(Locale.ROOT),false)))store.setPrivate(entry,true);

        sign.getSide(Side.FRONT).line(0,Component.text(
            (actualHeader.equalsIgnoreCase("[WP]")||actualHeader.equalsIgnoreCase("[WPT]"))
                ?actualHeader:(candidate.kind().equals("TARGET")?"[WarpTarget]":"[Warp]")));
        sign.update(true,false);
        plugin.write(entry);plugin.refresh(entry.name(),effectiveOwner);
        Files.writeString(log,candidate.key()+"\tAPPLIED"+System.lineSeparator(),StandardOpenOption.APPEND);imported++;successfullyApplied.add(candidate);
      }catch(SQLException|IOException ex){plugin.getLogger().warning("Migration skipped "+candidate.key()+": "+ex.getMessage());skipped++;}
    }
    // Capture the final post-migration state, not an intermediate target link count.
    // The rollback wizard refuses journals without this committed manifest.
    try {
      Path manifest=backupDir.toPath().resolve("rollback-manifest.tsv");
      List<String> lines=new ArrayList<>();
      lines.add("# WayBridge rollback manifest v1: position, target-id, final sign front/back JSON");
      for(Candidate candidate:successfullyApplied){
        Location loc=candidate.location();
        if(loc==null||!(loc.getBlock().getState() instanceof Sign sign))throw new IOException("Missing migrated sign "+candidate.key());
        Store.Entry entry=store.at(loc);
        if(entry==null)throw new IOException("Missing migrated registration "+candidate.key());
        String id=store.targetId(entry);
        if(id==null)throw new IOException("Missing target identity "+candidate.key());
        StringBuilder line=new StringBuilder(candidate.key()).append('\t').append(id);
        for(Side side:List.of(Side.FRONT,Side.BACK))for(int i=0;i<4;i++){
          String json=GsonComponentSerializer.gson().serialize(sign.getSide(side).line(i));
          line.append('\t').append(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        }
        lines.add(line.toString());
      }
      lines.add("COMPLETE\t"+successfullyApplied.size());
      Files.write(manifest,lines,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }catch(Exception ex){plugin.getLogger().severe("Could not finalize rollback manifest: "+ex.getMessage());say(sender,"WARNING: Automatic rollback is unavailable for this migration; retain the backups and journal.");}
    if(skipped>0)say(sender,"Partial migration: retain the journal and database backup; do not delete them. Re-running will skip existing registrations.");
    say(sender,"Migration finished: "+imported+" signs imported; "+skipped+" skipped. Backups: "+backupDir);
    if(bestEffort&&skipped>0)say(sender,"Some signs were skipped because their original owner could not be verified or their target was unavailable.");
    say(sender,"Imported signs are immediately active; no reload or restart needed.");
    applied=true;advanceWizard();wizard(sender);
  }
}
