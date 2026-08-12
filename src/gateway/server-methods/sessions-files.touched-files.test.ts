import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { sessionsFilesHandlers } from "./sessions-files.js";
import {
  assistantToolCall,
  createSessionFilesHandlerInvoker,
  createVisibleMessagesMock,
  createWorkspaceFixture,
  expectOkPayload,
  useSqliteSession,
  visibleMessageEvent,
  writeWorkspaceFile,
} from "./sessions-files.test-support.js";

const hoisted = vi.hoisted(() => ({
  loadSessionEntry: vi.fn(),
  resolveAgentWorkspaceDir: vi.fn(),
  resolveDefaultAgentId: vi.fn(),
  parseSessionTranscriptVisibleMessageCursorGeneration: vi.fn(),
  readSessionTranscriptVisibleMessageDeltaCore: vi.fn(),
}));

vi.mock("../../agents/agent-scope.js", () => ({
  resolveAgentWorkspaceDir: hoisted.resolveAgentWorkspaceDir,
  resolveDefaultAgentId: hoisted.resolveDefaultAgentId,
}));

vi.mock("../session-utils.js", async () => {
  const actual = await vi.importActual<typeof import("../session-utils.js")>("../session-utils.js");
  return {
    ...actual,
    loadSessionEntry: hoisted.loadSessionEntry,
    loadGatewaySessionEntryReadOnly: hoisted.loadSessionEntry,
  };
});

vi.mock("../session-transcript-readers.js", async () => {
  const actual = await vi.importActual<typeof import("../session-transcript-readers.js")>(
    "../session-transcript-readers.js",
  );
  return {
    ...actual,
    parseSessionTranscriptVisibleMessageCursorGeneration:
      hoisted.parseSessionTranscriptVisibleMessageCursorGeneration,
    readSessionTranscriptVisibleMessageDeltaCore:
      hoisted.readSessionTranscriptVisibleMessageDeltaCore,
  };
});

const invokeSessionFilesHandler = createSessionFilesHandlerInvoker(sessionsFilesHandlers);
const mockVisibleMessages = createVisibleMessagesMock(
  hoisted.readSessionTranscriptVisibleMessageDeltaCore,
);

