export type DiagnosticArgumentChurnActivity = {
  argumentChurnStartedAt?: number;
  argumentChurnObservationAt?: number;
  argumentChurnRunId?: string;
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
    target.argumentChurnObservationAt = Math.max(
      target.argumentChurnObservationAt ?? 0,
      source.argumentChurnObservationAt ?? 0,
    );
    return;
  }
  if (!sourceIsNewer) {
    return;
  }
  target.argumentChurnStartedAt = source.argumentChurnStartedAt;
  target.argumentChurnObservationAt = source.argumentChurnObservationAt;
  target.argumentChurnRunId = source.argumentChurnRunId;
}

export function clearArgumentChurnActivity(
  activity: DiagnosticArgumentChurnActivity,
  params: { now?: number; runId?: string } = {},
): boolean {
  const cleared = activity.argumentChurnStartedAt !== undefined;
  activity.argumentChurnStartedAt = undefined;
  activity.argumentChurnObservationAt = params.now ?? Date.now();
  activity.argumentChurnRunId = params.runId;
  return cleared;
}
