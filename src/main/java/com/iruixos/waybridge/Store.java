package com.iruixos.waybridge;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Store implements AutoCloseable {
  record Entry(String kind, String name, UUID owner, UUID world, int x, int y, int z, UUID targetOwner) {
    String position() { return world+":"+x+":"+y+":"+z; }
    Location location() { World w=Bukkit.getWorld(world); return w==null?null:new Location(w,x,y,z); }
  }
  private final Connection db;
  Store(File folder) throws Exception {
    folder.mkdirs();
    db=DriverManager.getConnection("jdbc:sqlite:"+new File(folder,"data.db").getAbsolutePath());
    try(Statement s=db.createStatement()) {
      s.execute("PRAGMA busy_timeout=5000");
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("CREATE TABLE IF NOT EXISTS signs (kind TEXT NOT NULL, name TEXT NOT NULL COLLATE NOCASE, owner TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, target_owner TEXT, PRIMARY KEY(world,x,y,z))");
      s.execute("CREATE UNIQUE INDEX IF NOT EXISTS unique_targets ON signs(name) WHERE kind='TARGET'");
      s.execute("CREATE INDEX IF NOT EXISTS linked_warps ON signs(name, target_owner)");
      s.execute("CREATE INDEX IF NOT EXISTS signs_by_chunk ON signs(world, x, z)");
      s.execute("CREATE TABLE IF NOT EXISTS target_access (target_id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE, owner TEXT NOT NULL, private INTEGER NOT NULL DEFAULT 0)");
      s.execute("CREATE TABLE IF NOT EXISTS target_invites (target_id TEXT NOT NULL, player TEXT NOT NULL, PRIMARY KEY(target_id,player))");
      // Add stable identities without destroying existing sign registrations.
      boolean hasId=false;
      try(ResultSet cols=s.executeQuery("PRAGMA table_info(signs)")){while(cols.next())if("target_id".equalsIgnoreCase(cols.getString("name")))hasId=true;}
      if(!hasId)s.execute("ALTER TABLE signs ADD COLUMN target_id TEXT");
      // Upgrade existing targets first, then associate only currently valid links.
      try(PreparedStatement q=db.prepareStatement("SELECT world,x,y,z,name,owner FROM signs WHERE kind='TARGET' AND target_id IS NULL");ResultSet r=q.executeQuery()){
        while(r.next()){
          String id=UUID.randomUUID().toString();
          try(PreparedStatement u=db.prepareStatement("UPDATE signs SET target_id=? WHERE world=? AND x=? AND y=? AND z=?")){
            u.setString(1,id);u.setString(2,r.getString(1));u.setInt(3,r.getInt(2));u.setInt(4,r.getInt(3));u.setInt(5,r.getInt(4));u.executeUpdate();}
        }
      }
      s.execute("INSERT OR IGNORE INTO target_access(target_id,name,owner,private) SELECT target_id,name,owner,0 FROM signs WHERE kind='TARGET' AND target_id IS NOT NULL");
      s.execute("UPDATE signs SET target_id=(SELECT t.target_id FROM signs t WHERE t.kind='TARGET' AND t.name=signs.name AND t.owner=signs.target_owner) WHERE kind='WARP' AND target_id IS NULL AND EXISTS(SELECT 1 FROM signs t WHERE t.kind='TARGET' AND t.name=signs.name AND t.owner=signs.target_owner)");
      s.execute("CREATE TABLE IF NOT EXISTS claims (name TEXT PRIMARY KEY COLLATE NOCASE, owner TEXT NOT NULL)");
    }
  }
  String targetId(Entry e) throws SQLException {
    try(PreparedStatement p=db.prepareStatement("SELECT target_id FROM signs WHERE world=? AND x=? AND y=? AND z=?")){
      p.setString(1,e.world().toString());p.setInt(2,e.x());p.setInt(3,e.y());p.setInt(4,e.z());
      try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}
    }
  }
  int linkedCount(Entry target) throws SQLException {
    String id=targetId(target);
    if(id==null)return 0;
    try(PreparedStatement p=db.prepareStatement("SELECT COUNT(*) FROM signs WHERE kind='WARP' AND target_id=? AND target_owner=?")){
      p.setString(1,id);p.setString(2,target.owner().toString());
      try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
    }
  }
  // A relink is an explicit identity change, never an automatic name match.
  boolean relink(Entry warp, Entry target) throws SQLException {
    if(!"WARP".equals(warp.kind())||!"TARGET".equals(target.kind())
        ||!warp.name().equalsIgnoreCase(target.name())
        ||!target.owner().equals(warp.targetOwner()))return false;
    String id=targetId(target);
    if(id==null)return false;
    try(PreparedStatement p=db.prepareStatement("UPDATE signs SET target_id=? WHERE kind='WARP' AND world=? AND x=? AND y=? AND z=? AND name=? AND target_owner=? AND (target_id IS NULL OR target_id<>?)")){
      p.setString(1,id);p.setString(2,warp.world().toString());p.setInt(3,warp.x());p.setInt(4,warp.y());p.setInt(5,warp.z());p.setString(6,warp.name());p.setString(7,warp.targetOwner().toString());p.setString(8,id);
      return p.executeUpdate()==1;
    }
  }
  boolean validLink(Entry warp, Entry target) throws SQLException {
    String a=targetId(warp), b=targetId(target);
    return a!=null&&a.equals(b)&&warp.targetOwner().equals(target.owner());
  }
  boolean isPrivate(Entry target) throws SQLException {
    String id=targetId(target);if(id==null)return false;
    try(PreparedStatement p=db.prepareStatement("SELECT private FROM target_access WHERE target_id=?")){
      p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()&&r.getInt(1)!=0;}}
  }
  void setPrivate(Entry target, boolean value) throws SQLException {
    try(PreparedStatement p=db.prepareStatement("UPDATE target_access SET private=? WHERE target_id=?")){
      p.setInt(1,value?1:0);p.setString(2,targetId(target));if(p.executeUpdate()!=1)throw new SQLException("Target identity missing");}
  }
  boolean allowed(Entry target, UUID player) throws SQLException {
    if(!isPrivate(target)||target.owner().equals(player))return true;
    try(PreparedStatement p=db.prepareStatement("SELECT 1 FROM target_invites WHERE target_id=? AND player=?")){
      p.setString(1,targetId(target));p.setString(2,player.toString());try(ResultSet r=p.executeQuery()){return r.next();}}
  }
  void invite(Entry target, UUID player, boolean add) throws SQLException {
    try(PreparedStatement p=db.prepareStatement(add?"INSERT OR IGNORE INTO target_invites(target_id,player) VALUES(?,?)":"DELETE FROM target_invites WHERE target_id=? AND player=?")){
      p.setString(1,targetId(target));p.setString(2,player.toString());p.executeUpdate();}
  }
  List<UUID> invites(Entry target) throws SQLException {
    List<UUID> result=new ArrayList<>();try(PreparedStatement p=db.prepareStatement("SELECT player FROM target_invites WHERE target_id=?")){
      p.setString(1,targetId(target));try(ResultSet r=p.executeQuery()){while(r.next())result.add(UUID.fromString(r.getString(1)));}}return result;
  }
  private Entry read(ResultSet r) throws SQLException { return new Entry(r.getString("kind"),r.getString("name"),UUID.fromString(r.getString("owner")),UUID.fromString(r.getString("world")),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("target_owner")==null?null:UUID.fromString(r.getString("target_owner"))); }
  private List<Entry> query(String sql, Object... args) throws SQLException {
    try(PreparedStatement p=db.prepareStatement(sql)) {for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(ResultSet r=p.executeQuery()){List<Entry> out=new ArrayList<>();while(r.next())out.add(read(r));return out;}}
  }
  Entry at(Location l) throws SQLException {return query("SELECT * FROM signs WHERE world=? AND x=? AND y=? AND z=?",l.getWorld().getUID().toString(),l.getBlockX(),l.getBlockY(),l.getBlockZ()).stream().findFirst().orElse(null);}
  Entry target(String name) throws SQLException {return query("SELECT * FROM signs WHERE kind='TARGET' AND name=?",name).stream().findFirst().orElse(null);}
  List<Entry> inChunk(UUID world,int cx,int cz) throws SQLException {return query("SELECT * FROM signs WHERE world=? AND x>=? AND x<? AND z>=? AND z<?",world.toString(),cx*16,cx*16+16,cz*16,cz*16+16);}
  List<Entry> all() throws SQLException {return query("SELECT * FROM signs");}
  List<Entry> links(String name, UUID owner) throws SQLException {return query("SELECT * FROM signs WHERE kind='WARP' AND name=? AND target_owner=?",name,owner.toString());}
  UUID claim(String name) throws SQLException {try(PreparedStatement p=db.prepareStatement("SELECT owner FROM claims WHERE name=?")){p.setString(1,name);try(ResultSet r=p.executeQuery()){return r.next()?UUID.fromString(r.getString(1)):null;}}}
  void add(Entry e) throws SQLException { add(e,false); }
  void add(Entry e,boolean privateTarget) throws SQLException {
    boolean auto=db.getAutoCommit();db.setAutoCommit(false);
    try {
      if(e.kind().equals("TARGET")) {
        try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO claims(name,owner) VALUES(?,?)")){p.setString(1,e.name());p.setString(2,e.owner().toString());p.executeUpdate();}
      }
      try(PreparedStatement p=db.prepareStatement("INSERT INTO signs(kind,name,owner,world,x,y,z,target_owner) VALUES(?,?,?,?,?,?,?,?)")){
        p.setString(1,e.kind());p.setString(2,e.name());p.setString(3,e.owner().toString());p.setString(4,e.world().toString());p.setInt(5,e.x());p.setInt(6,e.y());p.setInt(7,e.z());p.setString(8,e.targetOwner()==null?null:e.targetOwner().toString());p.executeUpdate();
      }
      String id=e.kind().equals("TARGET")?UUID.randomUUID().toString():null;
      if(e.kind().equals("WARP")) {
        Entry target=target(e.name());
        if(target==null||!target.owner().equals(e.targetOwner()))throw new SQLException("Target is no longer available");
        id=targetId(target);
      }
      try(PreparedStatement p=db.prepareStatement("UPDATE signs SET target_id=? WHERE world=? AND x=? AND y=? AND z=?")){
        p.setString(1,id);p.setString(2,e.world().toString());p.setInt(3,e.x());p.setInt(4,e.y());p.setInt(5,e.z());p.executeUpdate();}
      if(e.kind().equals("TARGET"))try(PreparedStatement p=db.prepareStatement("INSERT INTO target_access(target_id,name,owner,private) VALUES(?,?,?,?)")){
        p.setString(1,id);p.setString(2,e.name());p.setString(3,e.owner().toString());p.setInt(4,privateTarget?1:0);p.executeUpdate();}
      db.commit();
    } catch(SQLException ex){db.rollback();throw ex;}finally{db.setAutoCommit(auto);}
  }
  void backup(File destination) throws SQLException {
    try (Statement statement=db.createStatement()) {
      statement.execute("VACUUM INTO '"+destination.getAbsolutePath().replace("'","''")+"'");
    }
  }
  void remove(Entry e) throws SQLException {
    boolean auto=db.getAutoCommit();db.setAutoCommit(false);
    try {
      String removedTargetId=e.kind().equals("TARGET")?targetId(e):null;
      try(PreparedStatement p=db.prepareStatement("DELETE FROM signs WHERE world=? AND x=? AND y=? AND z=? AND kind=?")){
        p.setString(1,e.world().toString());p.setInt(2,e.x());p.setInt(3,e.y());p.setInt(4,e.z());p.setString(5,e.kind());
        if(p.executeUpdate()!=1)throw new SQLException("Sign registration was not found for deletion");
      }
      if(e.kind().equals("TARGET")) {
        String id=removedTargetId;
        if(id!=null){
          try(PreparedStatement p=db.prepareStatement("DELETE FROM target_invites WHERE target_id=?")){p.setString(1,id);p.executeUpdate();}
          try(PreparedStatement p=db.prepareStatement("DELETE FROM target_access WHERE target_id=?")){p.setString(1,id);p.executeUpdate();}
        }
      }
      // Retain ownership reservations while orphaned Warp signs still refer to
      // this owner. Release the name once no target or linked signs remain.
      try(PreparedStatement p=db.prepareStatement("DELETE FROM claims WHERE name=? AND NOT EXISTS (SELECT 1 FROM signs WHERE name=?)")){
        p.setString(1,e.name());p.setString(2,e.name());p.executeUpdate();
      }
      db.commit();
    }catch(SQLException ex){db.rollback();throw ex;}finally{db.setAutoCommit(auto);}
  }
  @Override public void close() throws SQLException {db.close();}
}
