package com.iruixos.waybridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import java.io.File;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Administrator-only, opt-in integrity scan. Never force-loads chunks. */
final class Repair {
  private final SignReconciliationService reconciliation;
  private record Candidate(Store.Entry entry, String targetId, SignReconciliationService.Condition condition) {}
  private record Preview(UUID token,long expires,List<Candidate> missing) {}
  private final WayBridge plugin;
  private final Store store;
  private final Map<String,Preview> previews=new HashMap<>();
  private List<Candidate> lastScan=List.of();
  private static final int EXPIRY_SECONDS=60;

  Repair(WayBridge plugin,Store store,SignReconciliationService reconciliation){this.plugin=plugin;this.store=store;this.reconciliation=reconciliation;}
  void clear(){previews.clear();lastScan=List.of();}
  private void say(CommandSender sender,String message){sender.sendMessage("§b[WayBridge]: §f"+message);}
  private String actor(CommandSender sender){return sender instanceof org.bukkit.entity.Player p?p.getUniqueId().toString():"CONSOLE";}
  private SignReconciliationService.Condition check(Store.Entry e) {
    return reconciliation.classify(e);
  }
  void command(CommandSender sender,String[] args){
    if(args.length==0){say(sender,"Usage: /waybridge repair scan | list | cleanup");return;}
    try {
      switch(args[0].toLowerCase(Locale.ROOT)) {
        case "scan" -> scan(sender);
        case "list" -> list(sender);
        case "cleanup" -> cleanup(sender);
        case "confirm" -> {
          if(args.length!=2){say(sender,"Use the clickable confirmation from /waybridge repair cleanup.");return;}
          confirm(sender,args[1]);
        }
        default -> say(sender,"Usage: /waybridge repair scan | list | cleanup");
      }
    }catch(SQLException ex){plugin.getLogger().warning("Repair operation failed: "+ex.getMessage());say(sender,"Database operation failed; see console. No unconfirmed deletions were performed.");}
  }
  private void scan(CommandSender sender) throws SQLException {
    previews.clear();
    List<Candidate> results=new ArrayList<>();
    for(Store.Entry entry:store.all()){
      SignReconciliationService.Condition state=check(entry);
      String id=store.targetId(entry);
      results.add(new Candidate(entry,id,state));
    }
    lastScan=List.copyOf(results);
    long missing=results.stream().filter(c->c.condition()==SignReconciliationService.Condition.MISSING).count();
    long conflict=results.stream().filter(c->c.condition()==SignReconciliationService.Condition.CONFLICT).count();
    long valid=results.stream().filter(c->c.condition()==SignReconciliationService.Condition.VALID).count();
    long unavailable=results.stream().filter(c->c.condition()==SignReconciliationService.Condition.UNAVAILABLE).count();
    say(sender,"Scan complete: "+valid+" valid, "+missing+" missing, "+conflict+" conflicts, "+unavailable+" unavailable/unloaded.");
    say(sender,"Only loaded chunks were inspected. No chunks were loaded and nothing was deleted.");
    if(missing+conflict>0)say(sender,"Use /waybridge repair list to review findings.");
  }
  private void list(CommandSender sender){
    if(lastScan.isEmpty()){say(sender,"No scan results. Run /waybridge repair scan first.");return;}
    int displayed=0;
    for(Candidate c:lastScan){
      if(c.condition()!=SignReconciliationService.Condition.MISSING&&c.condition()!=SignReconciliationService.Condition.CONFLICT)continue;
      if(++displayed>25)break;
      Store.Entry e=c.entry();
      sender.sendMessage("§7"+c.condition()+" §f"+e.kind()+" §5"+e.name()+" §7"+e.position());
    }
    say(sender,"Showing "+Math.min(displayed,25)+" findings (maximum 25). Conflicts require manual inspection and are never cleaned automatically.");
  }
  private void cleanup(CommandSender sender) throws SQLException {
    if(lastScan.isEmpty()){say(sender,"Run /waybridge repair scan first.");return;}
    List<Candidate> missing=lastScan.stream().filter(c->c.condition()==SignReconciliationService.Condition.MISSING).toList();
    if(missing.isEmpty()){say(sender,"No missing registrations eligible for cleanup.");return;}
    UUID token=UUID.randomUUID();
    previews.put(actor(sender),new Preview(token,System.currentTimeMillis()+EXPIRY_SECONDS*1000L,missing));
    say(sender,"Cleanup preview: "+missing.size()+" missing registrations. Unloaded chunks and conflicting signs will be preserved.");
    say(sender,"All entries are revalidated at confirmation; a database backup is made before deletion.");
    sender.sendMessage(Component.text("[WayBridge]: ",NamedTextColor.AQUA)
        .append(Component.text("[CONFIRM CLEANUP]",NamedTextColor.RED)
            .clickEvent(ClickEvent.runCommand("/waybridge repair confirm "+token))));
    say(sender,"Confirmation expires after "+EXPIRY_SECONDS+" seconds.");
  }
  private void confirm(CommandSender sender,String submitted) throws SQLException {
    Preview preview=previews.remove(actor(sender));
    if(preview==null||System.currentTimeMillis()>preview.expires()||!preview.token().toString().equals(submitted)){
      say(sender,"Confirmation expired or invalid. Run /waybridge repair cleanup again.");return;
    }
    List<Store.Entry> eligible=new ArrayList<>();
    int skipped=0;
    for(Candidate candidate:preview.missing()){
      if(candidate.entry().location()==null){skipped++;continue;}
      Store.Entry current=store.at(candidate.entry().location());
      if(current!=null&&current.equals(candidate.entry())
          &&java.util.Objects.equals(store.targetId(current),candidate.targetId())
          &&check(current)==SignReconciliationService.Condition.MISSING)eligible.add(current);
      else skipped++;
    }
    if(eligible.isEmpty()){say(sender,"No registrations remain eligible for cleanup. Skipped "+skipped+" changed/unavailable entries.");return;}
    File folder=new File(plugin.getDataFolder(),"repair-backups");
    if(!folder.exists()&&!folder.mkdirs())throw new SQLException("Unable to create repair backup directory");
    File backup=new File(folder,"before-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))+".db");
    store.backup(backup);
    int removed=0;
    for(Store.Entry entry:eligible){
      // Each removal is transactional and rechecked immediately before execution.
      Store.Entry current=store.at(entry.location());
      Candidate original=preview.missing().stream().filter(c->c.entry().equals(entry)).findFirst().orElse(null);
      if(current==null||!current.equals(entry)||original==null
          ||!java.util.Objects.equals(store.targetId(current),original.targetId())
          ||check(current)!=SignReconciliationService.Condition.MISSING){skipped++;continue;}
      if(reconciliation.cleanupIfMissing(current, original.targetId(), null))removed++;
      else skipped++;
    }
    plugin.getLogger().warning("Repair cleanup by "+sender.getName()+": removed "+removed+" stale registrations, skipped "+skipped+"; pre-cleanup backup "+backup.getPath());
    say(sender,"Removed "+removed+" missing registrations; skipped "+skipped+". Database backup: "+backup.getPath());
    lastScan=List.of();previews.clear();
  }
}
