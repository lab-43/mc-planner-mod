See your [MC Planner](https://www.mc-planner.com/mod?ref=modrinth) plans inside Minecraft. The mod draws your shared world's plan onto the terrain: paths to pave, fields to sow, the next building as see-through boxes or ghost blocks. Walk to what's next, tick it off, and your friends see it in the app at once.

> **What you need**
>
> - Minecraft: Java Edition **26.2** with **Fabric Loader** and **Fabric API**
> - A plan from the [MC Planner app](https://www.mc-planner.com?ref=modrinth): a plan file (`.mcplan`, no account needed), or a shared world you're in (a free Lab43 account)
>
> The mod is free and works the same for everybody. It never checks whether you have an MC Planner plan.

![A planned village over the terrain in Minecraft, with paths, see-through building boxes and a label on each](https://www.mc-planner.com/assets/screens/mod-hero-1280.webp)

## What you see

- **Paths and areas** lie on the terrain in the block they're planned in: a gravel road, a pond of water, a wheat field. The textures come from your own game, resource packs included.
- **Buildings** are see-through boxes with their storeys and a roof, so you know where every wall goes.
- **Schematic buildings** show their blocks as see-through ghost blocks when the schematic file is on your computer. Blocks you've already built disappear, so you see what's left to build.
- **Markers** get a beam, and everything gets a label with its name, kind, status and distance.
- **Filters** by kind and status. Ideas are paler, things in progress pulse, and finished ones are hidden until you switch them on.

![A schematic half built in Minecraft: real blocks below, see-through ghost blocks above showing what's left to build](https://www.mc-planner.com/assets/screens/mod-ghost-1280.webp)

## Find it, build it, tick it off

- Pick something from the list, nearest first. A line, a beam and the HUD's arrow lead you there.
- Set its status (Idea, Planned, In progress, Done) or drop a marker where you stand. Your friends see it in the MC Planner app at once.
- The last copy of each plan stays on your computer, so the overlay works offline too.

![The MC Planner HUD navigating to a planned building, and the line leading to it](https://www.mc-planner.com/assets/screens/mod-hud-1280.webp)

## Get started

1. Install the mod together with Fabric API. In the Modrinth App that's one click. For other launchers, follow the [install guide](https://www.mc-planner.com/academy/mod/install?ref=modrinth).
2. In the game, press **J**, then **Sign in**. Minecraft shows a code: check it on auth.lab43.dev and press **Connect**. The mod never sees your password.
3. Pick your shared world or a plan file. The mod remembers it for each save and server.

| Key or command | What it does |
| --- | --- |
| **J** | The MC Planner menu: account, plan, display, filters, and the objects near you |
| **N** | Shows or hides the plan |
| `/mcplanner` | Opens the menu. Also: `reload`, `toggle`, `list`, `goto <name>`, `stop`, `marker [name]`, `status <idea\|planned\|in_progress\|done>` |
| Mod Menu | **Configure** opens the MC Planner menu (Mod Menu is optional) |

More in the guide: [keys and commands](https://www.mc-planner.com/academy/mod/keys-commands?ref=modrinth), [the overlay](https://www.mc-planner.com/academy/mod/overlay?ref=modrinth), [schematic ghost blocks](https://www.mc-planner.com/academy/mod/schematics?ref=modrinth).

## Compatibility

- **Client-side only.** It works in singleplayer and on any server, and the server needs nothing. It only draws on your screen: it never places or breaks blocks, and other players don't see it.
- **Sodium** works. **Iris shader packs** work too: the plan's fills, lines and labels still show. See [shaders and performance](https://www.mc-planner.com/academy/mod/shaders-performance?ref=modrinth).
- **Minecraft 26.2** for now. Older versions are coming. There's no version for Forge, NeoForge or Bedrock.

## Privacy and network

The mod talks to two servers, and nothing else:

- **auth.lab43.dev** when you sign in: a request for a sign-in code, with a device name like "Minecraft 26.2 · Windows" so you recognise it in your account.
- **app.mc-planner.com** while you're signed in: your sign-in key and the mod and Minecraft version. It gets your account's name, your shared worlds and the plan you picked (every 10 seconds in a world it asks whether anything changed), and it sends the statuses and markers you set.

It reads plan files (in `Documents\MC Planner` and the profile's `mcplanner/plans` folder) and, to find a schematic by its fingerprint, the schematic files in the profile's `schematics` folder, your Downloads, your Desktop and `Documents\MC Planner`. It never uploads them. Your sign-in key is kept in `.lab43` in your user folder, outside the modpack, so sharing a modpack never shares it. No analytics.

The full list: [what the mod sends and reads](https://www.mc-planner.com/academy/mod/privacy?ref=modrinth).

## Questions

**Is the mod free?**
Yes, for everybody, with every feature. It never checks whether you have an MC Planner plan. Plan files open without an account; for shared worlds you need a free Lab43 account and an invite to the world.

**What do MC Planner's paid plans pay for?**
Hosting shared worlds in MC Planner Cloud: the person who shares a world pays for keeping it online for their friends. That's part of the app, not of the mod.

**What happens when a shared world is paused?**
In MC Planner a world pauses when its owner's plan no longer keeps it live. The app and the mod still show it, read-only, until it's live again. Your Minecraft world isn't touched.

**Why don't I see a schematic's blocks?**
The mod shows a schematic's blocks only when the same file is on your computer. Builds belong to their creators, so the plan never carries the file. Get it from the source link in the plan and put it in your Downloads.

More answers: [FAQ](https://www.mc-planner.com/academy/help/faq?ref=modrinth) · [troubleshooting](https://www.mc-planner.com/academy/mod/troubleshooting?ref=modrinth)

## Links

[Website](https://www.mc-planner.com/mod?ref=modrinth) · [Guide](https://www.mc-planner.com/academy/mod?ref=modrinth) · [Source code (AGPL-3.0)](https://github.com/lab-43/mc-planner-mod) · [Report a problem](https://github.com/lab-43/mc-planner-mod/issues)

Made by Lab43. Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
