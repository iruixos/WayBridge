![WayBridge](assets/header.png)

# WayBridge

**Protected, sign-based teleportation for Minecraft Paper.** Create named destinations, link Warp signs anywhere on the same server, and travel between worlds with public or invitation-only access.

**Requirements:** Paper 26.3 · Java 25 · Optional WorldGuard integration · Maven for building from source.

> [!IMPORTANT]
> DISCLAIMER: This is directly inspired by the SignWarpX, Sign Warp, and Essentials sign warping plugins, however it does NOT use any source code from those projects.
> WayBridge is not intended to infringe upon anyone's intellectual property, and is not intended to be used commercially.
> For concerns, please contact me at **iruixos (at) pm (dot) me**

## Features

- **Sign-based travel:** `[WarpTarget]` / `[WPT]` destinations and multiple `[Warp]` / `[WP]` links per target.
- **Cross-world teleportation:** Travel between loaded worlds on the same Paper backend, including the Overworld, Nether, and End. Cross-server travel through a proxy is not supported.
- **Safe arrival:** Searches a bounded three-dimensional area for safe footing, validates collision geometry, hazards, and world borders, and uses asynchronous chunk loading and teleportation.
- **Public and private targets:** Public by default; private targets can invite known players to use and create Warp signs. Private Warp signs display `Private` on line 4; public signs display the destination world name.
- **Persistent ownership:** UUID-based identities and SQLite storage protect sign registrations. Recreating a target with the same name does not automatically adopt orphaned links.
- **Orphan recovery:** Authorized owners can relink orphaned Warp signs with `/waybridge relink`.
- **Configurable countdown and effects:** Title/actionbar countdown, cancellation on movement or damage, sound and particle feedback.
- **Protection and integration:** Prevents unauthorized modification/destruction; respects WorldGuard build permissions when enabled, and exposes `isRegisteredSign(Block)` for integrations such as VanillaPlusSuite.
- **SignWarpX migration:** Guided migration wizard with previews, backups, progress indicators, and guided rollback for eligible new migrations.
- **Environmental sign integrity (1.0.6):** Protects sign supports, restricts TNT and straw-bed attachments, warns about gravity-affected supports, and reconciles confirmed environmental sign destruction.
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

- Line 1: `[WarpTarget]` or `[WPT]` (compact alias, useful for hanging signs)
- Line 2: globally unique name (1–16 characters; letters, digits, spaces, `_`, `-`)
- Line 3: optionally `Private` (case-insensitive); otherwise the target is public
- Line 4: leave empty; WayBridge fills it automatically

To connect another sign, place:

```text
[Warp]
Home


```

Use `[Warp]` or the compact `[WP]` alias on line 1. Right-click a registered Warp sign to travel. For a **public** target, line 4 displays the destination world name; for a **private** target it displays `Private`. Missing links show `Target missing`. WayBridge fills sign owner and status details automatically. WarpTarget signs show the linked-sign count.

A destination must exist before a connecting Warp sign can be registered. Destinations on separate Paper worlds are supported; separate proxy backend servers are not.

## Safe arrival: technical details (1.0.5+)

WayBridge treats the WarpTarget sign as the **destination reference**, not as a guaranteed player spawn point. A sign may be suspended above the ground, attached to a cliff, underwater, or surrounded by hazards. The plugin attempts to find a nearby location where a player can arrive safely; if no candidate passes its checks, **teleportation is refused** rather than forcing an unsafe arrival.

### Search area and candidate ranking

The search is centred on the registered WarpTarget's block coordinates and covers a configurable **horizontal radius** and **vertical radius** in both directions. By default, WayBridge checks locations within **5 blocks horizontally** and **8 blocks above or below** the sign. The horizontal search is circular (`dx² + dz² ≤ radius²`), not a square; the vertical range is independent.

Candidate standing positions are ordered by a distance score:

```text
score = dx² + dz² + 0.20 × dy²
```

This favours positions close to the sign horizontally while still allowing a safe location at a different elevation, such as ground below a hanging sign. WayBridge chooses the **first candidate that passes every safety check**, not necessarily the first open air block.

### What qualifies as a safe landing?

