import { execFileSync, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const SOURCE_PATHS = [
  "extensions/codex/index.ts",
  "extensions/codex/src/jsonl-lines.ts",
  "extensions/codex/src/node-cli-sessions.ts",
  "extensions/codex/src/node-cli-sessions.test.ts",
  "package.json",
  "pnpm-lock.yaml",
  "scripts/run-vitest.mjs",
];

function git(checkout, args, options = {}) {
  return execFileSync("git", ["-C", checkout, ...args], {
    encoding: "utf8",
    ...options,
  }).trim();
}

function requireQuietGit(checkout, args, label) {
  const result = spawnSync("git", ["-C", checkout, ...args], {
    encoding: "utf8",
  });
  if (result.status !== 0) {
    throw new Error(
      `${label} failed with exit ${String(result.status)}: ${result.stderr || result.stdout}`,
    );
  }
}

export function captureSourceIdentity(checkout, expectedHead, checkoutLabel) {
  const resolvedCheckout = fs.realpathSync(checkout);
  const topLevel = fs.realpathSync(git(checkout, ["rev-parse", "--show-toplevel"]));
  if (topLevel !== resolvedCheckout) {
    throw new Error("checkout does not resolve to the repository top level");
  }

  const head = git(checkout, ["rev-parse", "HEAD"]);
  if (head !== expectedHead) {
    throw new Error(`HEAD mismatch: expected ${expectedHead}, got ${head}`);
  }
  const symbolicHead = spawnSync("git", ["-C", checkout, "symbolic-ref", "-q", "--short", "HEAD"], {
    encoding: "utf8",
  });
  if (symbolicHead.status === 0) {
    throw new Error(`proof checkout must be detached, got ${symbolicHead.stdout.trim()}`);
  }
  if (symbolicHead.status !== 1) {
    throw new Error(
      `unable to verify detached HEAD: ${symbolicHead.stderr || symbolicHead.stdout}`,
    );
  }

  const status = git(checkout, ["status", "--porcelain=v1", "--untracked-files=all"]);
  if (status) {
    throw new Error(`proof checkout is not clean: ${status}`);
  }
  requireQuietGit(checkout, ["diff", "--quiet"], "working-tree diff check");
  requireQuietGit(checkout, ["diff", "--cached", "--quiet"], "index diff check");

  const files = SOURCE_PATHS.map((relativePath) => {
    const trackedPath = git(checkout, ["ls-tree", "--name-only", "HEAD", "--", relativePath]);
    if (!trackedPath) {
      return { path: relativePath, present: false };
    }
    if (trackedPath !== relativePath) {
      throw new Error(`unexpected tracked path while inspecting ${relativePath}: ${trackedPath}`);
    }
    const commitBlob = git(checkout, ["rev-parse", `HEAD:${relativePath}`]);
    const worktreeBlob = git(checkout, ["hash-object", relativePath]);
    if (commitBlob !== worktreeBlob) {
      throw new Error(`working source differs from HEAD for ${relativePath}`);
    }
    const bytes = fs.readFileSync(path.join(checkout, relativePath));
    return {
      path: relativePath,
      present: true,
      gitBlob: commitBlob,
      bytes: bytes.length,
      sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
    };
  });

  return {
    checkout: checkoutLabel,
    checkoutMode: "detached-clean-worktree",
    head,
    tree: git(checkout, ["rev-parse", "HEAD^{tree}"]),
    statusPorcelain: "",
    workingTreeDiffExit: 0,
    indexDiffExit: 0,
    sourceFiles: files,
  };
}

export function requireStableSourceIdentity(before, after) {
  if (JSON.stringify(before) !== JSON.stringify(after)) {
    throw new Error("source identity changed during execution");
  }
  return {
    ...before,
    verifiedBeforeAndAfter: true,
  };
}
