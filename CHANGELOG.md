# Changelog — MC Planner mod

What players read on Modrinth and on the GitHub release page. One line per change, in plain words.
New changes go under "Unreleased"; `pnpm release:mod <version>` turns it into the version's section (docs/RELEASES.md).

## Unreleased

## 0.3.0 — 2026-11-11

- **Free for everybody:** the mod no longer checks for an MC Planner plan. Plan files open without an account, and shared worlds show for everyone in them. Paused worlds show read-only instead of nothing. (Mojang's rules for mods don't allow a mod to check for a paid plan.)
- Offline, the last saved copy of a shared world shows however old it is.
- **Crash report:** when Minecraft crashed with the mod in the crash, or the mod hit an error, the menu (J) offers Crash report. It shows the whole report first, without your name, worlds or servers, and sends it to Lab43 only if you press Send.

## 0.2.0 — 2026-10-02

- First public release: see your MC Planner plans inside Minecraft — paths, areas, buildings and markers on the terrain, each with a label.
- Objects with a block set in the planner show that block's texture: gravel roads, ponds of water, wheat fields, stone brick walls.
- Schematic buildings show as see-through ghost blocks when the schematic file is on your computer. Blocks you've already built disappear.
- Navigate to any object, see what's next in the HUD, set statuses and drop markers in-game — your friends see it in the app at once.
- Works with Sodium and Iris shader packs, on any server (client-side only).
- Needs an active MC Planner plan (Cloud, Team, Lifetime or the 7-day trial) — yours, or the one of the friend whose shared world you're in. Without one, the menu shows what's missing and offers the free trial; plan files stay open offline for 72 hours after the last check.
- The menu has a Help button that opens the guide on mc-planner.com.