Each candidate must pass all of the following checks:

1. **Valid world and border:** The location must be within the world's build-height limits and the player's arrival footprint must lie inside the world border.
2. **Two clear blocks for the player:** The feet and head blocks must be passable, non-liquid, non-waterlogged, and have **empty collision shapes**. This is more restrictive than a simple air/passability test.
3. **Reliable footing:** The block beneath the player must be solid, non-hazardous, not waterlogged, and have a suitable full-height collision surface. Partial or irregular surfaces such as slabs and stairs are deliberately excluded.
4. **Hazard avoidance:** The arrival column, supporting block, and cardinal neighbours are checked for dangerous blocks. Examples include lava, fire, magma, cactus, powder snow, berry bushes, wither roses, cobwebs, portals and bubble columns. Water or waterlogged blocks are not accepted as arrival space.
5. **Stable 2×2 area:** The landing block must form part of a usable 2×2 footprint: two adjacent cardinal neighbours and their shared diagonal must also have clear standing space and safe footing. This prevents choosing isolated single blocks or precarious one-block-wide cliff ledges.
6. **Hanging-sign protection:** WayBridge rejects a landing on a block directly above a hanging sign. The safe-area test also excludes such blocks from the surrounding footprint.

These checks are deliberately conservative. Some locations a player could navigate manually, such as a one-block-wide corridor or a slab platform, may **not** qualify as safe arrival locations. The checks assess the **initial landing**, not every hazard a player may approach after teleporting.

### Chunk loading and teleport validation

Before searching, WayBridge requests the destination-area chunks using Paper's asynchronous chunk-loading API. It asks for **existing chunks only** (`generate = false`), so searching does not generate unexplored terrain. Chunks that remain unavailable are skipped rather than synchronously loaded by the arrival search. A failed neighbouring chunk request does not automatically prevent teleportation if another loaded candidate is safe.

The arrival evaluation and Bukkit block checks run on the **server thread** after the asynchronous load phase. WayBridge then verifies the registered WarpTarget's identity, physical sign, and the player's current access permissions. Immediately before calling `teleportAsync()`, it repeats the destination/permission checks and rechecks the chosen arrival's safety. A change detected at this stage cancels the teleport.

As with any asynchronous teleport, WayBridge cannot guarantee protection against external plugins modifying the arrival location *after* the final check. It will report an unsuccessful teleport instead of claiming it completed.

### Configuration

```yaml
teleport:
  safe-arrival:
    horizontal-radius: 5  # accepted range: 1–8 blocks
    vertical-radius: 8    # accepted range: 1–16 blocks, up and down
```

The values above are the defaults. The search remains bounded; larger values increase the candidate area and can require additional nearby chunk loads. When no position meets every criterion, the player sees **`No safe arrival location was found near this WarpTarget.`** and stays at the departure location.

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
| `/waybridge reservation info <name>` | Inspect a name reservation (admin) |
| `/waybridge reservation clear <name>` | Clear an inactive name reservation with confirmation (admin) |
| `/waybridge repair scan` | Scan registered signs in loaded chunks for missing blocks/conflicts (admin) |
| `/waybridge repair list` | Review repair scan results (admin) |
| `/waybridge repair cleanup` | Confirm cleanup of missing registrations (admin) |
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
- Safe-arrival horizontal and vertical search radii
- Teleportation, registration, and failure effects
- WorldGuard build protection
- SignWarpX database path and scan limits
- Rate limits for chunk lookup and sign refresh
- Optional performance diagnostics

Example:

