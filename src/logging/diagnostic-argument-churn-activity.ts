export type DiagnosticArgumentChurnActivity = {
  argumentChurnStartedAt?: number;
  argumentChurnObservationAt?: number;
  argumentChurnRunId?: string;
  argumentChurnSuspended?: boolean;
};

export type DiagnosticArgumentChurnObservationParams = {
  sessionId?: string;
  sessionKey?: string;
  runId?: string;
  active?: boolean;
  existingOnly?: boolean;
  policyWait?: boolean;
  now?: number;
};

export function resolveCurrentArgumentChurnOwner<T extends { sequence: number }>(
  owners: Iterable<T>,
): T | undefined {
  let currentOwner: T | undefined;
  for (const owner of owners) {
    if (!currentOwner || owner.sequence > currentOwner.sequence) {
      currentOwner = owner;
    }
  }
  return currentOwner;
}

export function resolveArgumentChurnProgress<T extends { runId: string; sequence: number }>(
  activity: DiagnosticArgumentChurnActivity & {
    lastProgressAt: number;
    lastProgressReason?: string;
  },
  owners: Iterable<T>,
  now: number,
): { lastProgressAt: number; lastProgressReason?: string } {
  const startedAt = activity.argumentChurnStartedAt;
  const belongsToOwner =
    startedAt !== undefined &&
    resolveCurrentArgumentChurnOwner(owners)?.runId === activity.argumentChurnRunId;
  if (!belongsToOwner) {
    return {
      lastProgressAt: activity.lastProgressAt,
      lastProgressReason: activity.lastProgressReason,
    };
  }
  if (activity.argumentChurnSuspended === true) {
    return { lastProgressAt: now, lastProgressReason: "tool_policy:pending" };
  }
  return {
    lastProgressAt: startedAt,
    lastProgressReason: "tool_loop:argument_churn",
  };
}

export function recordArgumentChurnActivityObservation(
  activity: DiagnosticArgumentChurnActivity,
  params: {
    runId?: string;
    active: boolean;
    existingOnly?: boolean;
    now: number;
  },
): void {
  if (
    params.existingOnly &&
    (activity.argumentChurnStartedAt === undefined || activity.argumentChurnRunId !== params.runId)
  ) {
    return;
  }
  if (!params.active) {
    if (activity.argumentChurnRunId === params.runId) {
      clearArgumentChurnActivity(activity, params);
    }
    return;
  }
  if (
    activity.argumentChurnStartedAt === undefined ||
    activity.argumentChurnRunId !== params.runId
  ) {
    activity.argumentChurnStartedAt = params.now;
    activity.argumentChurnRunId = params.runId;
  }
  activity.argumentChurnObservationAt = params.now;
  activity.argumentChurnSuspended = false;
}

export function suspendArgumentChurnActivity(
  activity: DiagnosticArgumentChurnActivity,
  params: { now: number; runId?: string },
): boolean {
  if (
    activity.argumentChurnStartedAt === undefined ||
    activity.argumentChurnRunId !== params.runId
  ) {
    return false;
  }
  activity.argumentChurnObservationAt = params.now;
  activity.argumentChurnSuspended = true;
  return true;
}

export function applyArgumentChurnObservation<T extends { runId: string; sequence: number }>(
  activity: DiagnosticArgumentChurnActivity,
  owners: Iterable<T>,
  params: DiagnosticArgumentChurnObservationParams,
): void {
  const now = params.now ?? Date.now();
  const runId = params.runId?.trim() || undefined;
  const currentOwnerRunId = resolveCurrentArgumentChurnOwner(owners)?.runId;
  if (currentOwnerRunId !== undefined && currentOwnerRunId !== runId) {
    return;
  }
  if (params.policyWait === true) {
    suspendArgumentChurnActivity(activity, { runId, now });
    return;
  }
  recordArgumentChurnActivityObservation(activity, {
    runId,
    active: params.active === true,
    existingOnly: params.existingOnly,
    now,
  });
}

export function mergeArgumentChurnActivity(
  target: DiagnosticArgumentChurnActivity,
  source: DiagnosticArgumentChurnActivity,
): void {
  const sourceClearsAtSameTime =
    source.argumentChurnObservationAt !== undefined &&
    source.argumentChurnObservationAt === target.argumentChurnObservationAt &&
    source.argumentChurnStartedAt === undefined &&
    target.argumentChurnStartedAt !== undefined;
  const sourceIsNewer =
    source.argumentChurnObservationAt !== undefined &&
    (target.argumentChurnObservationAt === undefined ||
      source.argumentChurnObservationAt > target.argumentChurnObservationAt ||
      sourceClearsAtSameTime);

  if (
    source.argumentChurnStartedAt !== undefined &&
    source.argumentChurnRunId === target.argumentChurnRunId &&
    target.argumentChurnStartedAt !== undefined
  ) {
    target.argumentChurnStartedAt = Math.min(
      target.argumentChurnStartedAt,
      source.argumentChurnStartedAt,
    );
    if (
      source.argumentChurnObservationAt !== undefined &&
      source.argumentChurnObservationAt >= (target.argumentChurnObservationAt ?? 0)
    ) {
      target.argumentChurnObservationAt = source.argumentChurnObservationAt;
      target.argumentChurnSuspended = source.argumentChurnSuspended;
    }
    return;
  }
  if (!sourceIsNewer) {
    return;
  }
  target.argumentChurnStartedAt = source.argumentChurnStartedAt;
  target.argumentChurnObservationAt = source.argumentChurnObservationAt;
  target.argumentChurnRunId = source.argumentChurnRunId;
  target.argumentChurnSuspended = source.argumentChurnSuspended;
}

export function clearArgumentChurnActivity(
  activity: DiagnosticArgumentChurnActivity,
  params: { now?: number; runId?: string } = {},
): boolean {
  const cleared = activity.argumentChurnStartedAt !== undefined;
  activity.argumentChurnStartedAt = undefined;
  activity.argumentChurnObservationAt = params.now ?? Date.now();
  activity.argumentChurnRunId = params.runId;
  activity.argumentChurnSuspended = undefined;
  return cleared;
}
