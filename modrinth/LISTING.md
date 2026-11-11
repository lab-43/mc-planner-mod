# Modrinth listing: MC Planner

Everything the Modrinth project page needs, ready to paste. The page text is [`description.md`](description.md), and the
icon is [`icon.png`](icon.png) (512 × 512, 22 KB; Modrinth allows PNG/JPEG up to 256 KiB). The gallery images are the
website's in-game shots (`apps/marketing/public/assets/screens/mod-*.webp`), saved as 1920 × 1080 JPEGs. Make them again
with the snippet at the end (it runs in the MC Planner monorepo). Plan and background: `docs/PLAN-mod-launch.md` → "3. Modrinth".

## 1 · Create the project

modrinth.com → **+** (top right) → **Create a project**

| Field | Value |
|---|---|
| Project type | Mod |
| Name | `MC Planner` |
| URL | `mc-planner` (if it's taken: `mc-planner-mod`, then tell Claude, because the release setup asks for it) |
| Visibility | Public (it stays a draft until the review approves it) |
| Summary | `Your MC Planner plan, drawn into Minecraft: paths, areas, buildings and schematic ghost blocks with real block textures.` |

The summary repeats neither the name nor any formatting (Modrinth rules, section 5).

## 2 · Settings

| Where | Value |
|---|---|
| General → Icon | `icon.png` |
| General → Environment | Client-side only. It works in singleplayer and on any server, and the server needs nothing |
| Description | Paste all of `description.md`. Check the preview: the three images load from www.mc-planner.com. If one doesn't show, replace its address with the gallery image's (open the gallery image → right-click → Copy image address) |
| Tags | **Utility**, **Social** (both featured) |
| License | `AGPL-3.0-only` (GNU Affero General Public License v3.0 only). License URL: `https://github.com/lab-43/mc-planner-mod/blob/main/LICENSE` |
| Links → Issues | `https://github.com/lab-43/mc-planner-mod/issues` |
| Links → Source | `https://github.com/lab-43/mc-planner-mod` |
| Links → Wiki | `https://www.mc-planner.com/academy/mod?ref=modrinth` |
| Links → Discord | leave empty (no server yet) |
| Links → Donation | Ko-fi: `https://ko-fi.com/lab43` |

## 3 · Gallery

Upload in this order. Every image needs a title (Modrinth rules, section 5). Set **01** as the featured image.

| File | Title | Description |
|---|---|---|
| `01-mod-hero.jpg` (featured) | Your plan in the world | A planned village drawn over the terrain: paths, see-through building boxes and a label on everything. |
| `02-mod-paths.jpg` | Paths and areas in their block | Planned paths and areas lie on the terrain, in the block they're planned in or in their colour. |
| `03-mod-ghost.jpg` | Schematics as ghost blocks | Real blocks below, ghost blocks above. What you've built disappears, so you see what's left to build. |
| `04-mod-boxes.jpg` | Buildings as boxes | See-through boxes with their storeys, each labelled with its name, status and distance. |
| `05-mod-hud.jpg` | Find what's next | The HUD's arrow and a line lead you to the object you picked. |
| `06-mod-menu.jpg` | The menu (J) | Your plan, overlay switches, filters and the objects nearest first. Set a status in one click. |
| `07-mod-shaders.jpg` | With shader packs | With Iris and a shader pack, the plan's fills, lines and labels still show. |
| `08-mod-signin.jpg` | Sign in with a code | The mod never sees your password. Check the code on auth.lab43.dev and press Connect. |

If you know which shader pack is in `07`, add it to that description, e.g. "(Complementary Reimagined)".

## 4 · Disclosures

These are mandatory since 27 Sept 2026 (Modrinth rules, sections 5.9 and 6). Settings → Disclosures:

| Disclosure | Answer |
|---|---|
| **Contains AI-generated content** | **Yes: Code.** Also tick **Text** while the description is the one Claude wrote (section 6.1.4: the page relies on generative AI). Note: *"The mod's code was written with an AI coding assistant (Claude) under our direction. We designed the features, tested every version in our own modpack and took the screenshots ourselves."* (adjust this so it says exactly what you did) |
| **Contains advertising** | No |
| **Contains paid features** | **No.** The mod is free with every feature and never checks for a plan (since 0.3.0). MC Planner's paid plans pay for hosting shared worlds in the app, which the mod doesn't need. |
| **Contains telemetry** | **Yes, Always active.** Data: *"The mod and Minecraft version, sent with each request to MC Planner's server (app.mc-planner.com), and a device name like 'Minecraft 26.2 · Windows' saved with your Lab43 account when you sign in. No analytics, no usage tracking."* |
| **Contains derivative content** | No |
| **Photosensitivity warning** | No (objects in progress pulse slowly; nothing flashes) |
| **Contains external system interactions** | **Yes.** Note: *"Reads .mcplan plan files in Documents\MC Planner and in the profile's mcplanner/plans folder. To show a schematic building, it reads schematic files (.litematic, .schem, .nbt, .schematic) in the profile's schematics folder, Downloads, Desktop and Documents\MC Planner (also OneDrive's Desktop and Documents on Windows) to find the one whose fingerprint matches; it never uploads or changes them. Saves its sign-in key in the folder .lab43 in the user's home folder, outside the Minecraft folder. Opens auth.lab43.dev in the browser when you press Sign in, and pages on www.mc-planner.com when you press Help."* |

Once a version with crash reports is out (0.2.1 or later, "Unreleased" in CHANGELOG.md), add to telemetry: *"Crash
reports, opt-in: sent to auth.lab43.dev only when you press Send to Lab43 on the Crash report screen."*

## 5 · The first version (0.2.0)

Modrinth needs a version before you can submit. Upload it through the release pipeline, so every later release does
the same thing:

1. modrinth.com/settings/pats → **Create a personal access token**. Scopes: *Read projects*, *Read versions*,
   *Create versions*. Expiry: 1 year.
2. Double-click `tools\release-mod-setup.cmd` → answer **y** at "4. Modrinth" → paste the token and the slug
   (`mc-planner`). It saves `MODRINTH_ID`, `MODRINTH_SLUG` and the secret `MODRINTH_TOKEN` in the public repo.
3. github.com/lab-43/mc-planner-mod → **Actions** → **Publish** → **Run workflow** → tag `v0.2.0`. It uploads the jar
   from the GitHub release as version `0.2.0+mc26.2`, channel **Beta**, Fabric, Minecraft 26.2, Fabric API required,
   Mod Menu optional, with the notes from the release.

By hand instead: Versions → **Create version** → the jar from https://github.com/lab-43/mc-planner-mod/releases/tag/v0.2.0,
version number `0.2.0+mc26.2`, name `MC Planner 0.2.0`, channel Beta, loader Fabric, game version 26.2, dependencies
Fabric API (required) and Mod Menu (optional), changelog = the "0.2.0" section of `CHANGELOG.md`.

## 6 · Submit for review

**Submit for review**, with this message to the moderators. Fill in the invite link first (see below):

> MC Planner is a client-side Fabric mod for our planning app MC Planner (www.mc-planner.com). It draws a plan the
> player made in the app (paths, areas, buildings, markers) as an overlay in their world.
>
> **Why it uses the network:** it is a client for MC Planner's shared worlds. It signs in with a device code on
> auth.lab43.dev (our account site; the mod never sees the password) and reads the plan from app.mc-planner.com. While
> the player is in a world it asks every 10 seconds whether the plan changed. It sends only the sign-in key, the mod and
> Minecraft version, and the statuses and markers the player sets in-game. No analytics.
>
> **Why it reads files outside the game:** plan files the desktop app saves in Documents\MC Planner, and schematic files
> (Downloads, Desktop, the profile's schematics folder) that it matches by SHA-256 to show a planned schematic as ghost
> blocks. It never uploads or changes them. The sign-in key is kept in ~/.lab43, so it isn't part of a shared modpack.
>
> **Paid features:** none. The mod is free with every feature and never checks whether a player has an MC Planner
> plan (Mojang's usage guidelines don't allow a mod to check for out-of-game products or services). Plan files open
> without an account; shared worlds need a free account and an invite.
>
> **Source:** https://github.com/lab-43/mc-planner-mod (AGPL-3.0). The jar is built there by GitHub Actions from that
> code (Release workflow, tag v0.3.0).
>
> **To try it:** make a free account at https://auth.lab43.dev with an email address you can read (sign-in is by a 6-digit code), then open this invite to our demo
> world: `<INVITE LINK>`. In Minecraft 26.2 with Fabric API, press J → Sign in, then pick `<WORLD NAME>`. You don't
> need to buy anything.
>
> **AI use:** the code was written with an AI coding assistant under our direction; we designed and tested it and made
> the screenshots ourselves. We disclosed it in the AI disclosure.

**The invite link:** in the MC Planner app, share a demo world (not your real one, and nothing with real names in it)
from your account (sharing a world needs a plan in the app; the mod itself doesn't). Make an invite with role *viewer*,
valid for 30 days, then paste it in place of `<INVITE LINK>` and the world's name in place of `<WORLD NAME>`.

Review usually takes 24 to 48 hours, and up to two weeks around holidays. Network access and file reading can send a
mod to manual review. That's expected.

### Sending it again after a rejection (0.3.0)

The first submission (0.2.0) needed a paid MC Planner plan, which Mojang's usage guidelines for mods don't allow
(*"The mod cannot be used to directly or indirectly verify whether a player owns or has access to out-of-game content,
products, or services that affect in-game features and functions"*). From 0.3.0 the mod checks no plan. Before you
resubmit: release 0.3.0 (`pnpm release:mod 0.3.0`, then `pnpm release:mod publish`, docs/RELEASES.md), upload it to the
project (github.com/lab-43/mc-planner-mod → Actions → Publish → Run workflow → tag `v0.3.0`, as in step 5), delete
version 0.2.0 from the project if it's there, update the summary, the description (paste `description.md` again) and the
**Contains paid features** disclosure as above, then reply to the moderators' message with:

> Thanks for the review. Version 0.3.0 removes the paid-plan check completely: the mod is free with every feature for
> everybody and never asks whether a player has an MC Planner plan. Plan files open without an account; shared worlds
> need a free Lab43 account and an invite from the world's owner. The description and the disclosures say so, and
> "Contains paid features" is now No. Source of 0.3.0: https://github.com/lab-43/mc-planner-mod/releases/tag/v0.3.0.
> The test steps above still work, without a purchase.

If the moderators named something else, answer that too; paste their message to Claude first.

## 7 · After approval

Tell Claude the final URL. Then (HQ task "Launch wiring: Modrinth link, in-app links, remove `waitsFor`"):
`content.links.modrinth` in `apps/marketing/content.ts` is set, so the website's buttons become "Get it on Modrinth". The
Academy's "Coming to Modrinth soon" texts and the install guide go back to Modrinth first, and the app links to it.

## The gallery images again

```bash
python3 - <<'EOF'
from PIL import Image
src = "apps/marketing/public/assets/screens"
for i, n in enumerate(["mod-hero", "mod-paths", "mod-ghost", "mod-boxes", "mod-hud", "mod-menu", "mod-shaders", "mod-signin"], 1):
    Image.open(f"{src}/{n}.webp").convert("RGB").save(f"{i:02d}-{n}.jpg", "JPEG", quality=92, optimize=True, progressive=True)
EOF
```
