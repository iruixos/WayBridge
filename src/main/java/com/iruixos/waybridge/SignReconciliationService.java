package com.iruixos.waybridge;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread, identity-aware registration reconciliation. Event-triggered
 * missing signs may be deregistered; merely observed stale registrations are
 * reported for explicit administrator repair, never silently removed.
 */
final class SignReconciliationService {
  enum Condition { MISSING, CONFLICT, VALID, UNAVAILABLE }
  private static final int MAX_CLEANUP_RETRIES = 3;
  private static final long INITIAL_RETRY_DELAY_TICKS = 20L;
  private record Pending(Store.Entry snapshot, String targetId, String reason, int failures) {}
  private final WayBridge plugin;
  private final Store store;
  private final Map<String, Pending> pending = new HashMap<>();
  private final Set<String> reportedMissing = new HashSet<>();

  SignReconciliationService(WayBridge plugin, Store store) {
    this.plugin=plugin;
    this.store=store;
  }
  void clear() {pending.clear(); reportedMissing.clear();}

  Condition classify(Store.Entry entry) {
    World world=Bukkit.getWorld(entry.world());
    if(world==null||!world.isChunkLoaded(entry.x()>>4,entry.z()>>4))return Condition.UNAVAILABLE;
    Block block=world.getBlockAt(entry.x(),entry.y(),entry.z());
    if(!(block.getState() instanceof Sign sign))return Condition.MISSING;
    String header=PlainTextComponentSerializer.plainText().serialize(sign.getSide(Side.FRONT).line(0)).trim().toLowerCase(Locale.ROOT);
    String name=PlainTextComponentSerializer.plainText().serialize(sign.getSide(Side.FRONT).line(1)).trim();
    boolean kindOk="TARGET".equals(entry.kind())
        ?header.equals("[warptarget]")||header.equals("[wpt]")
        :header.equals("[warp]")||header.equals("[wp]");
    return kindOk&&name.equalsIgnoreCase(entry.name())?Condition.VALID:Condition.CONFLICT;
  }

  /** Schedule a delayed post-physics check; do not change the event outcome. */
  void queue(Block signBlock, String reason) {
    if(!signBlock.getWorld().isChunkLoaded(signBlock.getX()>>4,signBlock.getZ()>>4))return;
    try {
      Store.Entry snapshot=store.at(signBlock.getLocation());
      if(snapshot==null)return;
      String key=snapshot.position();
      if(pending.containsKey(key))return;
      String id=store.targetId(snapshot);
      Pending request=new Pending(snapshot,id,reason,0);
      pending.put(key,request);
      Bukkit.getScheduler().runTaskLater(plugin,()->checkPending(key,request),3L);
    } catch(SQLException ex) {plugin.getLogger().warning("Reconciliation queue failed: "+ex.getMessage());}
  }

  private void checkPending(String key, Pending request) {
    // Ignore stale timer or chunk-load callbacks after a retry or replacement.
    if(pending.get(key)!=request)return;
    if(classify(request.snapshot())==Condition.UNAVAILABLE)return; // resume on chunk load
    try {
      cleanupIfMissing(request.snapshot(),request.targetId(),request.reason());
      pending.remove(key,request);
    } catch(SQLException ex) {
      int failures=request.failures()+1;
      String location=request.snapshot().position();
      if(failures>MAX_CLEANUP_RETRIES) {
        pending.remove(key,request);
        plugin.getLogger().warning("Reconciliation cleanup failed after "
            +MAX_CLEANUP_RETRIES+" retries for warp \""+request.snapshot().name()
            +"\" at "+location+": "+ex.getMessage()
            +". Use /waybridge repair scan to review the registration.");
        return;
      }
      Pending retry=new Pending(request.snapshot(),request.targetId(),request.reason(),failures);
      pending.put(key,retry);
      long delay=INITIAL_RETRY_DELAY_TICKS << (failures-1); // 1s, 2s, 4s at 20 TPS
      plugin.getLogger().warning("Reconciliation database cleanup failed for warp \""
          +request.snapshot().name()+"\" at "+location+" (retry "
          +failures+"/"+MAX_CLEANUP_RETRIES+" in "+delay+" ticks): "+ex.getMessage());
      Bukkit.getScheduler().runTaskLater(plugin,()->checkPending(key,retry),delay);
    }
  }

