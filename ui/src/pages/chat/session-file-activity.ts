import { gatewayOriginScope } from "@openclaw/gateway-client/browser";
import type { SessionWorkspaceFileEntry } from "../../api/types.ts";
import { getSafeLocalStorage } from "../../local-storage.ts";

const STORAGE_KEY_PREFIX = "openclaw.control.session-file-activity.v1:";
const MAX_ACTIVITY_SCOPES = 50;
const MAX_ACTIVITY_FILES_PER_SCOPE = 500;

export type SessionFileActivityFilter = "new" | "open" | "resolved" | "all";
export type SessionFileActivityStatus = "new" | "read" | "resolved";

export type SessionFileActivityContext = {
  gatewayUrl?: string | null;
  agentId: string;
  sessionKey: string;
  activityScope?: string;
};

type PersistedFileActivity = {
  readRevision?: number;
  resolvedRevision?: number;
};

type PersistedActivityScope = {
  updatedAt: number;
  files: Record<string, PersistedFileActivity>;
};

type PersistedActivityStore = {
  version: 1;
  scopes: Record<string, PersistedActivityScope>;
};

export type SessionFileActivitySnapshot = {
  statusByFile: ReadonlyMap<string, SessionFileActivityStatus>;
  newCount: number;
  openCount: number;
  resolvedCount: number;
};

const memoryStores = new Map<string, PersistedActivityStore>();
const volatileStoreSnapshots = new Map<string, string | null | undefined>();
const persistedStoreSnapshots = new Map<string, string | null>();

function emptyActivityStore(): PersistedActivityStore {
  return { version: 1, scopes: {} };
}

function objectRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function activityRevision(value: unknown): number | undefined {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}

function normalizeFileActivity(value: unknown): PersistedFileActivity | null {
  const record = objectRecord(value);
  if (!record) {
    return null;
  }
  const readRevision = activityRevision(record.readRevision);
  const resolvedRevision = activityRevision(record.resolvedRevision);
  if (readRevision === undefined && resolvedRevision === undefined) {
    return null;
  }
  return {
    ...(readRevision === undefined ? {} : { readRevision }),
    ...(resolvedRevision === undefined ? {} : { resolvedRevision }),
  };
}

function isActivityStoreEnvelope(value: unknown): boolean {
  const record = objectRecord(value);
  return record?.version === 1 && objectRecord(record.scopes) !== null;
}

function normalizeActivityStore(value: unknown): PersistedActivityStore {
  const record = objectRecord(value);
  const rawScopes = objectRecord(record?.scopes);
  if (record?.version !== 1 || !rawScopes) {
    return emptyActivityStore();
  }
  const scopes: Record<string, PersistedActivityScope> = {};
  for (const [scopeKey, rawScope] of Object.entries(rawScopes).slice(-MAX_ACTIVITY_SCOPES)) {
    if (!scopeKey || scopeKey.length > 512) {
      continue;
    }
    const scope = objectRecord(rawScope);
    if (!scope) {
      continue;
    }
    const rawFiles = objectRecord(scope.files);
    if (!rawFiles) {
      continue;
    }
    const files: Record<string, PersistedFileActivity> = {};
    for (const [fileKey, rawFile] of Object.entries(rawFiles).slice(
      -MAX_ACTIVITY_FILES_PER_SCOPE,
    )) {
      const file = normalizeFileActivity(rawFile);
      if (fileKey && fileKey.length <= 1024 && file) {
        files[fileKey] = file;
      }
    }
    scopes[scopeKey] = {
      updatedAt:
        typeof scope.updatedAt === "number" && Number.isFinite(scope.updatedAt)
          ? scope.updatedAt
          : 0,
      files,
    };
  }
  return { version: 1, scopes };
}

function storageKey(context: SessionFileActivityContext): string {
  return `${STORAGE_KEY_PREFIX}${gatewayOriginScope(context.gatewayUrl ?? "")}`;
}

