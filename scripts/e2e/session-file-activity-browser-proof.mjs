import { execFileSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repo = process.cwd();
const baseSha = process.env.OPENCLAW_PROOF_BASE_SHA?.trim();
const headSha = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
const proofRoot =
  process.env.OPENCLAW_PROOF_ROOT?.trim() ||
  path.join(os.tmpdir(), "openclaw-session-file-activity-proof", headSha);
const stateDir = path.join(proofRoot, "state");
const workspaceDir = path.join(proofRoot, "workspace");
const configPath = path.join(proofRoot, "openclaw.json");
const controlUiRoot = path.join(repo, "dist", "control-ui");
const token = crypto.randomBytes(24).toString("hex");
const harnessSourcePath = fileURLToPath(import.meta.url);

function source(modulePath) {
  return import(pathToFileURL(path.join(repo, modulePath)).href);
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

assert(baseSha, "OPENCLAW_PROOF_BASE_SHA was not set");

function assistantEdit(id, filePath) {
  return {
    role: "assistant",
    content: [{ type: "toolCall", id, name: "edit", arguments: { path: filePath } }],
    api: "openai-responses",
    provider: "openai",
    model: "proof-model",
    usage: {
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
      totalTokens: 0,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
    },
    stopReason: "toolUse",
    timestamp: Date.now(),
  };
}

function editResult(id, isError = false) {
  return {
    role: "toolResult",
    toolCallId: id,
    toolName: "edit",
    content: [{ type: "text", text: isError ? "edit failed" : "edit applied" }],
    isError,
    timestamp: Date.now(),
  };
}

function applyPatchResult(id, isError = false) {
  return {
    role: "toolResult",
    toolCallId: id,
    toolName: "apply_patch",
    content: [{ type: "text", text: isError ? "apply_patch failed" : "apply_patch applied" }],
    isError,
    timestamp: Date.now(),
  };
}

function appendEdit(manager, id, filePath, isError = false) {
  const callEntryId = manager.appendMessage(assistantEdit(id, filePath));
  manager.appendMessage(editResult(id, isError));
  return callEntryId;
}

async function seedSession(params, modules) {
  const marker = modules.formatSqliteSessionFileMarker({
    agentId: "main",
    sessionId: params.sessionId,
    storePath: params.storePath,
  });
  await modules.upsertSessionEntryCore(
    { agentId: "main", sessionKey: params.sessionKey, storePath: params.storePath },
    {
      sessionFile: marker,
      sessionId: params.sessionId,
      updatedAt: Date.now(),
    },
  );
  const manager = modules.SessionManager.open(
    {
      agentId: "main",
      sessionId: params.sessionId,
      sessionKey: params.sessionKey,
      storePath: params.storePath,
    },
    workspaceDir,
  );
  const firstEntryId = appendEdit(manager, params.callId, params.filePath);
  return { firstEntryId, manager };
}

async function main() {
  await fs.rm(proofRoot, { recursive: true, force: true });
  await fs.mkdir(path.join(workspaceDir, "src"), { recursive: true });
  await fs.writeFile(path.join(workspaceDir, "src", "app.ts"), "export const app = true;\n");
  await fs.writeFile(path.join(workspaceDir, "README.md"), "# Proof\n");
  await fs.writeFile(
    configPath,
    JSON.stringify({
      agents: { defaults: { workspace: workspaceDir } },
      gateway: {
        mode: "local",
        bind: "loopback",
        auth: { mode: "token" },
        controlUi: { enabled: true, root: controlUiRoot },
      },
    }),
  );

  Object.assign(process.env, {
    OPENCLAW_CONFIG_PATH: configPath,
    OPENCLAW_DISABLE_BUNDLED_PLUGINS: "1",
    OPENCLAW_HOME: proofRoot,
    OPENCLAW_SKIP_BROWSER_CONTROL_SERVER: "1",
    OPENCLAW_SKIP_CANVAS_HOST: "1",
    OPENCLAW_SKIP_CHANNELS: "1",
    OPENCLAW_SKIP_CRON: "1",
    OPENCLAW_SKIP_GMAIL_WATCHER: "1",
    OPENCLAW_SKIP_PROVIDERS: "1",
    OPENCLAW_STATE_DIR: stateDir,
  });

  const [
    { formatSqliteSessionFileMarker },
    {
      loadTranscriptEvents,
      replaceTranscriptEvents,
      upsertSessionEntryCore,
      waitForSessionTranscriptProjection,
    },
    { SessionManager },
    { createApplyPatchTool },
    { createTaskRecord },
    { getFreePort },
    { startGatewayServer },
    { chromium },
  ] = await Promise.all([
    source("src/config/sessions/legacy-sqlite-marker.ts"),
    source("src/config/sessions/session-accessor.ts"),
    source("src/agents/sessions/session-manager.ts"),
    source("src/agents/apply-patch.ts"),
    source("src/tasks/task-registry-record-api.ts"),
    source("src/test-utils/ports.ts"),
    source("src/gateway/server.ts"),
    import("playwright"),
  ]);
  const modules = { formatSqliteSessionFileMarker, upsertSessionEntryCore, SessionManager };
  const storePath = path.join(stateDir, "agents", "main", "sessions", "sessions.json");
  const mainKey = "agent:main:main";
  const otherKey = "agent:main:other";
  const mainSession = await seedSession(
    {
      sessionId: "proof-main",
      sessionKey: mainKey,
      storePath,
      callId: "call-main-1",
      filePath: "src/app.ts",
    },
    modules,
  );
  await seedSession(
    {
      sessionId: "proof-other",
      sessionKey: otherKey,
      storePath,
      callId: "call-other-1",
      filePath: "README.md",
    },
    modules,
  );
  const mainManager = mainSession.manager;
  appendEdit(mainManager, "call-main-alias", "./src/app.ts");
  const mainScope = {
    agentId: "main",
    sessionId: "proof-main",
    sessionKey: mainKey,
    storePath,
  };
  const otherScope = {
    agentId: "main",
    sessionId: "proof-other",
    sessionKey: otherKey,
    storePath,
  };
  await Promise.all([
    waitForSessionTranscriptProjection(mainScope),
    waitForSessionTranscriptProjection(otherScope),
  ]);
  const task = createTaskRecord({
    runtime: "cli",
    requesterSessionKey: mainKey,
    task: "Production browser proof task",
    status: "running",
    startedAt: Date.now(),
  });
  assert(task, "active task record was not created");

  let port = await getFreePort();
  while (port === 18789) {
    port = await getFreePort();
  }
  const server = await startGatewayServer(port, {
    auth: { mode: "token", token },
    bind: "loopback",
    controlUiEnabled: true,
    sidecarStartup: "defer",
  });
  const browser = await chromium.launch({
    executablePath:
      process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ||
      "/home/agent/openclaw-work/tools/playwright-browsers/chromium-1228/chrome-linux64/chrome",
    headless: true,
  });
  const context = await browser.newContext({
    locale: "en-US",
    serviceWorkers: "block",
    viewport: { width: 1440, height: 900 },
  });
  const page = await context.newPage();
  const origin = "http://127.0.0.1:" + String(port);
  const wsScope = "ws://127.0.0.1:" + String(port);
  await page.addInitScript(({ key, value }) => sessionStorage.setItem(key, value), {
    key: "openclaw.control.token.v1:" + wsScope,
    value: token,
  });

  try {
    await page.goto(origin + "/chat?session=" + encodeURIComponent(mainKey));
    await page.locator("openclaw-chat-page").waitFor({ timeout: 20_000 });
    await page.waitForFunction(
      (expectedSessionKey) =>
        [...document.querySelectorAll("openclaw-chat-pane")].some(
          (pane) =>
            pane.presented !== false &&
            pane.state?.connected === true &&
            pane.state?.agentsList != null &&
            pane.state?.sessionKey === expectedSessionKey,
        ),
      mainKey,
      { timeout: 20_000 },
    );
    const routeSessionKey = await page.evaluate(() => {
      const element = document.querySelector("openclaw-chat-page");
      return element?.data?.sessionKey;
    });
    assert(routeSessionKey === mainKey, "browser route did not bind the proof session");
    const gatewayFiles = await page.evaluate(async (expectedSessionKey) => {
      const pane = [...document.querySelectorAll("openclaw-chat-pane")].find(
        (candidate) =>
          candidate.presented !== false && candidate.state?.sessionKey === expectedSessionKey,
      );
      const result = await pane?.state?.sessions.listFiles(expectedSessionKey, {
        agentId: "main",
      });
      return {
        activityScope: result?.activityScope,
        files:
          result?.files.map((file) => ({
            activityRevision: file.activityRevision,
            kind: file.kind,
            name: file.name,
            path: file.path,
          })) ?? [],
      };
    }, mainKey);
    assert(
      gatewayFiles.files.filter((file) => file.name === "app.ts").length === 1,
      "production sessions.files.list did not deduplicate workspace-path aliases",
    );
    assert(
      gatewayFiles.files.some((file) => file.name === "app.ts" && file.kind === "modified"),
      "production sessions.files.list did not return the seeded modified file",
    );
    await page.locator(".chat-workspace-toggle").waitFor({ timeout: 20_000 });
    if ((await page.locator(".chat-workspace-toggle").getAttribute("aria-expanded")) !== "true") {
      await page.locator(".chat-workspace-toggle").click();
    }
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    await page
      .locator(".chat-workspace-rail__file", { hasText: "app.ts" })
      .locator(".chat-workspace-rail__file-badge--new")
      .waitFor({ timeout: 20_000 });
    await page.locator(".chat-workspace-rail__collapse-toggle").click();
    await page.locator(".chat-workspace-toggle__badge").waitFor({ timeout: 20_000 });
    assert(
      (await page.locator(".chat-workspace-toggle__badge").textContent())?.trim() === "1",
      "initial file badge was not 1",
    );
    await page.locator(".chat-tasks-toggle__badge").waitFor({ timeout: 20_000 });
    assert(
      (await page.locator(".chat-tasks-toggle__badge").textContent())?.trim() === "1",
      "active task badge was not 1",
    );
    const badgeColors = await page.evaluate(() => {
      const file = document.querySelector(".chat-workspace-toggle__badge");
      const taskBadge = document.querySelector(".chat-tasks-toggle__badge");
      if (!(file instanceof HTMLElement) || !(taskBadge instanceof HTMLElement)) {
        throw new Error("header badges not found");
      }
      return {
        fileBackground: getComputedStyle(file).backgroundColor,
        taskBackground: getComputedStyle(taskBadge).backgroundColor,
      };
    });
    assert(
      badgeColors.fileBackground !== badgeColors.taskBackground,
      "file and task badges use the same semantic color",
    );
    await page.screenshot({
      path: path.join(proofRoot, "01-header-new-and-active-task.png"),
    });

    await page.locator(".chat-workspace-toggle").click();
    let mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.waitFor();

    // Advance the transcript after list but before get. The successful open
    // must acknowledge the newer revision returned by sessions.files.get.
    appendEdit(mainManager, "call-main-race", "src/app.ts");
    await waitForSessionTranscriptProjection(mainScope);
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor({
      timeout: 20_000,
    });

    // Production apply_patch is non-atomic. Its first hunk mutates app.ts and
    // its second hunk fails. The overall error result must still reopen the
    // potentially changed existing file as New.
    const partialPatchId = "call-main-partial-patch";
    const partialPatchInput = `*** Begin Patch
*** Update File: src/app.ts
@@
-export const app = true;
+export const app = "partially changed";
*** Update File: src/missing-for-partial-patch.ts
@@
-missing
+changed
*** End Patch`;
    mainManager.appendMessage({
      role: "assistant",
      content: [
        {
          type: "toolCall",
          id: partialPatchId,
          name: "apply_patch",
          arguments: { input: partialPatchInput },
        },
      ],
      api: "openai-responses",
      provider: "openai",
      model: "proof-model",
      usage: {
        input: 0,
        output: 0,
        cacheRead: 0,
        cacheWrite: 0,
        totalTokens: 0,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      },
      stopReason: "toolUse",
      timestamp: Date.now(),
    });
    let partialPatchFailed = false;
    try {
      await createApplyPatchTool({ cwd: workspaceDir }).execute(
        partialPatchId,
        { input: partialPatchInput },
        undefined,
      );
    } catch {
      partialPatchFailed = true;
    }
    assert(partialPatchFailed, "production partial apply_patch did not fail on its later hunk");
    assert(
      (await fs.readFile(path.join(workspaceDir, "src", "app.ts"), "utf8")).includes(
        '"partially changed"',
      ),
      "production partial apply_patch did not retain its successful early mutation",
    );
    mainManager.appendMessage(applyPatchResult(partialPatchId, true));
    await waitForSessionTranscriptProjection(mainScope);
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.locator(".chat-workspace-rail__file-badge--new").waitFor({ timeout: 20_000 });
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor({ timeout: 20_000 });
    const beforeFailedWriteState = await page.evaluate(async (expectedSessionKey) => {
      const pane = [...document.querySelectorAll("openclaw-chat-pane")].find(
        (candidate) =>
          candidate.presented !== false && candidate.state?.sessionKey === expectedSessionKey,
      );
      const result = await pane?.state?.sessions.listFiles(expectedSessionKey, {
        agentId: "main",
      });
      return {
        activityScope: result?.activityScope,
        files:
          result?.files.map((file) => ({
            activityId: file.activityId,
            activityRevision: file.activityRevision,
            kind: file.kind,
            path: file.path,
          })) ?? [],
        storage: Object.fromEntries(
          Object.entries(localStorage).filter(([key]) =>
            key.startsWith("openclaw.control.session-file-activity.v1:"),
          ),
        ),
      };
    }, mainKey);
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.waitFor({ timeout: 20_000 });
    const failedWriteState = await page.evaluate(async (expectedSessionKey) => {
      const pane = [...document.querySelectorAll("openclaw-chat-pane")].find(
        (candidate) =>
          candidate.presented !== false && candidate.state?.sessionKey === expectedSessionKey,
      );
      const result = await pane?.state?.sessions.listFiles(expectedSessionKey, {
        agentId: "main",
      });
      return {
        activityScope: result?.activityScope,
        badges: [...document.querySelectorAll(".chat-workspace-rail__file-badge")].map(
          (badge) => badge.className,
        ),
        files:
          result?.files.map((file) => ({
            activityId: file.activityId,
            activityRevision: file.activityRevision,
            kind: file.kind,
            path: file.path,
          })) ?? [],
        storage: Object.fromEntries(
          Object.entries(localStorage).filter(([key]) =>
            key.startsWith("openclaw.control.session-file-activity.v1:"),
          ),
        ),
      };
    }, mainKey);
    console.log("BEFORE_FAILED_WRITE_DIAG " + JSON.stringify(beforeFailedWriteState));
    console.log("FAILED_WRITE_DIAG " + JSON.stringify(failedWriteState));
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor({
      timeout: 20_000,
    });

    // A failed write result must not advance activity or reopen a Read file.
    appendEdit(mainManager, "call-main-failed", "src/app.ts", true);
    await waitForSessionTranscriptProjection(mainScope);
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.waitFor({ timeout: 20_000 });
    const afterFailedWriteState = await page.evaluate(async (expectedSessionKey) => {
      const pane = [...document.querySelectorAll("openclaw-chat-pane")].find(
        (candidate) =>
          candidate.presented !== false && candidate.state?.sessionKey === expectedSessionKey,
      );
      const result = await pane?.state?.sessions.listFiles(expectedSessionKey, {
        agentId: "main",
      });
      return {
        activityScope: result?.activityScope,
        badges: [...document.querySelectorAll(".chat-workspace-rail__file-badge")].map(
          (badge) => badge.className,
        ),
        files:
          result?.files.map((file) => ({
            activityId: file.activityId,
            activityRevision: file.activityRevision,
            kind: file.kind,
            path: file.path,
          })) ?? [],
        storage: Object.fromEntries(
          Object.entries(localStorage).filter(([key]) =>
            key.startsWith("openclaw.control.session-file-activity.v1:"),
          ),
        ),
      };
    }, mainKey);
    console.log("AFTER_FAILED_WRITE_DIAG " + JSON.stringify(afterFailedWriteState));
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor({
      timeout: 20_000,
    });

    // Hold a real get response after it has captured revision N, publish a
    // refreshed list at N+1, then release the older response. The list must
    // keep N+1 New instead of regressing it to the opened revision.
    await page.evaluate((expectedSessionKey) => {
      const pane = [...document.querySelectorAll("openclaw-chat-pane")].find(
        (candidate) =>
          candidate.presented !== false && candidate.state?.sessionKey === expectedSessionKey,
      );
      const sessions = pane?.state?.sessions;
      if (!sessions?.getFile) {
        throw new Error("proof session getFile owner was unavailable");
      }
      const originalGetFile = sessions.getFile.bind(sessions);
      let releasePreview;
      const previewGate = new Promise((resolve) => {
        releasePreview = resolve;
      });
      globalThis.openclawProofPreviewCaptured = false;
      globalThis.openclawProofPreviewDelivered = false;
      globalThis.openclawProofReleasePreview = releasePreview;
      sessions.getFile = async (...args) => {
        const result = await originalGetFile(...args);
        globalThis.openclawProofPreviewCaptured = true;
        await previewGate;
        sessions.getFile = originalGetFile;
        globalThis.openclawProofPreviewDelivered = true;
        return result;
      };
    }, mainKey);
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await page.waitForFunction(() => globalThis.openclawProofPreviewCaptured === true);
    appendEdit(mainManager, "call-main-stale-preview", "src/app.ts");
    await waitForSessionTranscriptProjection(mainScope);
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.locator(".chat-workspace-rail__file-badge--new").waitFor({
      timeout: 20_000,
    });
    await page.evaluate(() => globalThis.openclawProofReleasePreview());
    await page.waitForFunction(() => globalThis.openclawProofPreviewDelivered === true);
    await mainRow.locator(".chat-workspace-rail__file-badge--new").waitFor({
      timeout: 20_000,
    });
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor({
      timeout: 20_000,
    });

    // If persistence rejects a write, the in-memory fallback remains current.
    // Once readable storage is then cleared, that explicit reset must win and
    // reopen the file as New instead of preserving the volatile Resolved marker.
    await page.evaluate(() => {
      const originalSetItem = Storage.prototype.setItem;
      Storage.prototype.setItem = function (key, value) {
        if (key.startsWith("openclaw.control.session-file-activity.v1:")) {
          Storage.prototype.setItem = originalSetItem;
          throw new DOMException("quota exceeded", "QuotaExceededError");
        }
        return originalSetItem.call(this, key, value);
      };
    });
    await mainRow.getByRole("button", { name: "Mark resolved", exact: true }).click();
    await mainRow.waitFor({ state: "detached" });
    await page.evaluate(() => {
      const key = Object.keys(localStorage).find((candidate) =>
        candidate.startsWith("openclaw.control.session-file-activity.v1:"),
      );
      if (!key) {
        throw new Error("session file activity storage key was not found after rejected write");
      }
      localStorage.removeItem(key);
    });
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.locator(".chat-workspace-rail__file-badge--new").waitFor({
      timeout: 20_000,
    });
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor({
      timeout: 20_000,
    });
    await mainRow.getByRole("button", { name: "Mark resolved", exact: true }).click();
    await mainRow.waitFor({ state: "detached" });
    await page.getByRole("button", { name: /^All 1$/ }).click();
    const resolvedRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await resolvedRow.locator(".chat-workspace-rail__file-badge--resolved").waitFor();
    await page.screenshot({
      path: path.join(proofRoot, "02-resolved-visible-in-all.png"),
    });

    await page.reload();
    await page.locator(".chat-workspace-toggle").waitFor({ timeout: 20_000 });
    assert(
      (await page.locator(".chat-workspace-toggle__badge").count()) === 0,
      "resolved file returned as unread after reload",
    );
    await page.locator(".chat-workspace-toggle").click();
    assert(
      (await page.locator(".chat-workspace-rail__file", { hasText: "app.ts" }).count()) === 0,
      "resolved file was visible in default Open filter after reload",
    );

    await page.goto(origin + "/chat?session=" + encodeURIComponent(otherKey));
    await page.locator(".chat-workspace-toggle__badge").waitFor({ timeout: 20_000 });
    assert(
      (await page.locator(".chat-workspace-toggle__badge").textContent())?.trim() === "1",
      "other thread did not retain independent unread state",
    );

    await page.goto(origin + "/chat?session=" + encodeURIComponent(mainKey));
    await page.locator(".chat-workspace-toggle").waitFor({ timeout: 20_000 });
    assert(
      (await page.locator(".chat-workspace-toggle__badge").count()) === 0,
      "thread switch lost main-thread acknowledgement",
    );

    // Reuse visible ordinal 2 on a different active branch without rotating
    // the transcript generation. The raw event sequence must reopen the file.
    mainManager.branch(mainSession.firstEntryId);
    appendEdit(mainManager, "call-main-branch", "src/app.ts");
    await waitForSessionTranscriptProjection(mainScope);
    await page.locator(".chat-workspace-toggle").click();
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    await page.locator(".chat-workspace-rail__file-badge--new").waitFor({
      timeout: 20_000,
    });

    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor();
    await mainRow.getByRole("button", { name: "Mark resolved", exact: true }).click();
    await mainRow.waitFor({ state: "detached" });

    // A full transcript replacement rotates activity scope even when the
    // visible events and ordinals are byte-identical.
    const unchangedEvents = await loadTranscriptEvents(mainScope);
    await replaceTranscriptEvents(mainScope, unchangedEvents);
    await waitForSessionTranscriptProjection(mainScope);
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.locator(".chat-workspace-rail__file-badge--new").waitFor({
      timeout: 20_000,
    });
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor();
    await mainRow.getByRole("button", { name: "Mark resolved", exact: true }).click();
    await mainRow.waitFor({ state: "detached" });

    // Malformed persisted data must not resurrect the cached Resolved marker.
    await page.evaluate(() => {
      const key = Object.keys(localStorage).find((candidate) =>
        candidate.startsWith("openclaw.control.session-file-activity.v1:"),
      );
      if (!key) {
        throw new Error("session file activity storage key was not found");
      }
      localStorage.setItem(key, "{not-json");
    });
    await page.reload();
    await page.locator(".chat-workspace-toggle__badge").waitFor({ timeout: 20_000 });
    assert(
      (await page.locator(".chat-workspace-toggle__badge").textContent())?.trim() === "1",
      "malformed persisted activity kept the file hidden",
    );
    await page.locator(".chat-workspace-toggle").click();
    mainRow = page.locator(".chat-workspace-rail__file", { hasText: "app.ts" });
    await mainRow.locator(".chat-workspace-rail__file-open").click();
    await mainRow.locator(".chat-workspace-rail__file-badge--read").waitFor();

    // A real sessions.files.get failure must leave its current activity New.
    const postReplaceManager = SessionManager.open(mainScope, workspaceDir);
    appendEdit(postReplaceManager, "call-main-missing", "src/missing.ts");
    await waitForSessionTranscriptProjection(mainScope);
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    const missingRow = page.locator(".chat-workspace-rail__file", { hasText: "missing.ts" });
    await missingRow.locator(".chat-workspace-rail__file-badge--new").waitFor({
      timeout: 20_000,
    });
    await missingRow.locator(".chat-workspace-rail__file-open").click();
    await page.locator(".chat-workspace-rail__state--error").waitFor({ timeout: 20_000 });
    await page.getByRole("button", { name: "Refresh session workspace", exact: true }).click();
    await page
      .locator(".chat-workspace-rail__file", { hasText: "missing.ts" })
      .locator(".chat-workspace-rail__file-badge--new")
      .waitFor({ timeout: 20_000 });

    await page.setViewportSize({ width: 620, height: 820 });
    await page.locator(".chat-workspace-rail__activity-toolbar").waitFor();
    const toolbarBox = await page.locator(".chat-workspace-rail__activity-toolbar").boundingBox();
    assert(
      toolbarBox && toolbarBox.width <= 620,
      "activity toolbar overflowed the narrow viewport",
    );
    await page.screenshot({
      path: path.join(proofRoot, "03-narrow-reopened-file.png"),
    });
    await page.locator(".chat-workspace-rail__collapse-toggle").click();
    await page
      .locator(".chat-header-session-menu--compact .chat-header-session-menu__trigger")
      .click();
    const compactFileBadge = page.locator(
      'wa-dropdown-item[value="quick:panels:session-files"] .session-menu__sub',
    );
    await compactFileBadge.waitFor({ timeout: 20_000 });
    assert(
      (await compactFileBadge.textContent())?.trim() === "1",
      "failed preview cleared the compact-menu unread file badge",
    );

    const screenshotNames = [
      "01-header-new-and-active-task.png",
      "02-resolved-visible-in-all.png",
      "03-narrow-reopened-file.png",
    ];
    const screenshotDigests = Object.fromEntries(
      await Promise.all(
        screenshotNames.map(async (name) => [
          name,
          crypto
            .createHash("sha256")
            .update(await fs.readFile(path.join(proofRoot, name)))
            .digest("hex"),
        ]),
      ),
    );
    const retainedHarness = path.join(proofRoot, "session-file-activity-browser-proof.mjs");
    await fs.copyFile(harnessSourcePath, retainedHarness);
    const harnessDigest = crypto
      .createHash("sha256")
      .update(await fs.readFile(retainedHarness))
      .digest("hex");
    const summary = {
      status: "PASS",
      headSha,
      fileBadge: "new/read/resolved/branch-reopened/rewrite-reopened",
      authoritativeOpen: "latest-get-revision-acknowledged",
      stalePreview: "newer-list-revision-preserved",
      failedWrite: "activity-revision-preserved",
      partialApplyPatch: "failed-result-reopened-successful-early-hunk",
      volatileStorageRecovery: "cleared-storage-reset-to-new",
      pathAliases: "canonicalized-and-deduplicated",
      failedOpen: "preserved-new",
      malformedStorage: "reset-to-new",
      taskBadge: "live-count-neutral-style",
      reload: "persisted",
      threadSwitch: "isolated",
      narrowLayout: "contained",
      harnessDigest,
      screenshotDigests,
    };
    const proofCommand =
      `env OPENCLAW_PROOF_BASE_SHA=${baseSha} OPENCLAW_PROOF_ENTRYPOINT=src/gateway/server.ts#startGatewayServer ` +
      "pnpm exec tsx scripts/e2e/session-file-activity-browser-proof.mjs";
    const transcript =
      [
        "HEAD " + headSha,
        "COMMAND " + proofCommand,
        "RESULT PASS",
        "Gateway: production startGatewayServer with production sessions.files.list/get and tasks.list",
        "Transcript: production SessionManager SQLite writes for two real sessions",
        "Browser: production Control UI bundle in Chromium",
        "Behavior: new -> read -> resolved -> hidden from Open -> visible in All",
        "Authoritative open: a write between list and get was acknowledged at the get revision",
        "Stale preview: a newer refreshed list revision remained New after an older get response arrived",
        "Failed write: an error tool result did not advance or reopen file activity",
        "Partial apply_patch: a failed result after a successful early production hunk reopened the changed file as New",
        "Storage fallback: clearing readable storage after a rejected persistence write reopened the file as New",
        "Path aliases: syntactic aliases resolved to one canonical activity entry",
        "Recovery: reload and thread switch preserve independent state",
        "Branch: a same-generation active-branch change reused visible ordinal 2 and reopened as New",
        "Generation: production replacement of identical transcript events reopened the same ordinals as New",
        "Storage: malformed persisted activity discarded cached markers and reopened as New",
        "Failed open: production sessions.files.get failure left the missing file New",
        "Task badge: one active production task remained live and used neutral styling",
        "Narrow layout: compact session menu kept the count and the activity toolbar stayed contained",
        "HARNESS session-file-activity-browser-proof.mjs sha256=" + harnessDigest,
        "SCREENSHOTS " + JSON.stringify(screenshotDigests),
      ].join("\n") + "\n";
    const transcriptFile = "direct.log";
    await fs.writeFile(path.join(proofRoot, transcriptFile), transcript);
    const transcriptDigest = crypto.createHash("sha256").update(transcript).digest("hex");
    await fs.writeFile(
      path.join(proofRoot, "direct-result.json"),
      JSON.stringify(
        {
          schema: "pr-agent-direct-proof-result-v1",
          repository: "openclaw/openclaw",
          base_sha: baseSha,
          head_sha: headSha,
          status: "pass",
          exit_code: 0,
          execution_kind: "production-runtime",
          proof_command: proofCommand,
          test_runner: "none",
          runtime_fidelity: {
            production_entrypoint: "src/gateway/server.ts#startGatewayServer",
            test_runner: "none",
            affected_owners: [
              "SQLite visible transcript touched-file fold and revision owner",
              "SQLite visible-transcript cursor generation owner",
              "sessions.files Gateway protocol and list/get owner",
              "Control UI session file activity persistence and rendering owner",
              "task registry/tasks.list active-count owner",
              "Control UI badge styling owner",
            ],
            real_affected_owners: [
              "SQLite visible transcript touched-file fold and revision owner",
              "SQLite visible-transcript cursor generation owner",
              "sessions.files Gateway protocol and list/get owner",
              "Control UI session file activity persistence and rendering owner",
              "task registry/tasks.list active-count owner",
              "Control UI badge styling owner",
            ],
            mocked_affected_owners: [],
            harness_boundaries: [
              "production SessionManager messages seeded without a provider request",
              "production SessionManager branch rewound the active path without replacing the transcript",
              "production replaceTranscriptEvents reused identical events to rotate the transcript generation",
              "production sessions.files.get returned a newer revision, a delayed older revision, and a missing-file failure",
              "production SessionManager paired successful and failed edit tool results",
              "production apply_patch performed an early file mutation before a later hunk failed",
              "production Control UI recovered from a rejected localStorage write followed by explicit storage clearing",
              "production task registry record seeded without executing a background model",
              "headless Chromium driving the production Control UI bundle",
            ],
            fault_injection:
              "write between list/get, delayed older get after newer list, failed edit result, partial production apply_patch failure after an early mutation, rejected storage write followed by clearing, path aliases, same-generation branch rewind, identical transcript replacement, malformed localStorage JSON, missing-file preview, reload, session switch, and 620px viewport",
          },
          transcript_file: transcriptFile,
          transcript_digest: transcriptDigest,
          redaction_status: "pass",
        },
        null,
        2,
      ) + "\n",
    );
    console.log(JSON.stringify(summary));
  } finally {
    await context.close();
    await browser.close();
    await server.close();
  }
}

await main();
