import { execFileSync } from "node:child_process";
import crypto from "node:crypto";
import path from "node:path";
import { pathToFileURL } from "node:url";

const checkout = path.resolve(process.argv[2] ?? "");
const expectedHead = process.argv[3]?.trim() ?? "";
if (!checkout || !expectedHead) {
  throw new Error("usage: peak-child.mts <checkout> <expected-head>");
}
if (!process.env.CODEX_HOME?.trim()) {
  throw new Error("CODEX_HOME is required");
}
const expectedCwdBytes = Number(process.env.EXPECTED_CWD_BYTES);
const expectedMessageCount = Number(process.env.EXPECTED_MESSAGE_COUNT);
const expectedLastMessageDigest = process.env.EXPECTED_LAST_MESSAGE_DIGEST?.trim() ?? "";
if (
  !Number.isSafeInteger(expectedCwdBytes) ||
  !Number.isSafeInteger(expectedMessageCount) ||
  !expectedLastMessageDigest
) {
  throw new Error("boundary expectations are required");
}
const actualHead = execFileSync("git", ["-C", checkout, "rev-parse", "HEAD"], {
  encoding: "utf8",
}).trim();
if (actualHead !== expectedHead) {
  throw new Error(`HEAD mismatch: expected ${expectedHead}, got ${actualHead}`);
}

const moduleUrl = pathToFileURL(path.join(checkout, "extensions/codex/index.ts")).href;
const { default: plugin } = await import(moduleUrl);
const registeredNodeHostCommands = new Map<
  string,
  { handle: (params: string) => Promise<string> }
>();
const noop = () => undefined;
const api = new Proxy(
  {
    id: "codex",
    name: "Codex",
    source: "external-peak-proof",
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
const raw = await command.handle(JSON.stringify({ limit: 10 }));
const parsed = JSON.parse(raw) as { sessions?: unknown[] };
if (!Array.isArray(parsed.sessions)) {
  throw new Error("production node-host command returned no sessions array");
}
const session = parsed.sessions[0];
if (!session || typeof session !== "object") {
  throw new Error("production node-host command returned no first session");
}
const cwd = Reflect.get(session, "cwd");
const messageCount = Reflect.get(session, "messageCount");
const lastMessage = Reflect.get(session, "lastMessage");
const lastMessageDigest =
  typeof lastMessage === "string"
    ? crypto.createHash("sha256").update(lastMessage).digest("hex")
    : "";
if (
  typeof cwd !== "string" ||
  Buffer.byteLength(cwd) !== expectedCwdBytes ||
  !cwd.endsWith("🙂") ||
  messageCount !== expectedMessageCount ||
  lastMessageDigest !== expectedLastMessageDigest
) {
  throw new Error("production node-host command failed boundary assertions");
}

console.log(
  JSON.stringify({
    head: actualHead,
    testRunner: "none",
    entrypoint:
      "extensions/codex/index.ts register -> registerNodeHostCommand -> codex.cli.sessions.list.handle",
    affectedOwners: [
      "bundled Codex plugin registration",
      "registered node-host command",
      "filesystem",
      "Codex history and rollout JSONL",
    ],
    mockedAffectedOwners: [],
    supportHarness: "registration receiver only",
    result: {
      sessionCount: parsed.sessions.length,
      outputBytes: Buffer.byteLength(raw),
      digest: crypto.createHash("sha256").update(raw).digest("hex"),
      assertions: {
        cwdBytes: Buffer.byteLength(cwd),
        cwdEndsWithBoundaryEmoji: true,
        messageCount,
        lastMessageDigest,
      },
    },
  }),
);
