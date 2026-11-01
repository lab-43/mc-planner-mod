# MC Planner for Minecraft (Fabric mod)

See your [MC Planner](https://www.mc-planner.com) plans **inside Minecraft**: paths, areas, buildings and markers
from app.mc-planner.com, or from a `.mcplan` file, drawn as an overlay in the world. Sign in with your Lab43 account,
walk to what's next, and mark it done. The mod runs on the client only and works on any server (the server needs
nothing).

**The mod needs an active MC Planner plan** (Cloud, Team, Lifetime, or the 7-day free trial). In a shared world it's
the plan of the world's owner that counts, so friends you invite play for free. Without a plan the mod shows how to
start the trial and draws nothing. More: [mc-planner.com/mod](https://www.mc-planner.com/mod) ·
[how the mod works](https://www.mc-planner.com/academy/mod).

| | |
|---|---|
| Minecraft | **26.2** (this build). Other versions come later |
| Loader | Fabric Loader ≥ 0.19, **Fabric API** ≥ 0.160 (required) |
| Optional | Mod Menu (adds a "Configure" button that opens the MC Planner menu) |
| Java | 25 (Minecraft 26.x requirement) |
| Licence | [AGPL-3.0-only](LICENSE) |

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.

### About this repository

This is the source of the mod as released: every release copies the folder `apps/fabric-mod` of the MC Planner
monorepo here as one commit, tags it (`v0.2.0`, …) and builds the release from this repository's code
(`.github/workflows/release.yml` → the jar on the Releases page and on Modrinth). Bug reports and ideas are welcome in
[Issues](https://github.com/lab-43/mc-planner-mod/issues). Pull requests are read too, but changes are made in the
monorepo and arrive here with the next release, so a merged PR shows up as part of a release commit.

## Install

Put `mcplanner-<version>+mc26.2.jar` into your instance's `mods` folder, next to Fabric API. In the Modrinth app:
Profile → ⋯ → Open folder → `mods`.

## Use

| | |
|---|---|
| **J** | MC Planner menu: account, which plan, overlay switches, filters, and the objects near you (click one for details) |
| **N** | Show / hide the plan |
| *(unbound)* | "Add a marker here", "Navigate to the nearest unfinished object". Set them in Options → Controls → MC Planner |
| `/mcplanner` | Opens the menu. Subcommands: `reload` · `toggle` · `list` (clickable) · `goto <name>` · `stop` · `marker [name]` · `status <idea\|planned\|in_progress\|done>` (sets the status of the object you're standing in) |

1. **Sign in** (menu → Sign in). Minecraft shows a code and opens `auth.lab43.dev/device`. Sign in there with your
   Lab43 account (the same one as for MC Planner), check the code and press **Connect**. The mod never sees your
   password. It gets its own token, which you can disconnect at any time on auth.lab43.dev/account.
2. **Choose a plan**: one of your shared worlds, or a `.mcplan` file. The mod finds files in `Documents\MC Planner`
   (where the desktop app saves with Ctrl+S) and in `<instance>/mcplanner/plans`. You can also drop a file onto the
   window. The choice is remembered per save and per server. A shared world with the same name as your save is
   picked automatically.
3. **In the world**:

   | Kind | Shown as |
   |---|---|
   | Paths | Tiles on every block to pave, with an outline and a centre line, following the terrain |
   | Areas | An outline along the block edges that follows the terrain, with posts at the corners, and a fill |
   | Buildings | See-through boxes with storey lines and a roof |
   | Markers | A block outline with a beam |

   An object with a block set in the app (a gravel path, a pond of water, a wheat field, stone brick walls) shows that
   block's texture on its tiles, fill, walls and roof, from your own game's textures (resource packs and modded blocks
   included). Without a block it's drawn in its colour. **Display → Block textures** switches this off.

   **Schematic buildings** show their blocks as see-through ghost blocks, like in the app, when the schematic file is
   on this computer. The shared plan never carries the file (builds belong to their creators), so the mod looks for it
   by its fingerprint in the instance's `schematics` folder, Downloads, Desktop and `Documents\MC Planner` — it only
   reads them, nothing is uploaded. Blocks you've already built disappear, so what's left is what's still to build.
   Without the file you see the box; the object's screen (J → the building) says where to put it.

   Every object gets a label: name, kind, status and distance. **Ideas** are paler, **in progress** pulses, and
   **done** is hidden unless you switch it on.
4. **HUD** (top left by default): the plan's name and whether it's live, the navigation target with an arrow and
   distance, and the object you're standing in.
5. **Changes** in shared worlds where you're an editor (not a viewer, and not a paused world):
   - set the status of objects (menu → object → Idea / Planned / In progress / Done, or `/mcplanner status done`);
   - add markers where you stand.

   Friends in the app see the change at once. The mod fetches the plan every 10 s (only what changed, using an
   ETag). Plan files reload by themselves when the app saves them again.

**Which plan counts.** Shared worlds show while their **owner** has an active MC Planner plan; friends they invited
need none of their own. A paused world (the owner's plan ended) is greyed out in the list and nothing of it is drawn;
it comes back by itself when the owner renews. **Plan files** need your **own** plan (Cloud, Team, Lifetime or the
7-day trial). The mod asks the server at sign-in, every 30 minutes and when you open a file, and trusts the last answer
for 72 hours offline, never past the plan's end date. Without a plan, J → **What now?** offers the free trial, the
plans and **Check again** (while a file waits, the mod also checks every minute). **Help** at the top of the menu opens
the guide on mc-planner.com.

Settings are in `config/mcplanner.json` (Display… in the menu). The sign-in token is stored **outside** the instance,
in `~/.lab43/mcplanner-minecraft.json` (the last plan check next to it, `mcplanner-access.json`), so exporting or
sharing a modpack never includes them. The last copy of each plan
is kept in `<instance>/mcplanner/cache/`, so the overlay also works offline.

## Build

```bash
./gradlew build          # needs JDK 25; jar in build/libs, runs coreTest + meshTest
./gradlew runClient      # Minecraft with the mod, for development
```

Releases are made from the MC Planner monorepo (`pnpm release:mod`), built by `.github/workflows/release.yml` in this
repository and published to Modrinth by `publish.yml`. `tools/make-icon.py` draws the icon (128 px for the jar,
`modrinth/icon.png` at 512 px).

Without access to Fabric's maven (e.g. in a sandbox), `tools/build-jar.sh` compiles against the jars of an existing
26.2 install. Minecraft 26.x isn't obfuscated and the mod has no mixins, so this jar is identical in function.
`tools/test-core.sh` (core, Java 17 compatible) and `tools/test-mc.sh` (overlay geometry) run the tests without
Gradle.

## Code

| Path | What |
|---|---|
| `core/` | Version-independent, plain Java 17 + Gson. The plan model and parser (a port of the web app's TypeScript plan model; the parity fixtures in `src/test/resources/fixtures` come from it — `tools/gen-fixtures.ts` only runs inside the monorepo), the hub device sign-in (`HubClient`, `DeviceLogin`), the mod API client (`PlannerClient`), polling (`PlanSync`, 402 = paused world), the plan check for plan files (`Access`, `AccessGate`: offline grace), config, token storage, local files, cache. Will be reused unchanged for older Minecraft versions |
| `mc/` | Minecraft 26.x. `McPlannerClient` (entrypoint, keys, events), `Planner` (state: account, source, plan, navigation, edits), `Hud`, `Commands`, `render/` (`Shape` → `Mesh` → gizmos + fills; `Materials` = block textures), `gui/` (screens), `compat/ModMenuEntry` |

Drawing goes through Minecraft's own render API, no raw OpenGL, so the mod also works with 26.2's experimental Vulkan
backend:
- **Lines and label text:** gizmos (`net.minecraft.gizmos`, vanilla's debug drawing).
- **Fills** (tiles, area fill, walls, roofs): custom geometry with the banner-pattern render type (translucent, entity
  vertex layout, no depth write), textured from the block atlas or a white texture. Not gizmo quads: shader packs
  (Iris) have no program for vanilla's `debug_filled_box` and drop them. Walls and roofs are written from both sides
  (that render type culls back faces), and fills never go below alpha 31/255 (Iris discards alpha ≤ 0.1).
- **Label backdrops:** the text-background render types, like name tags.

Work is split into two steps:
- **End of level extraction:** terrain heights are read and the meshes are built, under a per-frame budget.
- **`BEFORE_GIZMOS`:** the gizmos and the custom geometry are handed over in the same frame.

Meshes are culled per 16×16 cell by distance and frustum; long lines and big quads are split at the cell borders.
