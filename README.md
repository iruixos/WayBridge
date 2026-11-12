![WayBridge](assets/header.png)

# WayBridge

**Protected, sign-based teleportation for Minecraft Paper.** Create named destinations, link Warp signs anywhere on the same server, and travel between worlds with public or invitation-only access.

**Requirements:** Paper 26.3 · Java 25 · Optional WorldGuard integration · Maven for building from source.

## Features

- **Sign-based travel:** `[WarpTarget]` destinations and multiple `[Warp]` links per target.
- **Cross-world teleportation:** Travel between loaded worlds on the same Paper backend, including the Overworld, Nether, and End. Cross-server travel through a proxy is not supported.
- **Safe arrival:** Revalidates destination signs and permissions, finds a safe arrival location, and uses asynchronous destination chunk loading and teleportation.
- **Public and private targets:** Public by default; private targets can invite known players to use and create Warp signs. Private Warp signs display `Private` on line 4; public signs display the destination world name.
- **Persistent ownership:** UUID-based identities and SQLite storage protect sign registrations. Recreating a target with the same name does not automatically adopt orphaned links.
- **Orphan recovery:** Authorized owners can relink orphaned Warp signs with `/waybridge relink`.
- **Configurable countdown and effects:** Title/actionbar countdown, cancellation on movement or damage, sound and particle feedback.
- **Protection and integration:** Prevents unauthorized modification/destruction; respects WorldGuard build permissions when enabled, and exposes `isRegisteredSign(Block)` for integrations such as VanillaPlusSuite.
- **SignWarpX migration:** Guided migration wizard with previews, backups, progress indicators, and guided rollback for eligible new migrations.
- **Performance controls:** Rate-limited chunk sign synchronization, optional rotating diagnostics under `logs/WayBridge/`, and command tab completion.

## Installation

1. Download or build the **shaded** WayBridge JAR.
2. Stop the Paper server; place the JAR in `plugins/`.
3. Start the server. WayBridge creates `plugins/WayBridge/config.yml` and its SQLite data file (`plugins/WayBridge/data.db`).
4. Optionally configure settings, then restart as appropriate.

**Build from source:** Install JDK 25 and Maven, then run:

```bash
mvn clean package
```

Install the shaded JAR produced in `target/`. WorldGuard is optional on the server; its API is a provided compile-time dependency.

## Create a WarpTarget

Place a sign with:

```text
[WarpTarget]
Home


```

- Line 1: `[WarpTarget]`
- Line 2: globally unique name (1–16 characters; letters, digits, spaces, `_`, `-`)
- Line 3: optionally `Private` (case-insensitive); otherwise the target is public
- Line 4: leave empty; WayBridge fills it automatically

To connect another sign, place:

```text
[Warp]
Home


```

Right-click a registered `[Warp]` sign to travel. For a **public** target, line 4 displays the destination world name; for a **private** target it displays `Private`. Missing links show `Target missing`. WayBridge fills sign owner and status details automatically. WarpTarget signs show the linked-sign count.

A destination must exist before a connecting Warp sign can be registered. Destinations on separate Paper worlds are supported; separate proxy backend servers are not.

## Privacy, invitations and ownership

All WarpTargets are **public by default**. Create one as private with `Private` on line 3, or run `/waybridge private` and left-click an existing target within 30 seconds. `/waybridge public` reverses this.

```text
/waybridge invite Home PlayerName
/waybridge uninvite Home PlayerName
/waybridge invites Home
```

Invited players may use the target and create connecting Warp signs. Each sign retains its creator's ownership; the target owner and authorized administrators can also manage linked signs. Revoking an invitation removes destination access immediately but does not silently delete existing signs. Player names must already be available in the server's player-profile cache for invitations.

Existing links inherit privacy dynamically. Sign text for unloaded chunks is reconciled when those chunks load. Privacy authorization is checked against current registration data, independently of visual refresh timing.

## Commands