function activityScopeKey(context: SessionFileActivityContext): string {
  const agentId = context.agentId.trim() || "main";
  const serverScope = context.activityScope?.trim();
  const sessionScope = serverScope || `legacy:${context.sessionKey.trim()}`;
  return `${agentId}:${sessionScope}`;
}

function activityFileKey(file: SessionWorkspaceFileEntry): string {
  const activityId = file.activityId?.trim();
  return activityId ? `id:${activityId}` : `path:${file.path}`;
}

function fileRevision(file: SessionWorkspaceFileEntry): number {
  return activityRevision(file.activityRevision) ?? activityRevision(file.updatedAtMs) ?? 1;
}

function readActivityStore(key: string): PersistedActivityStore {
  const volatileFallback = memoryStores.get(key) ?? emptyActivityStore();
  const storage = getSafeLocalStorage();
  if (!storage) {
    return volatileFallback;
  }
  const volatile = volatileStoreSnapshots.has(key);
  const volatileSnapshot = volatileStoreSnapshots.get(key);
  let raw: string | null;
  try {
    raw = storage.getItem(key);
  } catch {
    // Storage access can be rejected while the current tab's in-memory state
    // remains valid, so preserve that fallback only for this failure class.
    return volatileFallback;
  }
  if (volatile && volatileSnapshot !== undefined && raw === volatileSnapshot) {
    persistedStoreSnapshots.set(key, raw);
    return volatileFallback;
  }
  if (!raw) {
    if (volatile && volatileSnapshot === undefined) {
      persistedStoreSnapshots.set(key, raw);
      volatileStoreSnapshots.set(key, raw);
      return volatileFallback;
    }
    volatileStoreSnapshots.delete(key);
    memoryStores.delete(key);
    persistedStoreSnapshots.set(key, raw);
    return emptyActivityStore();
  }
  try {
    const rawStore = JSON.parse(raw) as unknown;
    if (!isActivityStoreEnvelope(rawStore)) {
      volatileStoreSnapshots.delete(key);
      memoryStores.delete(key);
      persistedStoreSnapshots.set(key, raw);
      return emptyActivityStore();
    }
    const parsed = normalizeActivityStore(rawStore);
    if (volatile && volatileSnapshot === undefined) {
      persistedStoreSnapshots.set(key, raw);
      volatileStoreSnapshots.set(key, raw);
      return volatileFallback;
    }
    volatileStoreSnapshots.delete(key);
    memoryStores.set(key, parsed);
    persistedStoreSnapshots.set(key, raw);
    return parsed;
  } catch {
    // Persisted corruption must not revive stale Read or Resolved markers.
    volatileStoreSnapshots.delete(key);
    memoryStores.delete(key);
    persistedStoreSnapshots.set(key, raw);
    return emptyActivityStore();
  }
}

function writeActivityStore(key: string, store: PersistedActivityStore): void {
  memoryStores.set(key, store);
  const serializedStore = JSON.stringify(store);
  const storedSnapshot = persistedStoreSnapshots.get(key);
  const storage = getSafeLocalStorage();
  if (!storage) {
    volatileStoreSnapshots.set(key, storedSnapshot);
    return;
  }
  let persistedBeforeWrite: string | null | undefined;
  try {
    persistedBeforeWrite = storage.getItem(key);
    persistedStoreSnapshots.set(key, persistedBeforeWrite);
    storage.setItem(key, serializedStore);
    volatileStoreSnapshots.delete(key);
    persistedStoreSnapshots.set(key, serializedStore);
  } catch {
    // Browser storage is optional; the in-memory copy still keeps this tab coherent.
    volatileStoreSnapshots.set(
      key,
      persistedBeforeWrite !== undefined ? persistedBeforeWrite : storedSnapshot,
    );
  }
}

