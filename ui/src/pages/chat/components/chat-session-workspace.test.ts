import { render } from "lit";
import { describe, expect, it, vi } from "vitest";
import {
  createSessionWorkspaceProps,
  openSessionWorkspaceFile,
  renderSessionWorkspaceRail,
  toggleSessionWorkspace,
  type SessionWorkspaceHost,
} from "./chat-session-workspace.ts";

function gatewayHello(methods: string[], scopes = ["operator.admin"]) {
  return {
    type: "hello-ok" as const,
    protocol: 3,
    auth: { role: "operator", scopes },
    features: { methods },
  };
}

describe("toggleSessionWorkspace", () => {
  it("expands and collapses the session workspace rail", () => {
    const requestUpdate = vi.fn();
    const state = {
      client: null,
      connected: false,
      handleOpenSidebar: vi.fn(),
      hello: null,
      requestUpdate,
      sessionKey: "agent:main:current",
      sessions: {},
    } as unknown as SessionWorkspaceHost;

    expect(createSessionWorkspaceProps(state).collapsed).toBe(true);

    toggleSessionWorkspace(state);

    expect(createSessionWorkspaceProps(state).collapsed).toBe(false);

    toggleSessionWorkspace(state);

    expect(createSessionWorkspaceProps(state).collapsed).toBe(true);
    expect(requestUpdate).toHaveBeenCalledTimes(2);
  });
});

describe("custodian panel toggle", () => {
  it("is available only while the gateway is connected and advertises chat", () => {
    const state = {
      client: null,
      connected: false,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello(["openclaw.chat"]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: {},
    } as unknown as SessionWorkspaceHost;

    expect(createSessionWorkspaceProps(state).onToggleCustodian).toBeUndefined();

    state.connected = true;
    expect(createSessionWorkspaceProps(state).onToggleCustodian).toBeTypeOf("function");
  });
});

describe("session workspace artifacts", () => {
  function createArtifactHost(params: { data: string; mimeType: string; title?: string }) {
    const handleOpenSidebar = vi.fn();
    const request = vi.fn().mockResolvedValue({
      artifact: {
        id: "artifact-1",
        mimeType: params.mimeType,
        title: params.title ?? "Unicode artifact",
      },
      data: params.data,
      encoding: "base64",
    });
    const state = {
      client: { request },
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello([]),
      sessionKey: "agent:main:current",
      sessions: {},
    } as unknown as SessionWorkspaceHost;
    return { handleOpenSidebar, request, state };
  }

  it.each([
    {
      content: "Résumé 東京 🦀",
      fence: "```",
      mimeType: "text/plain",
    },
    {
      content: JSON.stringify({ message: "Résumé 東京 🦀" }),
      fence: "```json",
      mimeType: "application/json",
    },
  ])(
    "decodes UTF-8 $mimeType artifacts without corrupting visible or raw text",
    async (testCase) => {
      const data = btoa(String.fromCharCode(...new TextEncoder().encode(testCase.content)));
      const { handleOpenSidebar, state } = createArtifactHost({
        data,
        mimeType: testCase.mimeType,
      });

      createSessionWorkspaceProps(state).onOpenArtifact("artifact-1");

      await vi.waitFor(() => expect(handleOpenSidebar).toHaveBeenCalledOnce());
      expect(handleOpenSidebar.mock.calls[0]?.[0]).toEqual({
        kind: "markdown",
        content: `# Unicode artifact\n\n${testCase.fence}\n${testCase.content}\n\`\`\``,
        rawText: testCase.content,
      });
    },
  );

  it("preserves inline image artifacts as their original base64 data URLs", async () => {
    const data = "iVBORw0KGgo=";
    const { handleOpenSidebar, state } = createArtifactHost({
      data,
      mimeType: "image/png",
      title: "preview.png",
    });

    createSessionWorkspaceProps(state).onOpenArtifact("artifact-1");

    await vi.waitFor(() => expect(handleOpenSidebar).toHaveBeenCalledOnce());
    expect(handleOpenSidebar.mock.calls[0]?.[0]).toEqual({
      kind: "image",
      mimeType: "image/png",
      rawText: null,
      src: `data:image/png;base64,${data}`,
      title: "preview.png",
    });
  });

  it("reports malformed base64 artifact data as a visible workspace error", async () => {
    const { handleOpenSidebar, state } = createArtifactHost({
      data: "not-base64!",
      mimeType: "text/plain",
    });

    createSessionWorkspaceProps(state).onOpenArtifact("artifact-1");

    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).error).toMatch(/InvalidCharacterError|invalid/i),
    );
    expect(handleOpenSidebar).not.toHaveBeenCalled();
  });
});

