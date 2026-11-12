package com.iruixos.waybridge;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Suggestions are hints only: every command must still enforce its own permissions. */
final class WayBridgeTabCompleter implements TabCompleter {
  private static final List<String> BASE = List.of(
      "help", "list", "inspect", "private", "public", "relink", "invite", "uninvite", "invites");
  private static final List<String> ADMIN = List.of("reload", "migrate", "reservation");
  private static final List<String> MIGRATION = List.of(
      "rollback", "select", "signwarpx", "back", "reset", "next", "previous", "status", "cancel", "confirm");
  private final Store store;

  WayBridgeTabCompleter(Store store) { this.store = store; }

  @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 0) return List.of();
    if (args.length == 1) {
      List<String> sub = new ArrayList<>(BASE);
      if (sender.hasPermission("waybridge.admin")) sub.addAll(ADMIN);
      return matches(sub, args[0]);
    }
    String action = args[0].toLowerCase(Locale.ROOT);
    if (action.equals("help") && args.length == 2) return matches(List.of("1", "2"), args[1]);
    if (action.equals("reservation")) {
      if (!sender.hasPermission("waybridge.admin")) return List.of();
      if (args.length == 2) return matches(List.of("info", "clear", "confirm"),args[1]);
      if ((args[1].equalsIgnoreCase("info")||args[1].equalsIgnoreCase("clear")) && args.length >= 3) {
        try { return matches(store.reservations(),String.join(" ",java.util.Arrays.copyOfRange(args,2,args.length))); }
        catch(SQLException ex){return List.of();}
      }
      // Confirmation tokens are presented only in the interactive preview.
      return List.of();
    }
    if (action.equals("migrate")) {
      if (!sender.hasPermission("waybridge.admin")) return List.of();
      if (args.length == 2) return matches(MIGRATION, args[1]);
      String step = args[1].toLowerCase(Locale.ROOT);
      if (step.equals("select") && args.length == 3) return matches(List.of("signwarpx"), args[2]);
      if (step.equals("signwarpx")) {
        if (args.length == 3) return matches(List.of("help", "scan", "apply"), args[2]);
        if (args.length == 4 && args[2].equalsIgnoreCase("scan")) return matches(List.of("all"), args[3]);
      }
      if (step.equals("rollback")) {
        if (args.length == 3) return matches(List.of("list", "select", "cancel"), args[2]);
        // Backup IDs are deliberately not suggested: the rollback wizard
        // lists validated backups and provides clickable, session-bound options.
      }
      return List.of();
    }
    if (!(sender instanceof Player player)) return List.of();
    if (!action.equals("invite") && !action.equals("uninvite") && !action.equals("invites")) return List.of();
    if (args.length == 2) return targetSuggestions(player, args[1]);
    if (args.length == 3 && !action.equals("invites")) return playerSuggestions(player, action, args[1], args[2]);
    return List.of();
  }

  private List<String> targetSuggestions(Player player, String prefix) {
    try {
      List<String> targets = new ArrayList<>();
      for (Store.Entry e : store.all()) {
        if (e.kind().equals("TARGET") && (e.owner().equals(player.getUniqueId()) || player.hasPermission("waybridge.admin"))) {
          targets.add(e.name());
        }
      }
      return matches(targets, prefix);
    } catch (SQLException ex) {
      return List.of(); // Never expose information or spam errors from a tab request.
    }
  }

  private List<String> playerSuggestions(Player player, String action, String targetName, String prefix) {
    try {
      Store.Entry target = store.target(targetName);
      if (target == null || (!target.owner().equals(player.getUniqueId()) && !player.hasPermission("waybridge.admin"))) {
        return List.of();
      }
      if (action.equals("uninvite")) {
        List<String> invited = new ArrayList<>();
        for (UUID id : store.invites(target)) {
          OfflinePlayer profile = Bukkit.getOfflinePlayer(id);
          if (profile.getName() != null) invited.add(profile.getName());
        }
        return matches(invited, prefix);
      }
      // Only previously-seen, cached players are eligible for invitations.
      List<String> cached = new ArrayList<>();
      for (Player profile : Bukkit.getOnlinePlayers()) {
        if (profile.getName() != null && !profile.getUniqueId().equals(target.owner())
            && Bukkit.getOfflinePlayerIfCached(profile.getName()) != null) cached.add(profile.getName());
      }
      return matches(cached, prefix);
    } catch (SQLException ex) {
      return List.of();
    }
  }

  private static List<String> matches(Collection<String> candidates, String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    return candidates.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower))
        .distinct().sorted(Comparator.comparing((String s) -> s.toLowerCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder()))
        .toList();
  }
}
