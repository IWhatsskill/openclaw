import type { OpenClawConfig } from "../config/config.js";

export function createSearchProviderOption(overrides: Record<string, unknown>) {
  return overrides;
}

export function createEnabledWebSearchConfig(
  provider: string,
  pluginEntry: Record<string, unknown>,
) {
  return (cfg: OpenClawConfig) => ({
    ...cfg,
    tools: {
      ...cfg.tools,
      web: {
        ...cfg.tools?.web,
        search: {
          provider,
          enabled: true,
        },
      },
    },
    plugins: {
      ...cfg.plugins,
      entries: {
        ...cfg.plugins?.entries,
        [provider]: pluginEntry,
      },
    },
  });
}