describe("session workspace file activity", () => {
  it("acknowledges, resolves, restores, and reopens modified files", async () => {
    localStorage.clear();
    let revision = 3;
    const listFiles = vi.fn().mockImplementation(async () => ({
      sessionKey: "agent:main:current",
      activityScope: "a".repeat(64),
      root: "/workspace",
      files: [
        {
          path: "src/app.ts",
          workspacePath: "src/app.ts",
          name: "app.ts",
          kind: "modified",
          missing: false,
          activityId: "b".repeat(64),
          activityRevision: revision,
        },
      ],
    }));
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      root: "/workspace",
      file: {
        path: "src/app.ts",
        workspacePath: "src/app.ts",
        name: "app.ts",
        kind: "modified",
        missing: false,
        content: "export {};\n",
      },
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(listFiles).toHaveBeenCalledOnce());
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

    let props = createSessionWorkspaceProps(state);
    expect(props.fileActivity).toMatchObject({
      newCount: 1,
      openCount: 1,
      resolvedCount: 0,
    });

    props.onOpenFile("src/app.ts", "session");
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(1);
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());
    props = createSessionWorkspaceProps(state);
    expect(props.fileActivity.newCount).toBe(0);
    await vi.waitFor(() => expect(getFile).toHaveBeenCalledOnce());

    const file = props.list?.files[0];
    expect(file).toBeDefined();
    props.onSetFileResolved(file!, true);
    props = createSessionWorkspaceProps(state);
    expect(props.fileActivity).toMatchObject({
      newCount: 0,
      openCount: 0,
      resolvedCount: 1,
    });

    let container = document.createElement("div");
    render(renderSessionWorkspaceRail(props), container);
    expect(container.querySelector(".chat-workspace-rail__file-badge--resolved")).toBeNull();

    props.onSetFileFilter("all");
    props = createSessionWorkspaceProps(state);
    container = document.createElement("div");
    render(renderSessionWorkspaceRail(props), container);
    expect(container.querySelector(".chat-workspace-rail__file-badge--resolved")).not.toBeNull();

    props.onSetFileResolved(file!, false);
    props = createSessionWorkspaceProps(state);
    expect(props.fileActivity).toMatchObject({
      newCount: 0,
      openCount: 1,
      resolvedCount: 0,
    });

    props.onSetFileResolved(file!, true);
    revision = 8;
    props.onRefresh();
    await vi.waitFor(() => expect(listFiles).toHaveBeenCalledTimes(2));
    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).list?.files[0]?.activityRevision).toBe(8),
    );
    expect(createSessionWorkspaceProps(state).fileActivity).toMatchObject({
      newCount: 1,
      openCount: 1,
      resolvedCount: 0,
    });

    props = createSessionWorkspaceProps(state);
    props.onOpenFile("src/app.ts", "workspace");
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledTimes(2));
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(0);

    revision = 9;
    props.onRefresh();
    await vi.waitFor(() => expect(listFiles).toHaveBeenCalledTimes(3));
    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).list?.files[0]?.activityRevision).toBe(9),
    );
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(1);

    openSessionWorkspaceFile(state, { path: "src/app.ts" });
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledTimes(3));
    expect(createSessionWorkspaceProps(state).fileActivity).toMatchObject({
      newCount: 0,
      openCount: 1,
      resolvedCount: 0,
    });
  });

  it("keeps a modified file New while disconnected or when its preview cannot be opened", async () => {
    localStorage.clear();
    const listFiles = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope: "d".repeat(64),
      files: [
        {
          path: "src/missing.ts",
          name: "missing.ts",
          kind: "modified",
          missing: false,
          activityId: "e".repeat(64),
          activityRevision: 5,
        },
      ],
    });
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      file: {
        path: "src/missing.ts",
        name: "missing.ts",
        kind: "modified",
        missing: false,
      },
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: {
        getFile,
        listFiles,
      },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

    state.connected = false;
    createSessionWorkspaceProps(state).onOpenFile("src/missing.ts", "session");
    expect(getFile).not.toHaveBeenCalled();
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(1);

    state.connected = true;
    createSessionWorkspaceProps(state).onOpenFile("src/missing.ts", "session");
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).error).toBeTruthy());

    expect(state.handleOpenSidebar).not.toHaveBeenCalled();
    expect(createSessionWorkspaceProps(state).fileActivity).toMatchObject({
      newCount: 1,
      openCount: 1,
      resolvedCount: 0,
    });
  });

  it("acknowledges a direct file open before the workspace list loads", async () => {
    localStorage.clear();
    const activityScope = "f".repeat(64);
    const activityFile = {
      path: "src/direct.ts",
      workspacePath: "src/direct.ts",
      name: "direct.ts",
      kind: "modified" as const,
      missing: false,
      activityId: "1".repeat(64),
      activityRevision: 7,
    };
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope,
      root: "/workspace",
      file: {
        ...activityFile,
        content: "export {};\n",
      },
    });
    const listFiles = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope,
      root: "/workspace",
      files: [activityFile],
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "src/direct.ts" });

    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());
    expect(listFiles).not.toHaveBeenCalled();

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(listFiles).toHaveBeenCalledOnce());
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

    expect(createSessionWorkspaceProps(state).fileActivity).toMatchObject({
      newCount: 0,
      openCount: 1,
      resolvedCount: 0,
    });
  });

  it("acknowledges a direct legacy open before the workspace list loads", async () => {
    localStorage.clear();
    const legacyFile = {
      path: "src/direct-legacy.ts",
      workspacePath: "src/direct-legacy.ts",
      name: "direct-legacy.ts",
      kind: "modified" as const,
      missing: false,
      updatedAtMs: 17,
    };
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      root: "/workspace",
      file: { ...legacyFile, content: "export {};\n" },
    });
    const listFiles = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      root: "/workspace",
      files: [legacyFile],
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "src/direct-legacy.ts" });
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());
    expect(listFiles).not.toHaveBeenCalled();

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(0);
  });

  it("acknowledges the revision returned by get when the list changes during an open", async () => {
    localStorage.clear();
    const activityScope = "6".repeat(64);
    const activityId = "7".repeat(64);
    const listedFile = {
      path: "src/racing.ts",
      workspacePath: "src/racing.ts",
      name: "racing.ts",
      kind: "modified" as const,
      missing: false,
      activityId,
      activityRevision: 5,
    };
    const openedFile = { ...listedFile, activityRevision: 9 };
    const listFiles = vi
      .fn()
      .mockResolvedValueOnce({
        sessionKey: "agent:main:current",
        activityScope,
        files: [listedFile],
      })
      .mockResolvedValueOnce({
        sessionKey: "agent:main:current",
        activityScope,
        files: [openedFile],
      });
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope,
      file: { ...openedFile, content: "export {};\n" },
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

    createSessionWorkspaceProps(state).onOpenFile("src/racing.ts", "session");
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());
    expect(createSessionWorkspaceProps(state).list?.files[0]?.activityRevision).toBe(9);
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(0);

    createSessionWorkspaceProps(state).onRefresh();
    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).list?.files[0]?.activityRevision).toBe(9),
    );

    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(0);
  });

  it("keeps a newer refreshed revision New when an older open finishes later", async () => {
    localStorage.clear();
    const activityScope = "d".repeat(64);
    const activityId = "e".repeat(64);
    const listedFile = {
      path: "src/racing.ts",
      workspacePath: "src/racing.ts",
      name: "racing.ts",
      kind: "modified" as const,
      missing: false,
      activityId,
      activityRevision: 5,
    };
    const openedFile = { ...listedFile, activityRevision: 9 };
    const refreshedFile = { ...listedFile, activityRevision: 10 };
    const listFiles = vi
      .fn()
      .mockResolvedValueOnce({
        sessionKey: "agent:main:current",
        activityScope,
        files: [listedFile],
      })
      .mockResolvedValueOnce({
        sessionKey: "agent:main:current",
        activityScope,
        files: [refreshedFile],
      });
    let resolveGet!: (value: unknown) => void;
    const getFile = vi.fn().mockReturnValue(
      new Promise((resolve) => {
        resolveGet = resolve;
      }),
    );
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

    createSessionWorkspaceProps(state).onOpenFile("src/racing.ts", "session");
    await vi.waitFor(() => expect(getFile).toHaveBeenCalledOnce());
    createSessionWorkspaceProps(state).onRefresh();
    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).list?.files[0]?.activityRevision).toBe(10),
    );

    resolveGet({
      sessionKey: "agent:main:current",
      activityScope,
      file: { ...openedFile, content: "export {};\n" },
    });
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());

    expect(createSessionWorkspaceProps(state).list?.files[0]?.activityRevision).toBe(10);
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(1);
  });

  it("acknowledges the strongest modified alias returned by a browser open", async () => {
    localStorage.clear();
    const activityScope = "8".repeat(64);
    const olderAlias = {
      path: "./src/alias.ts",
      workspacePath: "src/alias.ts",
      name: "alias.ts",
      kind: "modified" as const,
      missing: false,
      activityId: "9".repeat(64),
      activityRevision: 5,
    };
    const latestAlias = {
      ...olderAlias,
      path: "src/alias.ts",
      activityId: "a".repeat(64),
      activityRevision: 11,
    };
    const listFiles = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope,
      root: "/workspace",
      files: [olderAlias, latestAlias],
    });
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope,
      root: "/workspace",
      file: { ...latestAlias, content: "export {};\n" },
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

    createSessionWorkspaceProps(state).onOpenFile("src/alias.ts", "workspace");
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());

    const activity = createSessionWorkspaceProps(state).fileActivity;
    expect(activity.statusByFile.get(`id:${olderAlias.activityId}`)).toBe("new");
    expect(activity.statusByFile.get(`id:${latestAlias.activityId}`)).toBe("read");
    expect(activity.newCount).toBe(1);
  });

  it("uses list metadata when an older Gateway omits activity fields from get", async () => {
    localStorage.clear();
    const activityScope = "b".repeat(64);
    const listedFile = {
      path: "src/legacy-get.ts",
      name: "legacy-get.ts",
      kind: "modified" as const,
      missing: false,
      activityId: "c".repeat(64),
      activityRevision: 3,
    };
    const listFiles = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope,
      files: [listedFile],
    });
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      file: {
        ...listedFile,
        activityId: undefined,
        activityRevision: undefined,
        content: "x\n",
      },
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());
    createSessionWorkspaceProps(state).onOpenFile("src/legacy-get.ts", "session");
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());

    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(0);
  });

  it("does not acknowledge a stale list item after the transcript scope changes", async () => {
    localStorage.clear();
    const oldScope = "2".repeat(64);
    const newScope = "3".repeat(64);
    const oldFile = {
      path: "src/replaced.ts",
      name: "replaced.ts",
      kind: "modified" as const,
      missing: false,
      activityId: "4".repeat(64),
      activityRevision: 5,
    };
    const newFile = {
      ...oldFile,
      activityId: "5".repeat(64),
    };
    const listFiles = vi
      .fn()
      .mockResolvedValueOnce({
        sessionKey: "agent:main:current",
        activityScope: oldScope,
        files: [oldFile],
      })
      .mockResolvedValueOnce({
        sessionKey: "agent:main:current",
        activityScope: newScope,
        files: [newFile],
      });
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      activityScope: newScope,
      file: {
        ...newFile,
        content: "export {};\n",
      },
    });
    const state = {
      client: { request: vi.fn().mockResolvedValue({ artifacts: [] }) },
      connected: true,
      handleOpenSidebar: vi.fn(),
      hello: gatewayHello([]),
      requestUpdate: vi.fn(),
      sessionKey: "agent:main:current",
      sessions: { getFile, listFiles },
      settings: { gatewayUrl: "wss://gateway-a.example" },
    } as unknown as SessionWorkspaceHost;

    toggleSessionWorkspace(state);
    await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

    let props = createSessionWorkspaceProps(state);
    expect(props.fileActivity.newCount).toBe(1);
    props.onOpenFile("src/replaced.ts", "session");
    await vi.waitFor(() => expect(state.handleOpenSidebar).toHaveBeenCalledOnce());
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(1);

    props = createSessionWorkspaceProps(state);
    props.onRefresh();
    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).list?.activityScope).toBe(newScope),
    );
    expect(createSessionWorkspaceProps(state).fileActivity.newCount).toBe(1);
  });
});

