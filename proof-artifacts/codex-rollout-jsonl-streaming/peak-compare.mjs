import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const [baseCheckout, headCheckout, baseSha, headSha] = process.argv.slice(2);
if (!baseCheckout || !headCheckout || !baseSha || !headSha) {
  throw new Error("usage: peak-compare.mjs <base-checkout> <head-checkout> <base-sha> <head-sha>");
}
const proofRoot = path.dirname(fileURLToPath(import.meta.url));
const childScript = path.join(proofRoot, "peak-child.mts");
const fixtureRoot = await fsp.mkdtemp(path.join(proofRoot, "peak-fixture-"));
const codexHome = path.join(fixtureRoot, "codex-home");
const historyFile = path.join(codexHome, "history.jsonl");
const sessionDir = path.join(codexHome, "sessions", "2026", "07", "29");
const sessionId = "019e23d1-f33d-78e3-959e-0f56f30a5fff";
const rolloutFile = path.join(sessionDir, `rollout-2026-07-29T00-00-00-${sessionId}.jsonl`);
const recordCount = 2_048;
const paddingBytes = 64 * 1_024;
const historyRecordCount = 80;
const historyPaddingBytes = 64 * 1_024;
const firstLineChunkBytes = 64 * 1_024;
const boundaryEmoji = "🙂";
const lastHistoryMessage = "external peak proof history";
const samples = { base: [], head: [] };

function write(stream, value) {
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      stream.off("drain", onDrain);
      reject(error);
    };
    const onDrain = () => {
      stream.off("error", onError);
      resolve();
    };
    stream.once("error", onError);
    if (stream.write(value)) {
      stream.off("error", onError);
      resolve();
    } else {
      stream.once("drain", onDrain);
    }
  });
}

async function finish(stream) {
  await new Promise((resolve, reject) => {
    stream.once("error", reject);
    stream.end(resolve);
  });
}

async function digestFile(file) {
  return await new Promise((resolve, reject) => {
    const hash = crypto.createHash("sha256");
    const input = fs.createReadStream(file);
    input.on("error", reject);
    input.on("data", (chunk) => hash.update(chunk));
    input.on("end", () => resolve(hash.digest("hex")));
  });
}

function createBoundarySessionMeta() {
  const marker = "__BOUNDARY_EMOJI__";
  const cwdPrefix = "/tmp/codex-peak-proof-";
  const record = (cwd, boundaryPadding) =>
    JSON.stringify({
      timestamp: "2026-07-29T00:00:00.000Z",
      type: "session_meta",
      payload: { id: sessionId, cwd, boundary_padding: boundaryPadding },
    });
  const markerTemplate = record(`${cwdPrefix}${marker}`, "");
  const markerCharacterOffset = markerTemplate.indexOf(marker);
  const markerByteOffset = Buffer.byteLength(markerTemplate.slice(0, markerCharacterOffset));
  const beforeEmojiBytes = firstLineChunkBytes - 2 - markerByteOffset;
  if (beforeEmojiBytes < 0) {
    throw new Error("session-meta prefix is too large for the boundary fixture");
  }
  const cwd = `${cwdPrefix}${"c".repeat(beforeEmojiBytes)}${boundaryEmoji}`;
  const unpadded = record(cwd, "");
  const targetRecordBytes = firstLineChunkBytes * 2 - 1;
  const boundaryPaddingBytes = targetRecordBytes - Buffer.byteLength(unpadded);
  if (boundaryPaddingBytes < 0) {
    throw new Error("session-meta record is too large for the boundary fixture");
  }
  const line = record(cwd, "p".repeat(boundaryPaddingBytes));
  const encoded = Buffer.from(line);
  const emojiByteOffset = encoded.indexOf(Buffer.from(boundaryEmoji));
  if (encoded.length !== targetRecordBytes || emojiByteOffset !== firstLineChunkBytes - 2) {
    throw new Error("session-meta UTF-8 boundary alignment failed");
  }
  return {
    line,
    cwdBytes: Buffer.byteLength(cwd),
    recordBytes: encoded.length,
    emojiByteOffset,
    crByteOffset: encoded.length,
    lfByteOffset: encoded.length + 1,
  };
}

