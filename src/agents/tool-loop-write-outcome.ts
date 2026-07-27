import { isPlainObject } from "../utils.js";

function normalizeWriteDetails(details: Record<string, unknown>): Record<string, unknown> {
  return {
    changed: details.changed,
    created: details.created,
    diff: details.diff,
    firstChangedLine: details.firstChangedLine,
  };
}

export function normalizeWriteToolOutcomeForLoopDetection(
  params: unknown,
  details: Record<string, unknown>,
  text: string,
): Record<string, unknown> | undefined {
  if (!isPlainObject(params)) {
    return undefined;
  }
  const path = typeof params.path === "string" ? params.path : undefined;
  const content = typeof params.content === "string" ? params.content : undefined;
  if (path === undefined || content === undefined) {
    return undefined;
  }
  const successPrefix = "Successfully wrote ";
  const successSuffix = ` bytes to ${path}`;
  if (text.startsWith(successPrefix) && text.endsWith(successSuffix)) {
    const reportedLength = Number(text.slice(successPrefix.length, -successSuffix.length));
    const byteLength = Buffer.byteLength(content, "utf8");
    if (reportedLength !== byteLength && reportedLength !== content.length) {
      return undefined;
    }
    // Built-in write results echo the path in both display text and the unified patch.
    // Churn still requires each argument variant to repeat; one-time fan-out remains progress.
    return { status: "written", byteLength, details: normalizeWriteDetails(details) };
  }
  if (text === `No changes made to ${path}. The file already has identical content.`) {
    return { status: "unchanged", details: normalizeWriteDetails(details) };
  }
  return undefined;
}