describe("openSessionWorkspaceFile", () => {
  it("opens Markdown with a canonical Gateway- and pane-scoped draft identity", async () => {
    const handleOpenSidebar = vi.fn();
    const getFile = vi.fn().mockResolvedValue({
      sessionKey: "agent:main:current",
      root: "/workspace",
      file: {
        path: "README.md",
        workspacePath: "README.md",
        name: "README.md",
        kind: "read",
        missing: false,
        content: "# Before\n",
        hash: "a".repeat(64),
      },
    });
    const state = {
      client: {},
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello(["sessions.files.set"]),
      sessionKey: "agent:main:current",
      sessionWorkspaceDraftScope: "pane-left",
      settings: { gatewayUrl: "wss://gateway-a.example" },
      sessions: { getFile },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "readme.md" });

    await vi.waitFor(() => expect(handleOpenSidebar).toHaveBeenCalledOnce());
    expect(handleOpenSidebar.mock.calls[0]?.[0]).toMatchObject({
      kind: "file",
      name: "README.md",
      content: "# Before\n",
      draftKey:
        "wss://gateway-a.example\u0000pane-left\u0000agent:main:current\u0000/workspace\u0000README.md",
      edit: { hash: "a".repeat(64) },
    });
  });

  it.each([
    { label: "the method is not advertised", methods: [], scopes: ["operator.admin"] },
    {
      label: "the connection lacks admin scope",
      methods: ["sessions.files.set"],
      scopes: ["operator.read"],
    },
  ])("keeps Markdown read-only when $label", async ({ methods, scopes }) => {
    const handleOpenSidebar = vi.fn();
    const state = {
      client: {},
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello(methods, scopes),
      sessionKey: "agent:main:current",
      sessions: {
        getFile: vi.fn().mockResolvedValue({
          sessionKey: "agent:main:current",
          file: {
            path: "README.md",
            name: "README.md",
            kind: "read",
            missing: false,
            content: "# Before\n",
            hash: "a".repeat(64),
          },
        }),
      },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "README.md" });

    await vi.waitFor(() => expect(handleOpenSidebar).toHaveBeenCalledOnce());
    expect(handleOpenSidebar.mock.calls[0]?.[0]).toMatchObject({ kind: "file" });
    expect(handleOpenSidebar.mock.calls[0]?.[0]?.edit).toBeUndefined();
  });

  it.each([
    { root: "/workspace", expected: "/workspace/src/readme.md" },
    { root: "C:\\workspace", expected: "C:\\workspace\\src\\readme.md" },
  ])(
    "opens rendered workspace-browser rows beneath $root with the full path",
    async ({ root, expected }) => {
      const getFile = vi.fn().mockResolvedValue({
        sessionKey: "agent:main:current",
        root,
        file: {
          path: expected,
          workspacePath: "src/readme.md",
          name: "readme.md",
          kind: "read",
          missing: false,
          content: "# Browser file\n",
        },
      });
      const listFiles = vi.fn().mockResolvedValue({
        sessionKey: "agent:main:current",
        root,
        files: [],
        browser: {
          path: "",
          entries: [{ kind: "file", name: "readme.md", path: "src/readme.md" }],
        },
      });
      const request = vi.fn().mockResolvedValue({ artifacts: [] });
      const state = {
        client: { request },
        connected: true,
        handleOpenSidebar: vi.fn(),
        hello: gatewayHello([]),
        sessionKey: "agent:main:current",
        sessions: { getFile, listFiles },
      } as unknown as SessionWorkspaceHost;

      toggleSessionWorkspace(state);
      await vi.waitFor(() => expect(listFiles).toHaveBeenCalledOnce());
      await vi.waitFor(() => expect(createSessionWorkspaceProps(state).list).not.toBeNull());

      const container = document.createElement("div");
      render(renderSessionWorkspaceRail(createSessionWorkspaceProps(state)), container);
      const row = container.querySelector<HTMLButtonElement>(
        ".chat-workspace-rail__list--browser .chat-workspace-rail__file-open",
      );
      expect(row).toBeInstanceOf(HTMLButtonElement);
      row!.click();

      await vi.waitFor(() => expect(getFile).toHaveBeenCalledOnce());
      expect(getFile.mock.calls[0]?.[1]).toBe(expected);
    },
  );

  it("opens base64 session images in the existing image sidebar", async () => {
    const handleOpenSidebar = vi.fn();
    const state = {
      client: {},
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello([]),
      sessionKey: "agent:main:current",
      sessions: {
        getFile: vi.fn().mockResolvedValue({
          sessionKey: "agent:main:current",
          file: {
            path: "screenshots/result.png",
            name: "result.png",
            kind: "read",
            missing: false,
            mimeType: "image/png",
            contentEncoding: "base64",
            previewKind: "image",
            content: "iVBORw0KGgo=",
          },
        }),
      },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "screenshots/result.png" });

    await vi.waitFor(() => expect(handleOpenSidebar).toHaveBeenCalledOnce());
    expect(handleOpenSidebar.mock.calls[0]?.[0]).toMatchObject({
      kind: "image",
      mimeType: "image/png",
      src: "data:image/png;base64,iVBORw0KGgo=",
      title: "result.png",
    });
  });

  it.each([
    { label: "a non-allowlisted MIME", mimeType: "image/svg+xml", contentEncoding: "base64" },
    { label: "a non-base64 encoding", mimeType: "image/png", contentEncoding: "utf8" },
  ])("rejects image preview metadata with $label", async ({ mimeType, contentEncoding }) => {
    const handleOpenSidebar = vi.fn();
    const state = {
      client: {},
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello([]),
      sessionKey: "agent:main:current",
      sessions: {
        getFile: vi.fn().mockResolvedValue({
          sessionKey: "agent:main:current",
          file: {
            path: "screenshots/result.png",
            name: "result.png",
            kind: "read",
            missing: false,
            mimeType,
            contentEncoding,
            previewKind: "image",
            content: "iVBORw0KGgo=",
          },
        }),
      },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "screenshots/result.png" });

    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).error).toBe(
        "Failed to load screenshots/result.png",
      ),
    );
    expect(handleOpenSidebar).not.toHaveBeenCalled();
  });

  it("does not render base64 content as text when the preview discriminator disagrees", async () => {
    const handleOpenSidebar = vi.fn();
    const state = {
      client: {},
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello([]),
      sessionKey: "agent:main:current",
      sessions: {
        getFile: vi.fn().mockResolvedValue({
          sessionKey: "agent:main:current",
          file: {
            path: "notes.txt",
            name: "notes.txt",
            kind: "read",
            missing: false,
            contentEncoding: "base64",
            previewKind: "text",
            content: "bm90ZXM=",
          },
        }),
      },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "notes.txt" });

    await vi.waitFor(() =>
      expect(createSessionWorkspaceProps(state).error).toBe("Failed to load notes.txt"),
    );
    expect(handleOpenSidebar).not.toHaveBeenCalled();
  });

  it("opens unsupported session files as metadata without treating bytes as text", async () => {
    const handleOpenSidebar = vi.fn();
    const state = {
      client: {},
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello([]),
      sessionKey: "agent:main:current",
      sessions: {
        getFile: vi.fn().mockResolvedValue({
          sessionKey: "agent:main:current",
          file: {
            path: "build/cache.db",
            name: "cache.db",
            kind: "read",
            missing: false,
            mimeType: "application/x-sqlite3",
            previewKind: "unsupported",
            size: 8192,
            updatedAtMs: 1_700_000_000_000,
          },
        }),
      },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: "build/cache.db" });

    await vi.waitFor(() => expect(handleOpenSidebar).toHaveBeenCalledOnce());
    expect(handleOpenSidebar.mock.calls[0]?.[0]).toMatchObject({ kind: "markdown" });
    const content = handleOpenSidebar.mock.calls[0]?.[0]?.content ?? "";
    expect(content).toContain("This file is not previewable inline.");
    expect(content).toContain("application/x-sqlite3");
    expect(content).toContain("8,192 bytes");
    expect(content).toContain("2023-11-14T22:13:20.000Z");
  });

  it("keeps hostile unsupported filenames literal in metadata Markdown", async () => {
    const handleOpenSidebar = vi.fn();
    const hostilePath = " build/`\n\n![remote](https://example.com/x) report~~old~~&amp;.db ";
    const state = {
      client: {},
      connected: true,
      handleOpenSidebar,
      hello: gatewayHello([]),
      sessionKey: "agent:main:current",
      sessions: {
        getFile: vi.fn().mockResolvedValue({
          sessionKey: "agent:main:current",
          file: {
            path: hostilePath,
            name: "cache.db",
            kind: "read",
            missing: false,
            mimeType: "application/octet-stream",
            previewKind: "unsupported",
            updatedAtMs: Number.MAX_VALUE,
          },
        }),
      },
    } as unknown as SessionWorkspaceHost;

    openSessionWorkspaceFile(state, { path: hostilePath });

    await vi.waitFor(() => expect(handleOpenSidebar).toHaveBeenCalledOnce());
    const content = handleOpenSidebar.mock.calls[0]?.[0]?.content ?? "";
    expect(content).toContain(
      "``  build/`\\n\\n![remote](https://example.com/x) report~~old~~&amp;.db  ``",
    );
    expect(content).not.toContain("\n\n![remote]");
    expect(content).not.toContain("Updated:");
  });
});