async function createFixture() {
  await fsp.mkdir(sessionDir, { recursive: true });
  const historyStream = fs.createWriteStream(historyFile, { encoding: "utf8" });
  const historyPadding = "h".repeat(historyPaddingBytes);
  let historyMaxRecordBytes = 0;
  for (let index = 0; index < historyRecordCount; index += 1) {
    const text =
      index === historyRecordCount - 1
        ? lastHistoryMessage
        : `${String(index).padStart(4, "0")}:${historyPadding}`;
    const record = JSON.stringify({
      session_id: sessionId,
      ts: 1_785_283_200 + index,
      text,
    });
    historyMaxRecordBytes = Math.max(historyMaxRecordBytes, Buffer.byteLength(record));
    await write(historyStream, `${record}\n`);
  }
  await finish(historyStream);

  const stream = fs.createWriteStream(rolloutFile, { encoding: "utf8" });
  const firstRecord = createBoundarySessionMeta();
  await write(stream, `${firstRecord.line}\r\n`);
  const padding = "x".repeat(paddingBytes);
  let maxRecordBytes = 0;
  for (let index = 0; index < recordCount; index += 1) {
    const record = JSON.stringify({
      timestamp: "2026-07-29T00:00:01.000Z",
      type: "event_msg",
      payload: { type: "token_count", sequence: index, padding },
    });
    maxRecordBytes = Math.max(maxRecordBytes, Buffer.byteLength(record));
    await write(stream, `${record}\n`);
  }
  await write(
    stream,
    JSON.stringify({
      timestamp: "2026-07-29T00:00:02.000Z",
      type: "response_item",
      payload: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: "external peak proof" }],
      },
    }),
  );
  await finish(stream);
  const [rolloutStat, historyStat, rolloutDigest, historyDigest] = await Promise.all([
    fsp.stat(rolloutFile),
    fsp.stat(historyFile),
    digestFile(rolloutFile),
    digestFile(historyFile),
  ]);
  return {
    rollout: {
      fileBytes: rolloutStat.size,
      physicalRecordCount: recordCount + 2,
      fillerRecordCount: recordCount,
      maxRecordBytes: Math.max(maxRecordBytes, firstRecord.recordBytes),
      firstRecordBytes: firstRecord.recordBytes,
      firstLineChunkBytes,
      boundaryEmojiByteOffset: firstRecord.emojiByteOffset,
      boundaryCrByteOffset: firstRecord.crByteOffset,
      boundaryLfByteOffset: firstRecord.lfByteOffset,
      digest: rolloutDigest,
    },
    history: {
      fileBytes: historyStat.size,
      physicalRecordCount: historyRecordCount,
      maxRecordBytes: historyMaxRecordBytes,
      digest: historyDigest,
    },
    totalBytes: rolloutStat.size + historyStat.size,
    expectations: {
      cwdBytes: firstRecord.cwdBytes,
      cwdEndsWithBoundaryEmoji: true,
      messageCount: historyRecordCount,
      lastMessageDigest: crypto.createHash("sha256").update(lastHistoryMessage).digest("hex"),
    },
  };
}

function run(label, checkout, sha, sampleIndex) {
  const metricsPath = path.join(fixtureRoot, `${label}-${sampleIndex}.time`);
  const child = spawnSync(
    "/usr/bin/time",
    [
      "-f",
      "%M\t%e",
      "-o",
      metricsPath,
      process.execPath,
      "--expose-gc",
      "--import",
      "tsx",
      childScript,
      checkout,
      sha,
    ],
    {
      cwd: checkout,
      encoding: "utf8",
      env: {
        ...process.env,
        CODEX_HOME: codexHome,
        EXPECTED_CWD_BYTES: String(fixture.expectations.cwdBytes),
        EXPECTED_MESSAGE_COUNT: String(fixture.expectations.messageCount),
        EXPECTED_LAST_MESSAGE_DIGEST: fixture.expectations.lastMessageDigest,
        PRODUCTION_ENTRYPOINT: "codex.cli.sessions.list",
      },
      timeout: 180_000,
    },
  );
  if (child.status !== 0) {
    throw new Error(`${label} peak child failed: ${child.stderr || child.stdout}`);
  }
  const parsed = JSON.parse(child.stdout);
  const [maxRssKiB, elapsedSeconds] = fs.readFileSync(metricsPath, "utf8").trim().split("\t");
  samples[label].push({
    sampleIndex,
    maxRssBytes: Number(maxRssKiB) * 1_024,
    elapsedMs: Math.round(Number(elapsedSeconds) * 1_000),
    result: parsed.result,
    entrypoint: parsed.entrypoint,
    mockedAffectedOwners: parsed.mockedAffectedOwners,
  });
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

let fixture;
try {
  fixture = await createFixture();
  for (let index = 0; index < 4; index += 1) {
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
      run(label, checkout, sha, index);
    }
  }
  const retained = {
    base: samples.base.slice(1),
    head: samples.head.slice(1),
  };
  const all = [...retained.base, ...retained.head];
  const outputDigests = new Set(all.map((sample) => sample.result.digest));
  const baseMedianMaxRssBytes = median(retained.base.map((sample) => sample.maxRssBytes));
  const headMedianMaxRssBytes = median(retained.head.map((sample) => sample.maxRssBytes));
  const reductionPercent = Number(
    (((baseMedianMaxRssBytes - headMedianMaxRssBytes) / baseMedianMaxRssBytes) * 100).toFixed(1),
  );
  const pass = outputDigests.size === 1 && reductionPercent >= 10;
  const report = {
    schema: "codex-cli-session-jsonl-external-peak-proof-v1",
    base: baseSha,
    head: headSha,
    testRunner: "none",
    measurement: "external /usr/bin/time maximum resident set size",
    entrypoint:
      "extensions/codex/index.ts register -> registerNodeHostCommand -> codex.cli.sessions.list.handle",
    affectedOwners: [
      "bundled Codex plugin registration",
      "registered node-host command",
      "filesystem",
      "Codex history and rollout JSONL",
    ],
    mockedAffectedOwners: [],
    supportHarness:
      "registration receiver, deterministic fixture generator, and sanitized boundary assertions only",
    fixture,
    retainedSamples: retained,
    baseMedianMaxRssBytes,
    headMedianMaxRssBytes,
    maxRssReductionPercent: reductionPercent,
    outputsIdenticalAcrossAllRetainedSamples: outputDigests.size === 1,
    outputDigest: all[0]?.result.digest,
    verdict: pass ? "PASS" : "STOP",
  };
  const serialized = JSON.stringify(report);
  const outputPath = process.env.PEAK_OUTPUT_JSON?.trim();
  if (outputPath) {
    fs.writeFileSync(outputPath, `${serialized}\n`, { mode: 0o600 });
  }
  console.log(serialized);
  if (!pass) {
    process.exitCode = 2;
  }
} finally {
  await fsp.rm(fixtureRoot, { recursive: true, force: true });
}