describe("sessions.files touched-file folds", () => {
  let workspaceRoot: string;

  beforeEach(() => {
    vi.clearAllMocks();
    hoisted.parseSessionTranscriptVisibleMessageCursorGeneration.mockReset();
    hoisted.parseSessionTranscriptVisibleMessageCursorGeneration.mockReturnValue("test-generation");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockReset();
    workspaceRoot = createWorkspaceFixture("openclaw-session-touched-files-test-");
    hoisted.resolveDefaultAgentId.mockReturnValue("main");
    hoisted.resolveAgentWorkspaceDir.mockReturnValue(workspaceRoot);
  });

  afterEach(() => {
    fs.rmSync(workspaceRoot, { recursive: true, force: true });
  });

  it("lists session-touched files with a browser rooted at the session workspace", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-list");
    mockVisibleMessages([
      assistantToolCall("edit", { path: "ui/chat.ts" }),
      assistantToolCall("read", { path: "src/readme.md" }),
      assistantToolCall("apply_patch", {
        input: "*** Begin Patch\n*** Update File: package.json\n*** End Patch\n",
      }),
    ]);

    const payload = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(payload.root).toBe(workspaceRoot);
    expect(payload.gitCheckout).toBe(false);
    expect(payload.files.map((file: Record<string, unknown>) => [file.path, file.kind])).toEqual([
      ["package.json", "modified"],
      ["ui/chat.ts", "modified"],
      ["src/readme.md", "read"],
    ]);
    expect(payload.browser.path).toBe("");
    expect(
      payload.browser.entries.map((entry: Record<string, unknown>) => [
        entry.path,
        entry.kind,
        entry.sessionKind,
      ]),
    ).toEqual([
      ["src", "directory", "read"],
      ["ui", "directory", "modified"],
      ["package.json", "file", "modified"],
    ]);
    execFileSync("git", ["-C", workspaceRoot, "init", "-q", "-b", "main"]);
    const gitPayload = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", { sessionKey: "agent:main:main" }),
    );
    expect(gitPayload.gitCheckout).toBe(true);
  });

  it("keeps modified activity when browser aliases resolve to the same file", async () => {
    const nestedCwd = path.join(workspaceRoot, "packages/app");
    fs.mkdirSync(path.join(nestedCwd, "src"), { recursive: true });
    writeWorkspaceFile(workspaceRoot, "packages/app/src/readme.md", "# Nested read me\n");
    hoisted.loadSessionEntry.mockReturnValue({
      canonicalKey: "agent:main:main",
      cfg: {},
      storePath: path.join(workspaceRoot, ".sessions.json"),
      entry: {
        sessionId: "sess-alias-activity",
        sessionFile: "sess-alias-activity.jsonl",
        spawnedCwd: nestedCwd,
        spawnedWorkspaceDir: workspaceRoot,
      },
    });
    mockVisibleMessages([
      assistantToolCall("edit", { path: "./src/readme.md" }),
      assistantToolCall("edit", { path: "src/readme.md" }),
    ]);

    const listed = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    expect(listed.files).toHaveLength(1);
    const modified = listed.files[0];
    expect(modified).toMatchObject({
      path: "src/readme.md",
      kind: "modified",
      activityId: expect.stringMatching(/^[a-f0-9]{64}$/),
      activityRevision: expect.any(Number),
    });

    const browserPreview = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.get", {
        sessionKey: "agent:main:main",
        path: "packages/app/src/readme.md",
      }),
    );

    expect(browserPreview.activityScope).toBe(listed.activityScope);
    expect(browserPreview.file).toMatchObject({
      kind: "modified",
      activityId: modified?.activityId,
      activityRevision: modified?.activityRevision,
      workspacePath: "packages/app/src/readme.md",
    });

    const olderAliasPreview = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.get", {
        sessionKey: "agent:main:main",
        path: "./src/readme.md",
      }),
    );
    expect(olderAliasPreview.file).toMatchObject({
      activityId: modified?.activityId,
      activityRevision: modified?.activityRevision,
      workspacePath: "packages/app/src/readme.md",
    });
  });

  it("folds only appended SQLite messages after the cached cursor", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-incremental");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((_scope, limits) => {
      if (limits.cursor === undefined) {
        return {
          kind: "page",
          cursor: "cursor-1",
          events: [visibleMessageEvent(assistantToolCall("read", { path: "ui/chat.ts" }), 1)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      if (limits.cursor === "cursor-1") {
        return {
          kind: "page",
          cursor: "cursor-2",
          events: [visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 2)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      throw new Error(`unexpected cursor: ${String(limits.cursor)}`);
    });

    const first = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    const second = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(first.files).toEqual([expect.objectContaining({ path: "ui/chat.ts", kind: "read" })]);
    expect(second.files).toEqual([
      expect.objectContaining({ path: "ui/chat.ts", kind: "modified" }),
    ]);
    expect(hoisted.readSessionTranscriptVisibleMessageDeltaCore).toHaveBeenCalledTimes(2);
    expect(hoisted.readSessionTranscriptVisibleMessageDeltaCore.mock.calls[1]?.[1]).toMatchObject({
      cursor: "cursor-1",
    });
  });

  it("advances modified-file activity only when the transcript writes that file again", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-activity");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((_scope, limits) => {
      if (limits.cursor === undefined) {
        return {
          kind: "page",
          cursor: "activity-1",
          events: [visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 4)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      if (limits.cursor === "activity-1") {
        return {
          kind: "page",
          cursor: "activity-2",
          events: [visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 9)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      if (limits.cursor === "activity-2") {
        return {
          kind: "page",
          cursor: "activity-3",
          events: [visibleMessageEvent(assistantToolCall("read", { path: "ui/chat.ts" }), 12)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      throw new Error("unexpected cursor: " + String(limits.cursor));
    });

    const first = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    const second = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    const afterRead = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(first.activityScope).toMatch(/^[a-f0-9]{64}$/);
    expect(second.activityScope).toBe(first.activityScope);
    expect(first.files).toEqual([
      expect.objectContaining({
        path: "ui/chat.ts",
        kind: "modified",
        activityId: expect.stringMatching(/^[a-f0-9]{64}$/),
        activityRevision: 4,
      }),
    ]);
    expect(second.files).toEqual([
      expect.objectContaining({
        activityId: first.files[0]?.activityId,
        activityRevision: 9,
      }),
    ]);
    expect(afterRead.files).toEqual([
      expect.objectContaining({
        activityId: first.files[0]?.activityId,
        activityRevision: 9,
      }),
    ]);
  });

  it("does not advance modified activity for a failed tool result", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-failed-write");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((_scope, limits) => {
      if (limits.cursor === undefined) {
        return {
          kind: "page",
          cursor: "failed-write-success",
          events: [
            visibleMessageEvent(
              {
                role: "assistant",
                content: [
                  {
                    type: "toolCall",
                    id: "edit-success",
                    name: "edit",
                    arguments: { path: "ui/chat.ts" },
                  },
                ],
              },
              4,
            ),
            visibleMessageEvent(
              {
                role: "toolResult",
                toolCallId: "edit-success",
                toolName: "edit",
                content: [{ type: "text", text: "ok" }],
                isError: false,
              },
              5,
            ),
          ],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      if (limits.cursor === "failed-write-success") {
        return {
          kind: "page",
          cursor: "failed-write-final",
          events: [
            visibleMessageEvent(
              {
                role: "assistant",
                content: [
                  {
                    type: "toolCall",
                    id: "edit-failed",
                    name: "edit",
                    arguments: { path: "ui/chat.ts" },
                  },
                ],
              },
              9,
            ),
            visibleMessageEvent(
              {
                role: "toolResult",
                toolCallId: "edit-failed",
                toolName: "edit",
                content: [{ type: "text", text: "failed" }],
                isError: true,
              },
              10,
            ),
          ],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      throw new Error("unexpected cursor: " + String(limits.cursor));
    });

    const first = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", { sessionKey: "agent:main:main" }),
    );
    const afterFailure = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", { sessionKey: "agent:main:main" }),
    );

    expect(first.files[0]).toMatchObject({ activityRevision: 5, path: "ui/chat.ts" });
    expect(afterFailure.files[0]).toMatchObject({ activityRevision: 5, path: "ui/chat.ts" });
  });

  it("advances apply_patch candidates when a later hunk can fail after an earlier mutation", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-partial-patch");
    const patchInput = `*** Begin Patch
*** Update File: ui/chat.ts
@@
-export const chat = true;
+export const chat = "partially changed";
*** Update File: src/missing.ts
@@
-missing
+changed
*** End Patch`;
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockReturnValue({
      kind: "page",
      cursor: "partial-apply-patch-final",
      events: [
        visibleMessageEvent(
          {
            role: "assistant",
            content: [
              {
                type: "toolCall",
                id: "partial-apply-patch",
                name: "apply_patch",
                arguments: { input: patchInput },
              },
            ],
          },
          1,
        ),
        visibleMessageEvent(
          {
            role: "toolResult",
            toolCallId: "partial-apply-patch",
            toolName: "apply_patch",
            content: [{ type: "text", text: "later hunk failed" }],
            isError: true,
          },
          2,
        ),
      ],
      hasMore: false,
      serializedBytes: 100,
    });

    const payload = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(payload.files).toEqual([
      expect.objectContaining({
        activityRevision: 2,
        kind: "modified",
        missing: true,
        path: "src/missing.ts",
      }),
      expect.objectContaining({
        activityRevision: 2,
        kind: "modified",
        missing: false,
        path: "ui/chat.ts",
      }),
    ]);
  });

  it("uses raw event revisions when an active branch reuses a visible ordinal", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-branch-change");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((_scope, limits) => {
      if (limits.cursor === undefined) {
        return {
          kind: "page",
          cursor: "same-generation-first",
          events: [
            {
              ...visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 4),
              eventSeq: 7,
            },
          ],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      if (limits.cursor === "same-generation-first") {
        return {
          kind: "reset",
          cursor: "same-generation-bootstrap",
          reason: "anchor_moved",
        };
      }
      if (limits.cursor === "same-generation-bootstrap") {
        return {
          kind: "page",
          cursor: "same-generation-second",
          events: [
            {
              ...visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 4),
              eventSeq: 12,
            },
          ],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      throw new Error("unexpected cursor: " + String(limits.cursor));
    });

    const first = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    const changedBranch = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(changedBranch.activityScope).toBe(first.activityScope);
    expect(changedBranch.files[0]).toMatchObject({
      activityId: first.files[0]?.activityId,
      activityRevision: 12,
      path: "ui/chat.ts",
    });
    expect(first.files[0]).toMatchObject({ activityRevision: 7, path: "ui/chat.ts" });
  });

  it("rotates activity identity when a transcript replacement reuses a message ordinal", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-replacement");
    hoisted.parseSessionTranscriptVisibleMessageCursorGeneration.mockImplementation((cursor) =>
      String(cursor).startsWith("generation-one") ? "generation-one" : "generation-two",
    );
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((_scope, limits) => {
      if (limits.cursor === undefined) {
        return {
          kind: "page",
          cursor: "generation-one-cursor",
          events: [visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 4)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      if (limits.cursor === "generation-one-cursor") {
        return {
          kind: "reset",
          cursor: "generation-two-bootstrap",
          reason: "generation_mismatch",
        };
      }
      if (limits.cursor === "generation-two-bootstrap") {
        return {
          kind: "page",
          cursor: "generation-two-cursor",
          events: [visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 4)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      throw new Error("unexpected cursor: " + String(limits.cursor));
    });

    const first = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    const replacement = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(first.files[0]).toMatchObject({ activityRevision: 4, path: "ui/chat.ts" });
    expect(replacement.files[0]).toMatchObject({ activityRevision: 4, path: "ui/chat.ts" });
    expect(replacement.activityScope).not.toBe(first.activityScope);
    expect(replacement.files[0]?.activityId).not.toBe(first.files[0]?.activityId);
  });

  it("yields between SQLite pages and shares one concurrent fold per session", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-singleflight");
    let otherWorkRan = false;
    setImmediate(() => {
      otherWorkRan = true;
    });
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((_scope, limits) => {
      if (limits.cursor !== undefined) {
        expect(limits.cursor).toBe("singleflight-page-1");
        expect(otherWorkRan).toBe(true);
      }
      return {
        kind: "page",
        cursor: limits.cursor === undefined ? "singleflight-page-1" : "singleflight-final",
        events: [],
        hasMore: limits.cursor === undefined,
        serializedBytes: 100,
      };
    });

    const params = { sessionKey: "agent:main:main" };
    const first = invokeSessionFilesHandler("sessions.files.list", params);
    const second = invokeSessionFilesHandler("sessions.files.list", params);

    expect(hoisted.readSessionTranscriptVisibleMessageDeltaCore).toHaveBeenCalledTimes(1);
    for (const result of await Promise.all([first, second])) {
      expectOkPayload(result);
    }
    expect(hoisted.readSessionTranscriptVisibleMessageDeltaCore).toHaveBeenCalledTimes(2);
  });

  it("lets a different session finish while a multi-page fold is yielded", async () => {
    hoisted.resolveAgentWorkspaceDir.mockReturnValue(undefined);
    hoisted.loadSessionEntry.mockImplementation((sessionKey: string) => {
      const sessionId = sessionKey.endsWith(":slow") ? "sess-touched-slow" : "sess-touched-fast";
      const storePath = path.join(workspaceRoot, `${sessionId}.sqlite`);
      return {
        canonicalKey: sessionKey,
        cfg: {},
        storePath,
        entry: { sessionId, sessionFile: `sqlite:main:${sessionId}:${storePath}` },
      };
    });
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((scope, limits) => {
      const isSlow = scope.sessionId === "sess-touched-slow";
      return {
        kind: "page",
        cursor: isSlow ? "slow-final" : "fast-final",
        events: [],
        hasMore: isSlow && limits.cursor === undefined,
        serializedBytes: 100,
      };
    });

    let slowFinished = false;
    const slow = invokeSessionFilesHandler("sessions.files.list", {
      sessionKey: "agent:main:slow",
    }).then((result) => {
      slowFinished = true;
      return result;
    });
    expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", { sessionKey: "agent:main:fast" }),
    );

    expect(slowFinished).toBe(false);
    expectOkPayload(await slow);
  });

  it("isolates touched-file folds for the same session across stores", async () => {
    const sessionId = "sess-touched-multi-store";
    const firstStorePath = path.join(workspaceRoot, "store-a.sqlite");
    const secondStorePath = path.join(workspaceRoot, "store-b.sqlite");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((scope, limits) => {
      expect(limits.cursor).toBeUndefined();
      const message =
        scope.storePath === firstStorePath
          ? assistantToolCall("read", { path: "src/readme.md" })
          : assistantToolCall("edit", { path: "ui/chat.ts" });
      return {
        kind: "page",
        cursor: `${scope.storePath}-final`,
        events: [visibleMessageEvent(message, 1)],
        hasMore: false,
        serializedBytes: 100,
      };
    });

    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, sessionId, firstStorePath);
    const first = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, sessionId, secondStorePath);
    const second = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(first.files).toEqual([expect.objectContaining({ path: "src/readme.md", kind: "read" })]);
    expect(second.files).toEqual([
      expect.objectContaining({ path: "ui/chat.ts", kind: "modified" }),
    ]);
  });

  it("rebuilds the SQLite fold from the bootstrap cursor after a reset", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-reset");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockImplementation((_scope, limits) => {
      if (limits.cursor === undefined) {
        return {
          kind: "page",
          cursor: "generation-1",
          events: [visibleMessageEvent(assistantToolCall("read", { path: "src/readme.md" }), 1)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      if (limits.cursor === "generation-1") {
        return {
          kind: "reset",
          cursor: "generation-2-bootstrap",
          reason: "generation_mismatch",
        };
      }
      if (limits.cursor === "generation-2-bootstrap") {
        return {
          kind: "page",
          cursor: "generation-2-final",
          events: [visibleMessageEvent(assistantToolCall("edit", { path: "ui/chat.ts" }), 1)],
          hasMore: false,
          serializedBytes: 100,
        };
      }
      throw new Error(`unexpected cursor: ${String(limits.cursor)}`);
    });

    const beforeReset = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );
    const afterReset = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(beforeReset.files).toEqual([
      expect.objectContaining({ path: "src/readme.md", kind: "read" }),
    ]);
    expect(afterReset.files).toEqual([
      expect.objectContaining({ path: "ui/chat.ts", kind: "modified" }),
    ]);
    expect(hoisted.readSessionTranscriptVisibleMessageDeltaCore).toHaveBeenCalledTimes(3);
  });

  it("collects the expected files and kinds from the SQLite fold", async () => {
    const messages = [
      assistantToolCall("read", { path: "ui/chat.ts" }),
      assistantToolCall("edit", { path: "ui/chat.ts" }),
      assistantToolCall("read", { path: "src/readme.md" }),
      assistantToolCall("apply_patch", {
        input: "*** Begin Patch\n*** Update File: package.json\n*** End Patch\n",
      }),
    ];
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-parity");
    hoisted.readSessionTranscriptVisibleMessageDeltaCore.mockReturnValue({
      kind: "page",
      cursor: "parity-final",
      events: messages.map(visibleMessageEvent),
      hasMore: false,
      serializedBytes: 400,
    });

    const payload = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(payload.files.map((file: Record<string, unknown>) => [file.path, file.kind])).toEqual([
      ["package.json", "modified"],
      ["ui/chat.ts", "modified"],
      ["src/readme.md", "read"],
    ]);
  });

  it("collects touched files from existing transcript tool-call spellings", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-spellings");
    mockVisibleMessages([
      {
        role: "assistant",
        content: [
          { type: "tool_use", name: "read", input: { path: "src/readme.md" } },
          { type: "toolcall", name: "edit", arguments: { path: "ui/vite.config.ts" } },
          { type: "tool_use", name: "read", args: { path: "ui/chat.ts" } },
          {
            type: "tool_call",
            name: "apply_patch",
            input: {
              input: "*** Begin Patch\n*** Update File: package.json\n*** End Patch\n",
            },
          },
        ],
      },
    ]);

    const payload = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(payload.files.map((file: Record<string, unknown>) => [file.path, file.kind])).toEqual([
      ["package.json", "modified"],
      ["ui/vite.config.ts", "modified"],
      ["src/readme.md", "read"],
      ["ui/chat.ts", "read"],
    ]);
  });

  it("collects changed files from structured apply_patch changes", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-structured-patch");
    mockVisibleMessages([
      assistantToolCall("apply_patch", {
        changes: [
          { path: "ui/chat.ts", kind: "update" },
          { path: "src/readme.md", kind: "delete" },
          { path: "old-name.md", kind: { type: "update", move_path: "package.json" } },
        ],
      }),
    ]);

    const payload = expectOkPayload(
      await invokeSessionFilesHandler("sessions.files.list", {
        sessionKey: "agent:main:main",
      }),
    );

    expect(payload.files.map((file: Record<string, unknown>) => [file.path, file.kind])).toEqual([
      ["old-name.md", "modified"],
      ["package.json", "modified"],
      ["src/readme.md", "modified"],
      ["ui/chat.ts", "modified"],
    ]);
  });

  it("omits transcript paths outside the workspace without hiding missing workspace files", async () => {
    useSqliteSession(hoisted.loadSessionEntry, workspaceRoot, "sess-touched-outside-paths");
    const outsidePath = path.join(os.tmpdir(), `${path.basename(workspaceRoot)}-outside-list.txt`);
    fs.writeFileSync(outsidePath, "outside\n", "utf8");
    mockVisibleMessages(
      [
        outsidePath,
        "../outside.txt",
        "~/.openclaw-external.txt",
        pathToFileURL(outsidePath).href,
        `@${outsidePath}`,
        "..cache/missing.txt",
        "missing.txt",
        "src/readme.md",
      ].map((filePath) => assistantToolCall("read", { path: filePath })),
    );

    try {
      const payload = expectOkPayload(
        await invokeSessionFilesHandler("sessions.files.list", {
          sessionKey: "agent:main:main",
        }),
      );

      expect(payload.files).toHaveLength(3);
      expect(payload.files).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ path: "..cache/missing.txt", missing: true }),
          expect.objectContaining({ path: "missing.txt", missing: true }),
          expect.objectContaining({
            path: "src/readme.md",
            missing: false,
            workspacePath: "src/readme.md",
          }),
        ]),
      );
    } finally {
      fs.rmSync(outsidePath, { force: true });
    }
  });
});
