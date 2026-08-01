import { describe, expect, it } from "vitest";
import {
  GATEWAY_CLIENT_IDS,
  GATEWAY_CLIENT_MODES,
} from "../../packages/gateway-protocol/src/client-info.js";
import {
  WORKER_PROTOCOL_FEATURES,
  WORKER_RPC_SET_VERSION,
} from "../../packages/gateway-protocol/src/schema/worker-admission.js";
import { createWorkerConnection, type WorkerConnectionState } from "./worker-connection.js";

function createIdleConnection() {
  return createWorkerConnection({
    socketPath: "ws://127.0.0.1:1",
    connectParams: {
      minProtocol: 1,
      maxProtocol: 1,
      client: {
        id: GATEWAY_CLIENT_IDS.WORKER,
        version: "listener-isolation-test",
        platform: process.platform,
        mode: GATEWAY_CLIENT_MODES.WORKER,
      },
      role: "worker",
      admission: {
        environmentId: "listener-isolation-test",
        credential: "listener-isolation-credential",
        ownerEpoch: 1,
        rpcSetVersion: WORKER_RPC_SET_VERSION,
        handshake: {
          bundleHash: "a".repeat(64),
          openclawVersion: "listener-isolation-test",
          protocolFeatures: [...WORKER_PROTOCOL_FEATURES],
        },
        sessionId: null,
        runId: null,
      },
    },
  });
}

function installThrowingThenHealthyListeners(connection: ReturnType<typeof createIdleConnection>) {
  let throwingCalls = 0;
  const observed: WorkerConnectionState["kind"][] = [];
  connection.onStateChange(() => {
    throwingCalls += 1;
    throw new Error("induced observer failure");
  });
  connection.onStateChange((state) => {
    observed.push(state.kind);
  });
  return { observed, throwingCalls: () => throwingCalls };
}

describe("WorkerConnection state listener isolation", () => {
  it("settles stop and reaches later listeners when an earlier listener throws", async () => {
    const connection = createIdleConnection();
    const listeners = installThrowingThenHealthyListeners(connection);
    const exit = connection.waitForExit();

    await expect(connection.stop()).resolves.toBeUndefined();
    await expect(exit).resolves.toEqual({ kind: "stopped" });
    await expect(connection.stop()).resolves.toBeUndefined();

    expect(connection.state).toEqual({ kind: "stopped" });
    expect(listeners.throwingCalls()).toBe(1);
    expect(listeners.observed).toEqual(["stopped"]);
  });

  it("settles fencing and reaches later listeners when an earlier listener throws", async () => {
    const connection = createIdleConnection();
    const listeners = installThrowingThenHealthyListeners(connection);

    expect(() => connection.fence("owner-epoch-mismatch")).not.toThrow();
    await expect(connection.waitForExit()).resolves.toEqual({
      kind: "fenced",
      reason: "owner-epoch-mismatch",
    });

    expect(connection.state).toEqual({ kind: "fenced", reason: "owner-epoch-mismatch" });
    expect(listeners.throwingCalls()).toBe(1);
    expect(listeners.observed).toEqual(["fenced"]);
  });
});