  /** A confirmed missing sign is removable only while its registration is unchanged. */
  boolean cleanupIfMissing(Store.Entry snapshot, String expectedId, String reason) throws SQLException {
    if(classify(snapshot)!=Condition.MISSING)return false;
    World world=Bukkit.getWorld(snapshot.world());
    if(world==null)return false;
    Store.Entry current=store.at(world.getBlockAt(snapshot.x(),snapshot.y(),snapshot.z()).getLocation());
    if(current==null||!current.equals(snapshot)||!Objects.equals(expectedId,store.targetId(current))
        ||classify(current)!=Condition.MISSING)return false;
    store.remove(current);
    reportedMissing.remove(snapshot.position());
    plugin.refresh(current.name(),"TARGET".equals(current.kind())?current.owner():current.targetOwner());
    if(reason!=null)logEnvironmentalCleanup(current,world,reason);
    return true;
  }

  void watchAttachedSigns(Block support, String reason) {
    if(!support.getWorld().isChunkLoaded(support.getX()>>4,support.getZ()>>4))return;
    for(org.bukkit.block.BlockFace face:new org.bukkit.block.BlockFace[]{
        org.bukkit.block.BlockFace.UP,org.bukkit.block.BlockFace.DOWN,
        org.bukkit.block.BlockFace.NORTH,org.bukkit.block.BlockFace.SOUTH,
        org.bukkit.block.BlockFace.EAST,org.bukkit.block.BlockFace.WEST}) {
      Block neighbour=support.getRelative(face);
      if(!neighbour.getWorld().isChunkLoaded(neighbour.getX()>>4,neighbour.getZ()>>4))continue;
      if(!(neighbour.getState() instanceof Sign))continue;
      var data=neighbour.getBlockData();
      String type=neighbour.getType().name();
      boolean attached=false;
      if(type.contains("WALL_")&&data instanceof org.bukkit.block.data.Directional directional)
        attached=neighbour.getRelative(directional.getFacing().getOppositeFace()).equals(support);
      else if(type.contains("HANGING_SIGN")&&!type.contains("WALL_"))
        attached=neighbour.getRelative(org.bukkit.block.BlockFace.UP).equals(support);
      else if(type.endsWith("_SIGN")&&!type.contains("WALL_")&&!type.contains("HANGING_"))
        attached=neighbour.getRelative(org.bukkit.block.BlockFace.DOWN).equals(support);
      if(attached)queue(neighbour,reason);
    }
  }

  /** Resume observed environmental changes when their chunk returns. */
  void onChunkLoad(UUID world,int chunkX,int chunkZ) {
    List<String> matching=new ArrayList<>();
    for(var entry:pending.entrySet()) {
      Store.Entry sign=entry.getValue().snapshot();
      if(sign.world().equals(world)&&(sign.x()>>4)==chunkX&&(sign.z()>>4)==chunkZ)
        matching.add(entry.getKey());
    }
    for(String key:matching) {
      Pending request=pending.get(key);
      if(request!=null)Bukkit.getScheduler().runTaskLater(plugin,()->checkPending(key,request),2L);
    }
  }

  /** Passive chunk inspection never removes registrations automatically. */
  void inspectLoadedChunk(List<Store.Entry> entries) {
    for(Store.Entry entry:entries) {
      Condition state=classify(entry);
      if(state==Condition.MISSING&&reportedMissing.add(entry.position()))
        plugin.getLogger().warning("Missing registered "+entry.kind()+" sign: Warp: \""+entry.name()
            +"\" | Location: "+entry.x()+", "+entry.y()+", "+entry.z()
            +" | Use /waybridge repair scan to review (no automatic deletion).");
      else if(state==Condition.VALID)reportedMissing.remove(entry.position());
    }
  }

  private void logEnvironmentalCleanup(Store.Entry current, World world, String reason) {
    String creator=Bukkit.getOfflinePlayer(current.owner()).getName();
    if(creator==null||creator.isBlank())creator="Unknown player";
    String signType="TARGET".equals(current.kind())?"WarpTarget":"Warp";
    String readableReason=reason.isBlank()?"Unknown":reason.substring(0,1).toUpperCase(Locale.ROOT)+reason.substring(1);
    plugin.getLogger().info("Environmentally destroyed "+signType+" sign deregistered: "
        +"Warp: \""+current.name()+"\" | Creator: "+creator
        +" | World: "+world.getName()
        +" | Location: "+current.x()+", "+current.y()+", "+current.z()
        +" | Reason: "+readableReason);
  }
}