```yaml
teleport:
  safe-arrival:
    horizontal-radius: 5
    vertical-radius: 8
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

## Sign integrity and repair (1.0.5–1.0.6)

WayBridge protects registered signs against unauthorized direct breaking and deliberate removal of blocks supporting them, including applicable piston and explosion events. When a sign is legitimately removed, its registration must also be removed so linked-sign counts, access restrictions, and orphaned-target state remain consistent.

### Supporting blocks, beds, TNT, and gravity

- **Ordinary supporting blocks:** Player-initiated removal of a block that directly supports a registered sign is blocked. Players must remove their registered sign through the normal ownership-checked break path first.
- **Ordinary beds:** The two halves of a bed are treated as a connected structure for protection purposes. Breaking the opposite half or relevant supporting blocks cannot be used to bypass sign removal protections.
- **TNT and straw beds:** New `[WarpTarget]`, `[WPT]`, `[Warp]`, and `[WP]` registrations on these supports are rejected. Straw beds can disappear on use, and TNT can be ignited independently of normal sign breaking; WayBridge does not prevent those intended mechanics.
- **Gravity-affected supports:** Registration on blocks such as sand, gravel, and concrete powder is allowed. WayBridge warns the creator that falling support may destroy the attached sign. The plugin does not cancel normal gravity simply to preserve the registration.

A normal player attempt to break a supporting gravity block is still subject to existing sign protection. The special environmental cleanup applies when the support falls or another observed environmental change removes the sign naturally.

### SignReconciliationService (1.0.6)

WayBridge uses an internal `SignReconciliationService` to inspect and reconcile registered signs. The service distinguishes four physical-registration states: **valid**, **missing**, **conflicting**, and **unavailable**. Its cleanup rules are intentionally conservative:

1. **Observed event:** A relevant falling-block, burning, fading, fluid, or sign-physics event identifies a potentially affected registered sign.
2. **Delayed check:** WayBridge queues an identity-aware check after the world has had an opportunity to apply the block update. Duplicate notifications for the same sign are consolidated.
3. **Loaded-world validation:** The service checks the physical block only when its world and chunk are available. If the chunk unloads, the check is deferred until it loads again.
4. **Identity recheck:** Before deletion, it confirms that the database registration still refers to the original sign. A newer registration at the same coordinates must not be removed by an old callback.
5. **Confirmed cleanup:** When an observed environmental event has genuinely destroyed the sign, WayBridge removes its registration and reconciles related link state. Destroyed WarpTargets retain the established orphaned-link and name-reservation protections.
6. **Database failure:** Transient SQLite cleanup errors are retried with bounded delays (20, 40, and 80 ticks). If retries are exhausted, the registration remains for administrator review using `/waybridge repair scan`.

When a chunk loads and WayBridge discovers an **unobserved** missing sign, it reports the discrepancy instead of automatically deleting it. A physical sign that exists but conflicts with the stored registration is also preserved for inspection rather than overwritten or removed. This reduces the risk of deleting legitimate registrations after ambiguous world changes.

Environmental cleanup messages appear in Paper's normal console and `logs/latest.log`, using the **sign type, warp name, creator name (when resolvable), world name, block coordinates, and destruction reason** rather than only a world UUID. For example:

```text
[WayBridge] Environmentally destroyed WarpTarget sign deregistered: Warp: "Home" | Creator: PlayerName | World: world | Location: -181, 84, 33 | Reason: Falling support
```

### Administrator repair

Third-party plugins, commands, or world editors may alter blocks without firing the normal Paper events. The repair workflow remains the fallback for registrations whose physical signs are unexpectedly missing.

```text
/waybridge repair scan
/waybridge repair list
/waybridge repair cleanup
```

The scan examines **already-loaded chunks only** and differentiates missing blocks from conflicts; it does not load an entire world. Cleanup requires confirmation, rechecks candidates, and creates a database backup. Back up your plugin data and worlds before performing cleanup. Pending automatic reconciliation checks are held in memory and do not survive a server restart; the repair workflow can recover from missed events.

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
- **Version 1.0.5:** Added sign-integrity repair, supporting-block protection, and improved safe-arrival searching and validation.
- **Version 1.0.6:** Adds bed-structure protection, restrictions for TNT/straw-bed supports, gravity warnings and automatic environmental cleanup, a central reconciliation service, informative destruction logs, and bounded SQLite cleanup retries. See GitHub Releases for version-specific changes.

## Project information

- **Author:** iruixos
- **Platform:** Paper
- **Java package:** `com.iruixos.waybridge`
- **Development:** Includes AI-assisted code, tested by a human on a Paper server. :)

## License

WayBridge is licensed under the MIT License. See [LICENSE](LICENSE) for details.
