// node --test tools/release-files.test.mjs — the release files of the mod (manifest, notes, channels).
import { test } from "node:test";
import assert from "node:assert/strict";
import { buildManifest, changelogNotes, channel, isVersion, parseProperties, releaseBody } from "./release-files.mjs";

const CHANGELOG = `# Changelog

## Unreleased

## 0.3.0 — 2026-11-01

- Newer thing.

## 0.2.0 — 2026-10-02

- First public release.
- Ghost blocks.

## 0.1.0 — 2026-09-30

- Old.
`;

test("gradle.properties", () => {
  const p = parseProperties("# comment\r\nmod_version=0.2.0\nminecraft_version = 26.2\n\nfabric_api_version=0.160.0+26.2\n");
  assert.equal(p.mod_version, "0.2.0");
  assert.equal(p.minecraft_version, "26.2");
  assert.equal(p.fabric_api_version, "0.160.0+26.2");
});

test("notes of one version, not the next one's", () => {
  assert.equal(changelogNotes("0.2.0", CHANGELOG), "- First public release.\n- Ghost blocks.");
  assert.equal(changelogNotes("0.3.0", CHANGELOG), "- Newer thing.");
  assert.equal(changelogNotes("0.2", CHANGELOG), null, "0.2 isn't 0.2.0");
  assert.equal(changelogNotes("9.9.9", CHANGELOG), null);
  assert.equal(changelogNotes("0.2.0", CHANGELOG.replace(/\n/g, "\r\n")), "- First public release.\n- Ghost blocks.");
});

test("versions and channels", () => {
  assert.ok(isVersion("0.2.0") && isVersion("1.0.0-beta.2"));
  assert.ok(!isVersion("v0.2.0") && !isVersion("0.2"));
  assert.equal(channel("0.2.0"), "beta");
  assert.equal(channel("1.0.0"), "release");
  assert.equal(channel("1.1.0-beta.1"), "beta");
  assert.equal(channel("1.1.0-alpha.1"), "alpha");
});

test("manifest the website reads", () => {
  const props = { mod_version: "0.2.0", minecraft_version: "26.2", loader_version: "0.19.5", fabric_api_version: "0.160.0+26.2" };
  const jar = { name: "mcplanner-0.2.0+mc26.2.jar", size: 260000, sha1: "a".repeat(40), sha512: "b".repeat(128) };
  const m = buildManifest({ props, tag: "v0.2.0", repo: "lab-43/mc-planner-mod", jar, notes: "- First.", modrinth: "https://modrinth.com/mod/mc-planner", date: new Date("2026-10-02T10:00:00Z") });
  assert.equal(m.version, "0.2.0");
  assert.deepEqual(m.minecraft, ["26.2"]);
  assert.equal(m.requires.fabricApi, ">=0.160.0");
  assert.equal(m.file.url, "https://github.com/lab-43/mc-planner-mod/releases/download/v0.2.0/mcplanner-0.2.0%2Bmc26.2.jar");
  assert.equal(m.page, "https://github.com/lab-43/mc-planner-mod/releases/tag/v0.2.0");
  assert.equal(m.date, "2026-10-02T10:00:00.000Z");
  const body = releaseBody(m);
  assert.match(body, /^- First\./);
  assert.match(body, /active MC Planner plan/, "the release text says a plan is needed");
  assert.match(body, /Not an official Minecraft product/);
  assert.match(body, /modrinth\.com\/mod\/mc-planner/);
  assert.equal(buildManifest({ props, tag: "v0.2.0", repo: "x/y", jar, notes: "n" }).modrinth, undefined, "no Modrinth link until there is one");
});
