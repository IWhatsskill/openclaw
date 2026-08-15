import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import {
  listAgentIds,
  resolveAgentDir,
  resolveDefaultAgentDir,
} from "openclaw/plugin-sdk/agent-runtime";
import type { OpenClawConfig } from "openclaw/plugin-sdk/config-contracts";
import {
  resolveCodexAppServerHomeDir,
  resolveCodexAppServerLocalHomeDir,
} from "./app-server/auth-start-options.js";
import {
  resolveCodexAppServerUserHomeDir,
  resolveCodexSupervisionAppServerRuntimeOptions,
  type CodexAppServerRuntimeOptions,
} from "./app-server/config.js";
import {
  buildCodexAppServerConnectionFingerprint,
  replaceCodexCatalogConnectionHomes,
} from "./app-server/plugin-app-cache-key.js";
import { CODEX_LOCAL_SESSION_HOST_ID, MAX_HOST_COUNT } from "./session-catalog-parsing.js";
import type { CodexCatalogHome } from "./session-catalog-types.js";

export type { CodexCatalogHome } from "./session-catalog-types.js";

type CatalogHomeCandidate = {
  codexHome: string;
  agentDir: string;
  label: string;
  usesProcessHomeFallback: boolean;
};

function canonicalCatalogHome(value: string): string {
  const resolved = path.resolve(value);
  try {
    return fs.realpathSync.native(resolved);
  } catch {
    return resolved;
  }
}

function catalogHomeId(codexHome: string): string {
  return createHash("sha256")
    .update("openclaw:codex-session-catalog-home:v1\0")
    .update(codexHome)
    .digest("hex");
}

function appServerForCatalogHome(
  base: CodexAppServerRuntimeOptions,
  codexHome: string,
): CodexAppServerRuntimeOptions {
  return {
    ...base,
    start: {
      ...base.start,
      homeScope: "user",
      env: { ...base.start.env, CODEX_HOME: codexHome },
    },
  };
}

/** Resolves every local Codex store the operator already owns, without path disclosure. */
function resolveCodexCatalogHomes(params: {
  config?: OpenClawConfig;
  pluginConfig?: unknown;
  ownerAgentId?: string;
  ownerAgentDir?: string;
  env?: NodeJS.ProcessEnv;
}): CodexCatalogHome[] {
  const config = params.config ?? {};
  const env = params.env ?? process.env;
  const ownerAgentDir =
    params.ownerAgentDir ??
    (params.ownerAgentId
      ? resolveAgentDir(config, params.ownerAgentId, env)
      : resolveDefaultAgentDir(config, env));
  const base = resolveCodexSupervisionAppServerRuntimeOptions({
    pluginConfig: params.pluginConfig,
    env,
    agentDir: ownerAgentDir,
    config,
  });
  const primaryCodexHome = canonicalCatalogHome(
    resolveCodexAppServerLocalHomeDir(base.start, ownerAgentDir, env),
  );
  const processUserHome = canonicalCatalogHome(resolveCodexAppServerUserHomeDir(env));
  const processHomeConfigured = Boolean(env.CODEX_HOME?.trim());
  const primaryUsesProcessHomeFallback =
    base.start.transport === "stdio" && base.start.homeScope === "user" && !processHomeConfigured;
  const candidates: CatalogHomeCandidate[] = [
    {
      codexHome: primaryCodexHome,
      agentDir: ownerAgentDir,
      label: "Local Codex",
      usesProcessHomeFallback: primaryUsesProcessHomeFallback,
    },
  ];

  if (base.start.transport === "stdio") {
    candidates.push({
      codexHome: processUserHome,
      agentDir: ownerAgentDir,
      label: "Local Codex · user",
      usesProcessHomeFallback: !processHomeConfigured,
    });
    const ownerAgentId = params.ownerAgentId;
    const agentIds = listAgentIds(config).toSorted((left, right) => {
      if (left === ownerAgentId) {
        return -1;
      }
      if (right === ownerAgentId) {
        return 1;
      }
      return left.localeCompare(right);
    });
    for (const agentId of agentIds) {
      const discoveredAgentDir = resolveAgentDir(config, agentId, env);
      const codexHome = canonicalCatalogHome(resolveCodexAppServerHomeDir(discoveredAgentDir));
      if (!fs.existsSync(codexHome)) {
        continue;
      }
      candidates.push({
        codexHome,
        // The route owner remains authoritative even when its catalog includes
        // a Codex store discovered beneath another configured agent directory.
        agentDir: ownerAgentDir,
        label: `Local Codex · ${agentId}`,
        usesProcessHomeFallback: false,
      });
    }
  }

  const seen = new Set<string>();
  const homes: CodexCatalogHome[] = [];
  for (const candidate of candidates) {
    if (seen.has(candidate.codexHome)) {
      continue;
    }
    seen.add(candidate.codexHome);
    const sourceHomeId = catalogHomeId(candidate.codexHome);
    const primary = homes.length === 0;
    homes.push({
      sourceHomeId,
      hostId: primary
        ? CODEX_LOCAL_SESSION_HOST_ID
        : `${CODEX_LOCAL_SESSION_HOST_ID}:${sourceHomeId}`,
      label: candidate.label,
      agentDir: candidate.agentDir,
      appServer: primary ? base : appServerForCatalogHome(base, candidate.codexHome),
      usesProcessHomeFallback: candidate.usesProcessHomeFallback,
    });
    if (homes.length >= MAX_HOST_COUNT) {
      break;
    }
  }
  return homes;
}

type CodexCatalogHomeSnapshot = {
  forAgent(agentId: string): readonly CodexCatalogHome[];
};

/** Discovers Codex homes once at plugin registration and reuses that lifecycle snapshot. */
export function createCodexCatalogHomeSnapshot(params: {
  config?: OpenClawConfig;
  pluginConfig?: unknown;
  env?: NodeJS.ProcessEnv;
}): CodexCatalogHomeSnapshot {
  const config = params.config ?? {};
  const env = params.env ?? process.env;
  const homesByAgent = new Map(
    listAgentIds(config).map((agentId) => [
      agentId,
      resolveCodexCatalogHomes({
        config,
        pluginConfig: params.pluginConfig,
        ownerAgentId: agentId,
        env,
      }),
    ]),
  );
  replaceCodexCatalogConnectionHomes(
    [...homesByAgent.values()].flatMap((homes) =>
      homes
        .filter((home) => home.appServer.start.transport === "stdio")
        .map((home) => ({
          agentDir: home.agentDir,
          fingerprint: buildCodexAppServerConnectionFingerprint(home.appServer, home.agentDir),
          codexHome: resolveCodexAppServerLocalHomeDir(home.appServer.start, home.agentDir, env),
        })),
    ),
  );
  return {
    forAgent: (agentId) => homesByAgent.get(agentId) ?? [],
  };
}
