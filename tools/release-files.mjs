#!/usr/bin/env node
/**
 * The files of a mod release, made by the release build after `./gradlew build` (public repo, .github/workflows):
 *
 *   node tools/release-files.mjs v0.2.0     → build/release/mod.json  (what the website reads: version, Minecraft
 *                                              versions, file + checksums, notes)
 *                                             build/release/notes.md   (the GitHub release text)
 *                                             prints the jar's path
 *
 * The version comes from gradle.properties and must match the tag; the notes from CHANGELOG.md ("## 0.2.0 — date").
 * Plain Node, no packages: it runs in the public mod repo, where nothing of the monorepo exists.
 */
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/** gradle.properties → { key: value } */
export function parseProperties(text) {
  const out = {};
  for (const raw of text.replace(/\r\n/g, "\n").split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#") || line.startsWith("!")) continue;
    const m = line.match(/^([^=:\s]+)\s*[=:]\s*(.*)$/);
    if (m) out[m[1]] = m[2].trim();
  }
  return out;
}

/** the lines of "## <version> …" up to the next "## " heading, trimmed; null if there's no such section */
export function changelogNotes(version, text) {
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const esc = version.replace(/[.+]/g, "\\$&");
  const start = lines.findIndex((l) => new RegExp(`^##\\s+v?${esc}(\\s|$)`).test(l));
  if (start < 0) return null;
  let end = lines.findIndex((l, i) => i > start && /^##\s/.test(l));
  if (end < 0) end = lines.length;
  const notes = lines.slice(start + 1, end).join("\n").trim();
  return notes || null;
}

/** "1.2.3" (optionally "-beta.1") — the versions a mod release may have */
export const isVersion = (v) => /^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$/.test(String(v));

/** release channel for Modrinth: alpha / beta / release, from a "-alpha…" / "-beta…" suffix or 0.x versions */
export function channel(version) {
  if (/-alpha/i.test(version)) return "alpha";
  if (/-(beta|rc)/i.test(version) || version.startsWith("0.")) return "beta";
  return "release";
}

/** the manifest the website reads (its /mod page, from releases/latest/download/mod.json) */
export function buildManifest({ props, tag, repo, jar, notes, modrinth = null, date = new Date() }) {
  const version = props.mod_version;
  const mc = props.minecraft_version;
  return {
    version,
    tag,
    channel: channel(version),
    date: date.toISOString(),
    minecraft: [mc],
    loader: "fabric",
    requires: {
      fabricLoader: `>=${props.loader_version}`,
      fabricApi: `>=${String(props.fabric_api_version).split("+")[0]}`,
      java: 25,
    },
    file: {
      name: jar.name,
      url: `https://github.com/${repo}/releases/download/${tag}/${encodeURIComponent(jar.name)}`,
      size: jar.size,
      sha1: jar.sha1,
      sha512: jar.sha512,
    },
    notes,
    page: `https://github.com/${repo}/releases/tag/${tag}`,
    ...(modrinth ? { modrinth } : {}),
  };
}

/** the GitHub release text: the notes + what you need + checksums */
export function releaseBody(m) {
  return [
    m.notes,
    "",
    "**What you need:** Minecraft: Java Edition " + m.minecraft.join(", ") + " with Fabric Loader and Fabric API, and a plan from MC Planner: a plan file (no account needed) or a shared world you're in (a free Lab43 account). The mod is free for everybody and checks no MC Planner plan.",
    "",
    ...(m.modrinth ? [`Get it on Modrinth: ${m.modrinth}`, ""] : []),
    `SHA-512 \`${m.file.sha512}\``,
    "",
    "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
  ].join("\n");
}

function main(tag) {
  const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
  const die = (msg) => { console.error(`release-files: ${msg}`); process.exit(1); };
  if (!tag || !/^v/.test(tag)) die("usage: node tools/release-files.mjs v<version>");
  const props = parseProperties(readFileSync(join(ROOT, "gradle.properties"), "utf8"));
  const version = props.mod_version;
  if (!isVersion(version)) die(`mod_version "${version}" in gradle.properties isn't a version`);
  if (tag !== `v${version}`) die(`the tag ${tag} doesn't match mod_version ${version} in gradle.properties`);
  const notes = changelogNotes(version, readFileSync(join(ROOT, "CHANGELOG.md"), "utf8"));
  if (!notes) die(`CHANGELOG.md has no "## ${version}" section (run pnpm release:mod in the monorepo)`);
  const name = `${props.archives_base_name}-${version}+mc${props.minecraft_version}.jar`;
  const path = join(ROOT, "build", "libs", name);
  if (!existsSync(path)) die(`${path} is missing — did ./gradlew build run?`);
  const bytes = readFileSync(path);
  const jar = {
    name,
    size: statSync(path).size,
    sha1: createHash("sha1").update(bytes).digest("hex"),
    sha512: createHash("sha512").update(bytes).digest("hex"),
  };
  const repo = process.env.GITHUB_REPOSITORY || "lab-43/mc-planner-mod";
  const slug = process.env.MODRINTH_SLUG;
  const m = buildManifest({ props, tag, repo, jar, notes, modrinth: slug ? `https://modrinth.com/mod/${slug}` : null });
  const out = join(ROOT, "build", "release");
  mkdirSync(out, { recursive: true });
  writeFileSync(join(out, "mod.json"), JSON.stringify(m, null, 2) + "\n");
  writeFileSync(join(out, "notes.md"), releaseBody(m) + "\n");
  console.log(path);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main(process.argv[2]);
