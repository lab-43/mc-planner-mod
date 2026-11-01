/**
 * Generates the parity fixtures for the mod's Java core from the web app's own plan model
 * (packages/plan/src). Run from the repo root:
 *
 *   node --experimental-strip-types apps/fabric-mod/tools/gen-fixtures.ts
 *
 * Writes into apps/fabric-mod/src/test/resources/fixtures/:
 *   geometry.json            polygonGrid / pathGrid / gridOutline / ringCount / gridBlocks / lengths / areas /
 *                            pointInPolygon results for a set of shapes
 *   objects.json             counts() + describe() for objects of every kind
 *   <plan>.expected.json     parsePlanFile() + counts() + describe() of the plan fixtures (plan-*.json/.mcplan)
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  bounds, counts, describe, formatStacks, gridBlocks, gridOutline, materialName, parsePlanFile, pathGrid,
  pointInPolygon, polygonArea, polygonGrid, polylineLength, rectCorners, ringCount,
  type BlockGrid, type PlanObject, type Pt,
} from "../../../packages/plan/src/index.ts";

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "../src/test/resources/fixtures");

function gridJson(g: BlockGrid | null) {
  return g ? { x0: g.x0, z0: g.z0, w: g.w, d: g.d, count: g.count, cells: Array.from(g.cells).join("") } : null;
}

/** pointInPolygon over the bounding box ± 1.5 in steps of 0.5 (block centres, block edges, outside). */
function pipSamples(pts: Pt[]) {
  const b = bounds(pts);
  let s = "";
  for (let z = b.z0 - 1.5; z <= b.z1 + 1.5; z += 0.5)
    for (let x = b.x0 - 1.5; x <= b.x1 + 1.5; x += 0.5) s += pointInPolygon(x, z, pts) ? "1" : "0";
  return s;
}

const polygons: Record<string, Pt[]> = {
  rect2corner: rectCorners([0, 0], [4, 4]),
  rect4: [[-3, -2], [5, -2], [5, 6], [-3, 6]],
  rectCcw: [[-3, 6], [5, 6], [5, -2], [-3, -2]],
  thinRect: [[0, 0], [12, 0], [12, 1], [0, 1]],
  triangle: [[0, 0], [10, 0], [5, 8]],
  triangleSkew: [[0, 0], [7, 3], [2, 9]],
  triangleNeg: [[-10, -3], [-1, -12], [4, 2]],
  concaveL: [[0, 0], [8, 0], [8, 3], [3, 3], [3, 8], [0, 8]],
  concaveArrow: [[0, 0], [10, 4], [0, 8], [4, 4]],
  concaveU: [[0, 0], [3, 0], [3, 6], [6, 6], [6, 0], [9, 0], [9, 9], [0, 9]],
  star: [[5, 0], [6, 4], [10, 4], [7, 6], [8, 10], [5, 7], [2, 10], [3, 6], [0, 4], [4, 4]],
  bowtie: [[0, 0], [6, 6], [6, 0], [0, 6]],
  lake: [[-60, -60], [-20, -70], [-5, -40], [-25, -30], [-40, -45], [-55, -35]],
  diamond: [[5, 0], [10, 5], [5, 10], [0, 5]],
  onePoint: [[3, -4]],
  twoPoints: [[0, 0], [7, 3]],
  twoPointsNeg: [[-5, 2], [3, -6]],
  twoPointsSame: [[2, 2], [2, 2]],
  collinear: [[0, 0], [4, 2], [8, 4]],
  dupPoints: [[0, 0], [0, 0], [6, 0], [6, 5], [6, 5], [0, 5]],
};

const paths: Record<string, Pt[]> = {
  single: [[3, -4]],
  straightX: [[0, 0], [10, 0]],
  straightZ: [[2, -3], [2, 9]],
  diagonal: [[0, 0], [8, 5]],
  diagonal45: [[0, 0], [7, 7]],
  bend: [[0, 0], [10, 0], [10, 8]],
  zigzag: [[0, 0], [8, 2], [0, 4]],
  hairpin: [[0, 0], [10, 0], [0, 1]],
  long: [[0, 0], [40, 0], [40, 25], [72, 41]],
  dup: [[0, 0], [0, 0], [5, 5]],
  negative: [[-20, -7], [-3, -15], [6, 4]],
};
const widths = [1, 2, 3, 4, 5, 1.5, 2.5];