function updateActivityScope(
  context: SessionFileActivityContext,
  update: (files: Record<string, PersistedFileActivity>) => void,
): void {
  const key = storageKey(context);
  const store = readActivityStore(key);
  const scopeKey = activityScopeKey(context);
  const current = store.scopes[scopeKey];
  const files = { ...current?.files };
  update(files);
  const boundedFiles = Object.fromEntries(
    Object.entries(files).slice(-MAX_ACTIVITY_FILES_PER_SCOPE),
  );
  delete store.scopes[scopeKey];
  store.scopes[scopeKey] = { updatedAt: Date.now(), files: boundedFiles };
  store.scopes = Object.fromEntries(Object.entries(store.scopes).slice(-MAX_ACTIVITY_SCOPES));
  writeActivityStore(key, store);
}

function classifyFileActivity(
  marker: PersistedFileActivity | undefined,
  revision: number,
): SessionFileActivityStatus {
  if (marker?.resolvedRevision === revision) {
    return "resolved";
  }
  if (marker?.readRevision === revision) {
    return "read";
  }
  return "new";
}

export function readSessionFileActivity(
  context: SessionFileActivityContext,
  files: readonly SessionWorkspaceFileEntry[],
): SessionFileActivitySnapshot {
  const store = readActivityStore(storageKey(context));
  const markers = store.scopes[activityScopeKey(context)]?.files ?? {};
  const statusByFile = new Map<string, SessionFileActivityStatus>();
  let newCount = 0;
  let openCount = 0;
  let resolvedCount = 0;
  for (const file of files) {
    if (file.kind !== "modified") {
      continue;
    }
    const key = activityFileKey(file);
    const status = classifyFileActivity(markers[key], fileRevision(file));
    statusByFile.set(key, status);
    if (status === "resolved") {
      resolvedCount += 1;
    } else {
      openCount += 1;
      if (status === "new") {
        newCount += 1;
      }
    }
  }
  return { statusByFile, newCount, openCount, resolvedCount };
}

export function sessionFileActivityStatus(
  snapshot: SessionFileActivitySnapshot,
  file: SessionWorkspaceFileEntry,
): SessionFileActivityStatus {
  return file.kind === "modified"
    ? (snapshot.statusByFile.get(activityFileKey(file)) ?? "new")
    : "read";
}

export function sessionFileMatchesActivityFilter(
  status: SessionFileActivityStatus,
  filter: SessionFileActivityFilter,
): boolean {
  if (filter === "all") {
    return true;
  }
  if (filter === "open") {
    return status !== "resolved";
  }
  return status === filter;
}

export function markSessionFileRead(
  context: SessionFileActivityContext,
  file: SessionWorkspaceFileEntry,
): void {
  if (file.kind !== "modified") {
    return;
  }
  updateActivityScope(context, (files) => {
    const key = activityFileKey(file);
    const marker = files[key] ?? {};
    delete files[key];
    files[key] = { ...marker, readRevision: fileRevision(file) };
  });
}

export function markAllSessionFilesRead(
  context: SessionFileActivityContext,
  filesToMark: readonly SessionWorkspaceFileEntry[],
): void {
  const modifiedFiles = filesToMark.filter((file) => file.kind === "modified");
  if (modifiedFiles.length === 0) {
    return;
  }
  updateActivityScope(context, (files) => {
    for (const file of modifiedFiles) {
      const key = activityFileKey(file);
      const marker = files[key] ?? {};
      delete files[key];
      files[key] = { ...marker, readRevision: fileRevision(file) };
    }
  });
}

export function setSessionFileResolved(
  context: SessionFileActivityContext,
  file: SessionWorkspaceFileEntry,
  resolved: boolean,
): void {
  if (file.kind !== "modified") {
    return;
  }
  updateActivityScope(context, (files) => {
    const key = activityFileKey(file);
    const marker = files[key] ?? {};
    delete files[key];
    if (resolved) {
      const revision = fileRevision(file);
      files[key] = { readRevision: revision, resolvedRevision: revision };
      return;
    }
    const next = { ...marker };
    delete next.resolvedRevision;
    if (next.readRevision !== undefined) {
      files[key] = next;
    }
  });
}