| Command | Purpose |
| --- | --- |
| `/waybridge` or `/waybridge help [page]` | Interactive help |
| `/waybridge list` | List registered WarpTargets |
| `/waybridge inspect` | Inspect the sign you're looking at |
| `/waybridge private` | Make a selected WarpTarget private (left-click) |
| `/waybridge public` | Make a selected WarpTarget public (left-click) |
| `/waybridge invite <target> <player>` | Grant access |
| `/waybridge uninvite <target> <player>` | Revoke access |
| `/waybridge invites <target>` | List target invitees |
| `/waybridge relink` | Select an orphaned Warp sign and reconnect it, subject to ownership checks |
| `/waybridge reload` | Reload configuration (admin) |
| `/waybridge migrate` | Guided SignWarpX migration (admin) |
| `/waybridge migrate rollback` | Guided migration rollback for eligible sessions (admin) |

Tab completion is permission-aware and suggests relevant target names and player names when authorized. The `list` command currently lists all registered targets and their coordinates; **private** means access-controlled, not hidden from the server-wide target listing.

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `waybridge.create.target` | Everyone | Create WarpTargets |
| `waybridge.create.warp` | Everyone | Create connecting Warp signs |
| `waybridge.use` | Everyone | Use Warp signs |
| `waybridge.admin` | OP | Administration and overrides |

WayBridge checks ownership and private invitation rules in addition to these permissions. When `respect-worldguard: true`, WorldGuard checks are applied where integrated.

## Configuration

The default `plugins/WayBridge/config.yml` covers:

- Warp name length and teleport cooldown
- Countdown duration, title/actionbar display, movement/damage cancellation, and click sounds
- Teleportation, registration, and failure effects
- WorldGuard build protection
- SignWarpX database path and scan limits
- Rate limits for chunk lookup and sign refresh
- Optional performance diagnostics

Example:

```yaml
teleport:
  countdown:
    enabled: true
    seconds: 5
performance:
  chunk-lookups-per-tick: 1
  sign-refreshes-per-tick: 4
  max-pending-signs: 4096
  diagnostics: false
```

With diagnostics enabled and the server restarted, aggregate performance data is written to `logs/WayBridge/diagnostics-0.log`, with rotating backups. Warnings and errors remain in Paper's standard `logs/latest.log`.

## SignWarpX migration and rollback

Run `/waybridge migrate` as an administrator to launch the guided wizard: choose SignWarpX, select a chunk-scan strategy, review results, and explicitly authorize conversion. The source SignWarpX SQLite database is read-only to WayBridge; conversion updates WayBridge registrations and physical sign text. **Disable SignWarpX before applying a migration.**

Before migrating, **back up both your world files and `plugins/WayBridge/`**. Scans can use loaded chunks or an administrator-approved full-world scan; migration journals and database snapshots help recovery.

`/waybridge migrate rollback` provides a guided rollback for migration sessions that generated the required final-state manifest. Older sessions may require manual recovery. Rollback checks for conflicts and requires loaded affected chunks; interruption recovery is not fully automatic. Never attempt a rollback without a separate world and database backup.

## Integration API

WayBridge's main plugin class provides:

```java
public boolean isRegisteredSign(org.bukkit.block.Block block)
```

Use it on the enabled WayBridge plugin instance, on the main server thread. It recognizes registered target and connecting signs, including orphaned Warp signs. This supports external sign-edit protection such as VanillaPlusSuite.

## Notes

- WarpTarget names are case-insensitive and globally unique.
- Destroying a WarpTarget can leave orphaned Warp signs. Their old identity is not reassigned automatically to a replacement with the same name; an authorized user can relink them.
- WayBridge cannot intercept every direct sign change made by arbitrary third-party plugins or NMS modifications.
- **First public release:** version 1.0.0 is based on the previously tested internal 1.0.29 codebase; only publication metadata and documentation have changed.

## Project information

- **Author:** iruixos
- **Platform:** Paper
- **Java package:** `com.iruixos.waybridge`
- **Development:** Includes AI-assisted code, tested by a human on a Paper server. :)

## License

WayBridge is licensed under the MIT License. See [LICENSE](LICENSE) for details.