const geometry = {
  polygons: Object.entries(polygons).map(([name, pts]) => {
    const g = polygonGrid(pts);
    return {
      name, points: pts, grid: gridJson(g), ring: g ? ringCount(g) : null, outline: g ? gridOutline(g) : null,
      blocks7: gridBlocks(g, 7), area: polygonArea(pts), length: polylineLength(pts),
      pip: pts.length >= 3 ? pipSamples(pts) : null,
    };
  }),
  paths: Object.entries(paths).flatMap(([name, pts]) => widths.map((width) => {
    const g = pathGrid(pts, width);
    return { name: `${name}/w${width}`, points: pts, width, grid: gridJson(g), ring: g ? ringCount(g) : null, outline: g ? gridOutline(g) : null, length: polylineLength(pts) };
  })),
  tooLarge: {
    polygon: polygonGrid([[0, 0], [5000, 0], [5000, 5000], [0, 5000]]) === null,
    path: pathGrid([[0, 0], [4100, 4100]], 3) === null,
  },
  hypot: [[3, 4], [1, 1], [7, 3], [-5, 12], [1e-3, 2], [123456, 654321], [0, 0], [17, 0], [0.1, 0.2]].map(([a, b]) => ({ a, b, h: Math.hypot(a, b) })),
  stacks: [0, 1, 63, 64, 65, 128, 870, 4096, 4097].map((n) => ({ n, s: formatStacks(n) })),
  materialNames: ["minecraft:dirt_path", "minecraft:gravel", "create:andesite_casing", "minecraft:block/oak_log", "stone_bricks", "mod:a__b", "minecraft:_x_"].map((id) => ({ id, name: materialName(id) })),
};
writeFileSync(join(out, "geometry.json"), JSON.stringify(geometry));

// ---------- counts + describe for objects of every kind ----------

let n = 0;
function obj(kind: PlanObject["kind"], points: Pt[], extra: Partial<PlanObject> = {}): PlanObject {
  n++;
  return {
    id: `obj${n}`, kind, name: `${kind} ${n}`, color: "#112233", status: "planned", dimension: "minecraft:overworld",
    points, y: 64, width: kind === "path" ? 3 : 0, height: kind === "building" ? 8 : 0, notes: "", author: "",
    createdAt: 1, updatedAt: 1, ...extra,
  };
}
const schem = { hash: "a".repeat(64), file: "x.schem", format: "sponge" as const, size: [12, 9, 30] as [number, number, number], turns: 0, mirror: false, blocks: 1234567 };
const objects: PlanObject[] = [
  obj("marker", [[10, -20]], { y: 70 }),
  obj("marker", [], {}),
  obj("marker", [[-5, 5], [6, 6]], { y: -12 }),
  ...Object.values(paths).flatMap((pts) => [1, 2, 3, 5].map((w) => obj("path", pts, { width: w, material: w === 3 ? "minecraft:gravel" : undefined }))),
  obj("path", [[0, 0], [4100, 4100]], { width: 3 }),
  obj("path", [[0, 0], [10, 0]], { width: 0 }),
  ...Object.values(polygons).map((pts) => obj("area", pts, { material: "minecraft:grass_block" })),
  obj("area", [[0, 0], [5000, 0], [5000, 5000], [0, 5000]]),
  obj("area", [[0, 0], [5000, 0], [5000, 5000], [0, 5000]], { material: "create:andesite_casing" }),
  ...Object.values(polygons).map((pts) => obj("building", pts, { height: 13, storey: 4, material: "minecraft:stone_bricks" })),
  obj("building", [[0, 0], [11, 8]], { height: 8 }),
  obj("building", [[11, 8], [0, 0]], { height: 1, storey: 0 }),
  obj("building", [[0, 0], [11, 0], [11, 8], [0, 8]], { height: 30, storey: 5 }),
  obj("building", [[0, 0], [6000, 0], [6000, 6000], [0, 6000]], { height: 20 }),
  obj("building", rectCorners([0, 0], [29, 11]), { schematic: { ...schem, turns: 1 } }),
  obj("building", rectCorners([0, 0], [11, 29]), { schematic: { ...schem, turns: 2 } }),
  obj("building", [[0, 0], [1, 1]], { height: 0 }),
];
writeFileSync(join(out, "objects.json"), JSON.stringify(objects.map((o) => ({ object: o, counts: counts(o), describe: describe(o) }))));

// ---------- plan files ----------

for (const f of ["plan-sample.mcplan", "plan-legacy.json", "plan-messy.json"]) {
  const parsed = parsePlanFile(JSON.parse(readFileSync(join(out, f), "utf8")));
  const expected = {
    world: parsed.world,
    settings: { home: parsed.settings.home ?? null, kindColors: parsed.settings.kindColors ?? null },
    objects: parsed.objects.map((o) => ({ object: o, counts: counts(o), describe: describe(o) })),
  };
  writeFileSync(join(out, f.replace(/\.(mcplan|json)$/, ".expected.json")), JSON.stringify(expected, null, 1));
}
console.log(`fixtures written to ${out}`);
