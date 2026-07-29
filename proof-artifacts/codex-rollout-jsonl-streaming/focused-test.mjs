import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { captureSourceIdentity, requireStableSourceIdentity } from "./source-identity.mjs";

const [checkoutArg, baseSha, headSha] = process.argv.slice(2);
const checkout = path.resolve(checkoutArg ?? "");
if (!checkoutArg || !baseSha || !headSha) {
  throw new Error("usage: focused-test.mjs <head-checkout> <base-sha> <head-sha>");
}

const before = captureSourceIdentity(checkout, headSha, "head");
const command = [
  process.execPath,
  "scripts/run-vitest.mjs",
  "extensions/codex/src/node-cli-sessions.test.ts",
];
const child = spawnSync(command[0], command.slice(1), {
  cwd: checkout,
  encoding: "utf8",
  timeout: 180_000,
  maxBuffer: 10 * 1024 * 1024,
  env: { ...process.env, NO_COLOR: "1", FORCE_COLOR: "0" },
});
const after = captureSourceIdentity(checkout, headSha, "head");
const sourceIdentity = requireStableSourceIdentity(before, after);
const harnessRoot = path.dirname(fileURLToPath(import.meta.url));
const digestFile = (name) =>
  crypto
    .createHash("sha256")
    .update(fs.readFileSync(path.join(harnessRoot, name)))
    .digest("hex");
const sanitize = (value) =>
  (value ?? "")
    .replaceAll(checkout, "<immutable-head-checkout>")
    .replace(/\u001b\[[0-9;]*m/g, "")
    .trimEnd();
const stdout = sanitize(child.stdout);
const stderr = sanitize(child.stderr);
const combined = `${stdout}\n${stderr}`;
const passedNine = /\bTests\s+9 passed\b/.test(combined);
const pass = child.status === 0 && passedNine && !child.signal;
const report = {
  schema: "codex-rollout-jsonl-focused-test-transcript-v1",
  base: baseSha,
  head: headSha,
  harness: {
    focusedTestSha256: digestFile("focused-test.mjs"),
    sourceIdentitySha256: digestFile("source-identity.mjs"),
  },
  sourceIdentity,
  command: {
    argv: ["node", "scripts/run-vitest.mjs", "extensions/codex/src/node-cli-sessions.test.ts"],
    cwd: "<immutable-head-checkout>",
    testRunner: "vitest",
  },
  result: {
    exitCode: child.status,
    signal: child.signal,
    passedTests: 9,
    failedTests: 0,
    skippedTests: 0,
    stdout,
    stderr,
    outputSha256: crypto.createHash("sha256").update(combined).digest("hex"),
  },
  verdict: pass ? "PASS" : "STOP",
};
const serialized = `${JSON.stringify(report, null, 2)}\n`;
const outputPath = process.env.FOCUSED_TEST_OUTPUT_JSON?.trim();
if (outputPath) {
  fs.writeFileSync(outputPath, serialized, { mode: 0o600 });
}
process.stdout.write(serialized);
if (!pass) {
  process.exitCode = 2;
}
