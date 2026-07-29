import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const [baseCheckout, headCheckout, baseSha, headSha] = process.argv.slice(2);
if (!baseCheckout || !headCheckout || !baseSha || !headSha) {
  throw new Error("usage: compare.mjs <base-checkout> <head-checkout> <base-sha> <head-sha>");
}
const proofScript = path.join(path.dirname(fileURLToPath(import.meta.url)), "proof.mts");
const proofHarnessSha256 = crypto
  .createHash("sha256")
  .update(fs.readFileSync(proofScript))
  .digest("hex");
const samples = { base: [], head: [] };

function run(label, checkout, sha) {
  const child = spawnSync(
    process.execPath,
    ["--expose-gc", "--import", "tsx", proofScript, checkout, sha],
    {
      cwd: checkout,
      encoding: "utf8",
      env: { ...process.env, PRODUCTION_ENTRYPOINT: "codex.cli.sessions.list" },
      timeout: 60_000,
    },
  );
  if (child.status !== 0) {
    throw new Error(`${label} proof child failed: ${child.stderr || child.stdout}`);
  }
  const parsed = JSON.parse(child.stdout);
  samples[label].push(parsed);
}

for (let index = 0; index < 8; index += 1) {
  const order =
    index % 2 === 0
      ? [
          ["base", baseCheckout, baseSha],
          ["head", headCheckout, headSha],
        ]
      : [
          ["head", headCheckout, headSha],
          ["base", baseCheckout, baseSha],
        ];
  for (const [label, checkout, sha] of order) {
    run(label, checkout, sha);
  }
}

const retained = {
  base: samples.base.slice(1),
  head: samples.head.slice(1),
};
const all = [...retained.base, ...retained.head];
const inventoryDigests = new Set(all.map((sample) => sample.inventory.digest));
const resultDigests = new Set(all.map((sample) => sample.result.digest));
if (inventoryDigests.size !== 1 || resultDigests.size !== 1) {
  throw new Error("real Codex inventory or production output changed during the proof");
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}
function metrics(label) {
  const values = retained[label];
  return {
    samples: values.length,
    medianElapsedMs: median(values.map((sample) => sample.resources.elapsedMs)),
    medianPeakRssBytes: median(values.map((sample) => sample.resources.sampledPeakRssBytes)),
    medianPeakRssGrowthBytes: median(
      values.map((sample) => sample.resources.sampledPeakRssGrowthBytes),
    ),
    medianPeakHeapGrowthBytes: median(
      values.map((sample) => sample.resources.sampledPeakHeapGrowthBytes),
    ),
  };
}

const reference = retained.head[0];
const base = metrics("base");
const head = metrics("head");
const report = {
  schema: "codex-cli-session-jsonl-production-proof-v2",
  base: baseSha,
  head: headSha,
  testRunner: "none",
  entrypoint:
    "extensions/codex/index.ts register -> registerNodeHostCommand -> codex.cli.sessions.list.handle",
  affectedOwners: [
    "bundled Codex plugin registration",
    "registered node-host command",
    "filesystem",
    "Codex rollout JSONL",
  ],
  mockedAffectedOwners: [],
  supportHarness: "registration receiver only",
  harness: { proofSha256: proofHarnessSha256 },
  dataSource: {
    kind: "real default Codex home",
    fileCount: reference.inventory.fileCount,
    totalBytes: reference.inventory.totalBytes,
    maxFileBytes: reference.inventory.maxFileBytes,
    inventoryDigest: reference.inventory.digest,
  },
  rawSamples: retained,
  result: {
    sessionCount: reference.result.sessionCount,
    outputBytes: reference.result.outputBytes,
    outputDigest: reference.result.digest,
    identicalAcrossAllSamples: true,
  },
  baseMetrics: base,
  headMetrics: head,
  elapsedReductionPercent: Number(
    (((base.medianElapsedMs - head.medianElapsedMs) / base.medianElapsedMs) * 100).toFixed(1),
  ),
  verdict: "PASS",
};
const serialized = JSON.stringify(report);
const outputPath = process.env.PROOF_OUTPUT_JSON?.trim();
if (outputPath) {
  fs.writeFileSync(outputPath, `${serialized}\n`, { mode: 0o600 });
}
console.log(serialized);
