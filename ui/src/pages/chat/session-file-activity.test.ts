import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SessionWorkspaceFileEntry } from "../../api/types.ts";
import {
  markAllSessionFilesRead,
  markSessionFileRead,
  readSessionFileActivity,
  sessionFileActivityStatus,
  sessionFileMatchesActivityFilter,
  setSessionFileResolved,
  type SessionFileActivityContext,
} from "./session-file-activity.ts";

const context: SessionFileActivityContext = {
  gatewayUrl: "wss://gateway-a.example",
  agentId: "main",
  sessionKey: "agent:main:current",
  activityScope: "a".repeat(64),
};

function modifiedFile(
  activityRevision: number,
  path = "src/app.ts",
  activityId = "b".repeat(64),
): SessionWorkspaceFileEntry {
  return {
    path,
    name: path.split("/").at(-1) ?? path,
    kind: "modified",
    missing: false,
    activityId,
    activityRevision,
  };
}

function status(targetContext: SessionFileActivityContext, file: SessionWorkspaceFileEntry) {
  const snapshot = readSessionFileActivity(targetContext, [file]);
  return {
    snapshot,
    status: sessionFileActivityStatus(snapshot, file),
  };
}

describe("session file activity", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("moves one revision through new, read, resolved, and reopened states", () => {
    const file = modifiedFile(7);

    expect(status(context, file)).toMatchObject({
      status: "new",
      snapshot: { newCount: 1, openCount: 1, resolvedCount: 0 },
    });

    markSessionFileRead(context, file);
    expect(status(context, file)).toMatchObject({
      status: "read",
      snapshot: { newCount: 0, openCount: 1, resolvedCount: 0 },
    });

    setSessionFileResolved(context, file, true);
    expect(status(context, file)).toMatchObject({
      status: "resolved",
      snapshot: { newCount: 0, openCount: 0, resolvedCount: 1 },
    });

    setSessionFileResolved(context, file, false);
    expect(status(context, file)).toMatchObject({
      status: "read",
      snapshot: { newCount: 0, openCount: 1, resolvedCount: 0 },
    });
  });

  it("reopens a resolved file as new when its transcript revision changes", () => {
    const firstRevision = modifiedFile(7);
    setSessionFileResolved(context, firstRevision, true);

    const nextRevision = modifiedFile(11);
    expect(status(context, nextRevision)).toMatchObject({
      status: "new",
      snapshot: { newCount: 1, openCount: 1, resolvedCount: 0 },
    });
  });

  it("marks every current modified file as read without changing referenced files", () => {
    const first = modifiedFile(2, "src/app.ts", "b".repeat(64));
    const second = modifiedFile(5, "src/other.ts", "c".repeat(64));
    const referenced: SessionWorkspaceFileEntry = {
      path: "README.md",
      name: "README.md",
      kind: "read",
      missing: false,
    };

    markAllSessionFilesRead(context, [first, referenced, second]);

    const snapshot = readSessionFileActivity(context, [first, referenced, second]);
    expect(snapshot).toMatchObject({ newCount: 0, openCount: 2, resolvedCount: 0 });
    expect(sessionFileActivityStatus(snapshot, first)).toBe("read");
    expect(sessionFileActivityStatus(snapshot, second)).toBe("read");
  });

  it("isolates acknowledgements by Gateway, agent, and transcript scope", () => {
    const file = modifiedFile(3);
    markSessionFileRead(context, file);

    expect(status(context, file).status).toBe("read");
    expect(status({ ...context, gatewayUrl: "wss://gateway-b.example" }, file).status).toBe("new");
    expect(status({ ...context, agentId: "other" }, file).status).toBe("new");
    expect(status({ ...context, activityScope: "d".repeat(64) }, file).status).toBe("new");
  });

  it("does not retain raw paths when the Gateway supplies activity identities", () => {
    const file = modifiedFile(3, "private/customer-name.txt");

    markSessionFileRead(context, file);

    const persisted = Array.from({ length: localStorage.length }, (_, index) =>
      localStorage.getItem(localStorage.key(index) ?? ""),
    ).join("");
    expect(persisted).not.toContain(file.path);
    expect(persisted).toContain(file.activityId);
  });

  it("treats cleared browser storage as unread instead of restoring stale memory", () => {
    const file = modifiedFile(3);
    markSessionFileRead(context, file);
    expect(status(context, file).status).toBe("read");

    localStorage.clear();

    expect(status(context, file).status).toBe("new");
  });

  it("keeps the current tab coherent when browser storage rejects a write", () => {
    const file = modifiedFile(3);
    const setItem = vi.spyOn(localStorage, "setItem").mockImplementationOnce(() => {
      throw new DOMException("quota exceeded", "QuotaExceededError");
    });

    markSessionFileRead(context, file);

    expect(status(context, file).status).toBe("read");
    setSessionFileResolved(context, file, true);
    expect(status(context, file).status).toBe("resolved");
    expect(setItem).toHaveBeenCalledTimes(2);
    setItem.mockRestore();
  });

  it("uses file timestamps to reopen activity from older Gateways", () => {
    const legacyFile = { ...modifiedFile(3), activityRevision: undefined, updatedAtMs: 10 };
    setSessionFileResolved(context, legacyFile, true);
    expect(status(context, { ...legacyFile, updatedAtMs: 11 }).status).toBe("new");
  });

  it("matches new, open, resolved, and all filters", () => {
    expect(sessionFileMatchesActivityFilter("new", "new")).toBe(true);
    expect(sessionFileMatchesActivityFilter("read", "new")).toBe(false);
    expect(sessionFileMatchesActivityFilter("new", "open")).toBe(true);
    expect(sessionFileMatchesActivityFilter("read", "open")).toBe(true);
    expect(sessionFileMatchesActivityFilter("resolved", "open")).toBe(false);
    expect(sessionFileMatchesActivityFilter("resolved", "resolved")).toBe(true);
    expect(sessionFileMatchesActivityFilter("resolved", "all")).toBe(true);
  });
});
