package com.iruixos.waybridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Conservative, conflict-detecting selective migration rollback. Never restores a whole world/database. */
final class Rollback {
  private record Snapshot(String key, UUID world, int x,int y,int z,String targetId,List<String> lines) {
    Location location(){World w=Bukkit.getWorld(world);return w==null?null:new Location(w,x,y,z);}
  }
  private final WayBridge plugin;
  private final Store store;
  private String selected;
  private UUID actor;
  private UUID nonce;
  private long expires;
  private boolean reviewed;
  Rollback(WayBridge plugin,Store store){this.plugin=plugin;this.store=store;}
  private void say(CommandSender s,String msg){s.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA).append(Component.text(msg,NamedTextColor.WHITE)));}
  private void action(CommandSender s,String label,String command){s.sendMessage(Component.text("  ["+label+"]",NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(command)));}
  private File root(){return new File(plugin.getDataFolder(),"migration-backups");}
  private File folder(String id){return new File(root(),id);}
  private boolean validId(String id){return id.matches("[0-9]{10,20}");}
  private List<Snapshot> manifest(File dir) throws IOException {
    Path path=dir.toPath().resolve("rollback-manifest.tsv");
    if(!Files.isRegularFile(path))throw new IOException("This migration predates guided rollback or has no complete manifest. Use manual recovery.");
    List<String> lines=Files.readAllLines(path,StandardCharsets.UTF_8);
    if(lines.size()<2||!lines.get(0).startsWith("# WayBridge rollback manifest v1"))throw new IOException("Unrecognized manifest format");
    List<Snapshot> out=new ArrayList<>();Set<String> positions=new HashSet<>();
    for(int n=1;n<lines.size()-1;n++){
      String[] fields=lines.get(n).split("\\t",-1);
      if(fields.length!=10)throw new IOException("Invalid manifest entry at line "+(n+1));
      String[] pos=fields[0].split(":",-1);
      if(pos.length!=4||!positions.add(fields[0]))throw new IOException("Invalid or duplicate position at line "+(n+1));
      try{
        List<String> json=new ArrayList<>();
        for(int i=2;i<10;i++)json.add(new String(Base64.getDecoder().decode(fields[i]),StandardCharsets.UTF_8));
        out.add(new Snapshot(fields[0],UUID.fromString(pos[0]),Integer.parseInt(pos[1]),Integer.parseInt(pos[2]),Integer.parseInt(pos[3]),fields[1],json));
      }catch(IllegalArgumentException ex){throw new IOException("Invalid manifest entry at line "+(n+1),ex);}
    }
    if(!lines.get(lines.size()-1).equals("COMPLETE\t"+out.size()))throw new IOException("Incomplete manifest; manual recovery required");
    return out;
  }
  private Map<String,List<String>> originals(File dir) throws IOException {
    Path journal=dir.toPath().resolve("migration-journal.tsv");
    if(!Files.isRegularFile(journal))throw new IOException("Original sign journal missing");
    Map<String,List<String>> originals=new HashMap<>();Set<String> applied=new HashSet<>();
    for(String line:Files.readAllLines(journal,StandardCharsets.UTF_8)){
      if(line.startsWith("#"))continue;
      String[] parts=line.split("\\t",-1);
      if(parts.length==10&&parts[1].equals("PREPARED")){
        List<String> values=new ArrayList<>();
        try{for(int i=2;i<10;i++)values.add(new String(Base64.getDecoder().decode(parts[i]),StandardCharsets.UTF_8));}
        catch(IllegalArgumentException ex){throw new IOException("Corrupt original sign data",ex);}
        if(originals.putIfAbsent(parts[0],values)!=null)throw new IOException("Duplicate journal entry");
      }else if(parts.length==2&&parts[1].equals("APPLIED"))applied.add(parts[0]);
    }
    originals.keySet().retainAll(applied);
    return originals;
  }
  private List<Store.Entry> preflight(File dir,List<Snapshot> snapshots) throws Exception {
    Map<String,List<String>> before=originals(dir);
    if(snapshots.isEmpty())throw new IOException("No migrated signs in this session");
    List<Store.Entry> entries=new ArrayList<>();Set<String> positions=new HashSet<>();
    for(Snapshot s:snapshots){
      if(!before.containsKey(s.key()))throw new IOException("Missing original sign journal for "+s.key());
      Location loc=s.location();
      if(loc==null||!loc.isChunkLoaded())throw new IOException("World/chunk unavailable at "+s.key()+"; load it and retry");
      if(!(loc.getBlock().getState() instanceof Sign sign))throw new IOException("Sign missing/replaced at "+s.key());
      Store.Entry entry=store.at(loc);
      if(entry==null||!s.targetId().equals(store.targetId(entry)))throw new IOException("Registration identity changed at "+s.key());
      for(Side side:List.of(Side.FRONT,Side.BACK))for(int i=0;i<4;i++){
        String current=GsonComponentSerializer.gson().serialize(sign.getSide(side).line(i));
        if(!current.equals(s.lines().get((side==Side.FRONT?0:4)+i)))throw new IOException("Sign text changed since migration at "+s.key());
      }
      entries.add(entry);positions.add(entry.position());
    }
    // Do not remove a migrated target if new links were created after migration.
    for(Store.Entry e:entries)if(e.kind().equals("TARGET")){
      for(Store.Entry link:store.links(e.name(),e.owner()))
        if(!positions.contains(link.position()) && store.validLink(link,e))throw new IOException("New linked Warp sign exists for '"+e.name()+"'; remove it before rollback");
    }
    return entries;
  }
  void command(CommandSender sender,String[] args){
    if(!sender.hasPermission("waybridge.admin")){say(sender,"Administrator permission required.");return;}
    if(args.length==0||args[0].equalsIgnoreCase("list")){
      sender.sendMessage(Component.text("──── WayBridge · Rollback Wizard ────",NamedTextColor.AQUA));
      say(sender,"Step 1 — Select a migration backup (new-format migrations only).");
      File[] dirs=root().listFiles(File::isDirectory);
      if(dirs==null||dirs.length==0){say(sender,"No migration backups found.");return;}
      java.util.Arrays.sort(dirs,(a,b)->b.getName().compareTo(a.getName()));
      for(File dir:dirs)if(validId(dir.getName())){
        try{int count=manifest(dir).size();action(sender,dir.getName()+" ("+count+" signs)","/waybridge migrate rollback select "+dir.getName());}
        catch(IOException ignored){}
      }
      say(sender,"Older/incomplete journals are intentionally excluded; manual recovery is still possible.");return;
    }
    if(args[0].equalsIgnoreCase("cancel")){selected=null;nonce=null;reviewed=false;say(sender,"Rollback wizard cancelled; nothing changed.");return;}
    if(args[0].equalsIgnoreCase("select")&&args.length==2){
      if(!validId(args[1])){say(sender,"Invalid backup ID.");return;}
      try{
        List<Snapshot> snaps=manifest(folder(args[1]));
        if(Files.exists(folder(args[1]).toPath().resolve("rollback-complete.txt"))) throw new IOException("This migration has already been rolled back");
        selected=args[1];actor=sender instanceof Player p?p.getUniqueId():null;nonce=UUID.randomUUID();reviewed=false;expires=System.currentTimeMillis()+120_000;
        say(sender,"Step 2 — Review rollback for migration "+selected+" ("+snaps.size()+" signs).");
        say(sender,"This will remove only this migration's registrations and restore their original sign text. No other database records will be restored.");
        say(sender,"SignWarpX must be disabled. All affected chunks must be loaded; conflicts will abort the rollback.");
        action(sender,"Check conflicts","/waybridge migrate rollback review "+selected+" "+nonce);
      }catch(Exception ex){say(sender,"Cannot select backup: "+ex.getMessage());}return;
    }
    if(args.length!=3||selected==null||!selected.equals(args[1])||nonce==null||!nonce.toString().equals(args[2])||System.currentTimeMillis()>expires||(actor!=null&&(!(sender instanceof Player p)||!actor.equals(p.getUniqueId())))){
      say(sender,"Outdated or unauthorized rollback action. Restart with /waybridge migrate rollback.");return;
    }
    if(plugin.getServer().getPluginManager().isPluginEnabled("SignWarpX")){say(sender,"Disable SignWarpX before rolling back migrated signs.");return;}
    try{
      File dir=folder(selected);List<Snapshot> snaps=manifest(dir);List<Store.Entry> entries=preflight(dir,snaps);
      if(args[0].equalsIgnoreCase("review")){
        reviewed=true;
        say(sender,"Step 3 — Preflight passed for "+entries.size()+" signs. No conflicts detected.");
        say(sender,"Final confirmation expires in 2 minutes. Back up your worlds before proceeding.");
        action(sender,"CONFIRM ROLLBACK","/waybridge migrate rollback apply "+selected+" "+nonce);
        action(sender,"Cancel","/waybridge migrate rollback cancel");return;
      }
      if(!args[0].equalsIgnoreCase("apply")||!reviewed){say(sender,"Complete conflict review first.");return;}
      // Create a second independent DB snapshot immediately before selective rollback.
      File safety=new File(dir,"waybridge-before-rollback-"+Instant.now().toEpochMilli()+".db");
      store.backup(safety);
      Path log=dir.toPath().resolve("rollback-progress.tsv");
      if(Files.exists(log))throw new IOException("A rollback attempt already exists; manual inspection required");
      Files.writeString(log,"# Rollback in progress; DB safety copy: "+safety.getName()+"\n",StandardOpenOption.CREATE_NEW);
      Map<String,List<String>> before=originals(dir);
      // Remove connecting signs before targets; the journal records each successful step.
      entries.sort((a,b)->Boolean.compare(a.kind().equals("TARGET"),b.kind().equals("TARGET")));
      for(Store.Entry entry:entries){
        Location loc=entry.location();
        if(loc==null||!(loc.getBlock().getState() instanceof Sign sign))throw new IOException("Sign vanished during rollback: "+entry.position());
        store.remove(entry);
        List<String> values=before.get(entry.position());
        for(Side side:List.of(Side.FRONT,Side.BACK))for(int i=0;i<4;i++)
          sign.getSide(side).line(i,GsonComponentSerializer.gson().deserialize(values.get((side==Side.FRONT?0:4)+i)));
        if(!sign.update(true,false))throw new IOException("Could not update original sign at "+entry.position());
        Files.writeString(log,entry.position()+"\tRESTORED\n",StandardOpenOption.APPEND);
      }
      Files.writeString(dir.toPath().resolve("rollback-complete.txt"),"Completed "+Instant.now()+"; "+entries.size()+" signs restored\n",StandardOpenOption.CREATE_NEW);
      selected=null;nonce=null;reviewed=false;
      say(sender,"Rollback complete: "+entries.size()+" original signs restored; migration registrations removed. Backup retained at "+dir);
    }catch(Exception ex){
      plugin.getLogger().severe("Rollback failed or blocked: "+ex.getMessage());
      say(sender,"Rollback blocked/failed: "+ex.getMessage());
      say(sender,"If rollback-progress.tsv exists, do not retry automatically; preserve the directory and inspect the partial changes.");
    }
  }
}
