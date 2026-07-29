import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { captureSourceIdentity, requireStableSourceIdentity } from "./source-identity.mjs";

const checkout = path.resolve(process.argv[2] ?? "");
const expectedHead = process.argv[3]?.trim() ?? "";
if (!checkout || !expectedHead) {
  throw new Error("usage: proof.mts <checkout> <expected-head>");
}
const sourceIdentityBefore = captureSourceIdentity(
  checkout,
  expectedHead,
  process.env.PROOF_CHECKOUT_LABEL?.trim() || "unspecified",
);
const actualHead = sourceIdentityBefore.head;

delete process.env.CODEX_HOME;
const sessionsRoot = path.join(os.homedir(), ".codex", "sessions");
const inventoryRows: string[] = [];
let fileCount = 0;
let totalBytes = 0;
let maxFileBytes = 0;

async function inventory(dir: string, depth: number): Promise<void> {
  if (depth < 0) {
    return;
  }
  let entries: Array<import("node:fs").Dirent>;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const entryPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      await inventory(entryPath, depth - 1);
      continue;
    }
    if (!entry.isFile() || !entry.name.endsWith(".jsonl")) {
      continue;
    }
    try {
      const stat = await fs.stat(entryPath);
      fileCount += 1;
      totalBytes += stat.size;
      maxFileBytes = Math.max(maxFileBytes, stat.size);
      inventoryRows.push(
        `${path.relative(sessionsRoot, entryPath)}\0${stat.size}\0${stat.mtimeMs}`,
      );
    } catch {
      // Match the production reader's fail-soft treatment of disappearing files.
    }
  }
}

await inventory(sessionsRoot, 4);
inventoryRows.sort();
const inventoryDigest = crypto
  .createHash("sha256")
  .update(inventoryRows.join("\n"))
  .digest("hex");

const moduleUrl = pathToFileURL(path.join(checkout, "extensions/codex/index.ts")).href;
const { default: plugin } = await import(moduleUrl);
const registeredNodeHostCommands = new Map<string, { handle: (params: string) => Promise<string> }>();
const noop = () => undefined;
const api = new Proxy(
  {
    id: "codex",
    name: "Codex",
    source: "production-proof",
    config: {},
    pluginConfig: { sessionCatalog: { enabled: false } },
    runtime: {
      state: {
        openSyncKeyedStore: () => {
          throw new Error("proof must not open unrelated plugin state");
        },
      },
    },
    registerNodeHostCommand(command: {
      command: string;
      handle: (params: string) => Promise<string>;
    }) {
      registeredNodeHostCommands.set(command.command, command);
    },
  },
  {
    get(target, property, receiver) {
      return Reflect.has(target, property) ? Reflect.get(target, property, receiver) : noop;
    },
  },
);
plugin.register(api);
const command = registeredNodeHostCommands.get("codex.cli.sessions.list");
if (!command) {
  throw new Error("production plugin did not register codex.cli.sessions.list");
}

globalThis.gc?.();
await new Promise<void>((resolve) => setImmediate(resolve));
const memoryBefore = process.memoryUsage();
let sampledPeakRssBytes = memoryBefore.rss;
let sampledPeakHeapBytes = memoryBefore.heapUsed;
const sampler = setInterval(() => {
  const sample = process.memoryUsage();
  sampledPeakRssBytes = Math.max(sampledPeakRssBytes, sample.rss);
  sampledPeakHeapBytes = Math.max(sampledPeakHeapBytes, sample.heapUsed);
}, 1);
sampler.unref();

const startedAt = performance.now();
const raw = await command.handle(JSON.stringify({ limit: 10 }));
const elapsedMs = performance.now() - startedAt;
clearInterval(sampler);
const memoryAfter = process.memoryUsage();
sampledPeakRssBytes = Math.max(sampledPeakRssBytes, memoryAfter.rss);
sampledPeakHeapBytes = Math.max(sampledPeakHeapBytes, memoryAfter.heapUsed);
const parsed = JSON.parse(raw) as { sessions?: unknown[] };
if (!Array.isArray(parsed.sessions)) {
  throw new Error("production node-host command returned no sessions array");
}
const sourceIdentity = requireStableSourceIdentity(
  sourceIdentityBefore,
  captureSourceIdentity(
    checkout,
    expectedHead,
    process.env.PROOF_CHECKOUT_LABEL?.trim() || "unspecified",
  ),
);

console.log(
  JSON.stringify({
    head: actualHead,
    sourceIdentity,
    entrypoint:
      "extensions/codex/index.ts register -> registerNodeHostCommand -> codex.cli.sessions.list.handle",
    registration: {
      commandCount: registeredNodeHostCommands.size,
      selectedCommand: "codex.cli.sessions.list",
    },
    dataSource: "real default Codex home",
    testRunner: "none",
    affectedOwners: [
      "bundled Codex plugin registration",
      "registered node-host command",
      "filesystem",
      "Codex rollout JSONL",
    ],
    mockedAffectedOwners: [],
    supportHarness: "registration receiver only",
    inventory: {
      fileCount,
      totalBytes,
      maxFileBytes,
      digest: inventoryDigest,
    },
    result: {
      sessionCount: parsed.sessions.length,
      outputBytes: Buffer.byteLength(raw),
      digest: crypto.createHash("sha256").update(raw).digest("hex"),
    },
    resources: {
      elapsedMs: Math.round(elapsedMs),
      rssBeforeBytes: memoryBefore.rss,
      rssAfterBytes: memoryAfter.rss,
      sampledPeakRssBytes,
      sampledPeakRssGrowthBytes: Math.max(0, sampledPeakRssBytes - memoryBefore.rss),
      heapBeforeBytes: memoryBefore.heapUsed,
      heapAfterBytes: memoryAfter.heapUsed,
      sampledPeakHeapBytes,
      sampledPeakHeapGrowthBytes: Math.max(0, sampledPeakHeapBytes - memoryBefore.heapUsed),
    },
  }),
);
