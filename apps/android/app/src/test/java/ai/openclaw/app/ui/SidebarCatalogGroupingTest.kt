package ai.openclaw.app.ui

import ai.openclaw.app.SessionCatalog
import ai.openclaw.app.SessionCatalogEntry
import ai.openclaw.app.SessionCatalogHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarCatalogGroupingTest {
  @Test
  fun groupingKeepsHostsWorkspacesOtherWorkAndRecencyOrder() {
    val hosts =
      sidebarCatalogHosts(
        listOf(
          SessionCatalog(
            id = "codex",
            label = "Codex",
            hosts =
              listOf(
                SessionCatalogHost(
                  catalogId = "codex",
                  hostId = "desktop",
                  label = "Desktop",
                  kind = "node",
                  connected = true,
                  nextCursor = "next",
                  sessions =
                    listOf(
                      entry("older", cwd = "C:\\work\\openclaw", recency = 10.0),
                      entry("newer", cwd = "C:\\work\\openclaw", recency = 20.0),
                      entry("other", cwd = null, recency = 30.0),
                      entry("archived", cwd = "/work/hidden", recency = 40.0, archived = true),
                    ),
                ),
              ),
          ),
        ),
      )

    assertEquals(1, hosts.size)
    val host = hosts.single()
    assertEquals("Desktop", host.label)
    assertTrue(host.connected)
    assertTrue(host.canLoadMore)
    assertEquals(listOf("openclaw", "Other work"), host.workspaces.map(SidebarCatalogWorkspace::label))
    assertEquals(
      listOf("newer", "older"),
      host.workspaces
        .first()
        .sessions
        .map(SessionCatalogEntry::threadId),
    )
    assertNull(host.workspaces.last().path)
    assertFalse(host.workspaces.any { workspace -> workspace.sessions.any(SessionCatalogEntry::archived) })
  }

  @Test
  fun visibleCatalogKeysExcludeArchivedRows() {
    val keys =
      sidebarVisibleCatalogSessionKeys(
        listOf(
          SessionCatalog(
            id = "codex",
            label = "Codex",
            hosts =
              listOf(
                SessionCatalogHost(
                  catalogId = "codex",
                  hostId = "desktop",
                  label = "Desktop",
                  kind = "node",
                  connected = true,
                  sessions =
                    listOf(
                      entry("visible", cwd = "/work/visible", recency = 2.0),
                      entry("archived", cwd = "/work/hidden", recency = 1.0, archived = true),
                    ),
                ),
              ),
          ),
        ),
      )

    assertEquals(setOf("agent:main:visible"), keys)
  }

  @Test
  fun fullyArchivedHostDoesNotLeaveAnEmptyHeading() {
    val hosts =
      sidebarCatalogHosts(
        listOf(
          SessionCatalog(
            id = "codex",
            label = "Codex",
            hosts =
              listOf(
                SessionCatalogHost(
                  catalogId = "codex",
                  hostId = "desktop",
                  label = "Desktop",
                  kind = "node",
                  connected = true,
                  sessions = listOf(entry("archived", cwd = "/work/hidden", recency = 1.0, archived = true)),
                ),
              ),
          ),
        ),
      )

    assertTrue(hosts.isEmpty())
  }

  @Test
  fun fullyArchivedHostKeepsItsRefreshErrorVisible() {
    val hosts =
      sidebarCatalogHosts(
        listOf(
          SessionCatalog(
            id = "codex",
            label = "Codex",
            hosts =
              listOf(
                SessionCatalogHost(
                  catalogId = "codex",
                  hostId = "desktop",
                  label = "Desktop",
                  kind = "node",
                  connected = true,
                  errorText = "Refresh failed",
                  sessions = listOf(entry("archived", cwd = "/work/hidden", recency = 1.0, archived = true)),
                ),
              ),
          ),
        ),
      )

    val host = hosts.single()
    assertTrue(host.workspaces.isEmpty())
    assertEquals("Refresh failed", host.errorText)
    assertFalse(host.canLoadMore)
  }

  @Test
  fun fullyArchivedPageKeepsPaginationForLaterActiveRows() {
    val hosts =
      sidebarCatalogHosts(
        listOf(
          SessionCatalog(
            id = "codex",
            label = "Codex",
            hosts =
              listOf(
                SessionCatalogHost(
                  catalogId = "codex",
                  hostId = "desktop",
                  label = "Desktop",
                  kind = "node",
                  connected = true,
                  nextCursor = "next",
                  sessions = listOf(entry("archived", cwd = "/work/hidden", recency = 1.0, archived = true)),
                ),
              ),
          ),
        ),
      )

    val host = hosts.single()
    assertTrue(host.workspaces.isEmpty())
    assertTrue(host.canLoadMore)
  }

  @Test
  fun catalogSectionsMatchWebVisibilityAndKeepExpansionIndependent() {
    val catalogs =
      listOf(
        SessionCatalog(id = "codex", label = "Codex", hosts = emptyList(), canCreateSession = true),
        SessionCatalog(
          id = "claude",
          label = "Claude Code",
          hosts = listOf(host("claude", sessions = listOf(entry("visible", "/work/claude", 2.0, catalogId = "claude")))),
        ),
        SessionCatalog(id = "pi", label = "Pi", hosts = emptyList()),
        SessionCatalog(id = "catalog-error", label = "Catalog error", hosts = emptyList(), errorText = "Unavailable"),
        SessionCatalog(
          id = "host-error",
          label = "Host error",
          hosts = listOf(host("host-error", errorText = "Unavailable")),
        ),
        SessionCatalog(
          id = "paged",
          label = "Paged",
          hosts = listOf(host("paged", nextCursor = "next")),
        ),
        SessionCatalog(
          id = "archived",
          label = "Archived",
          hosts = listOf(host("archived", sessions = listOf(entry("archived", "/work/hidden", 1.0, archived = true)))),
        ),
      )

    val sections = sidebarCatalogSections(catalogs, expandedCatalogIds = listOf("claude"))

    assertEquals(listOf("codex", "claude", "catalog-error", "host-error", "paged"), sections.map { it.catalog.id })
    assertFalse(sections.first { it.catalog.id == "codex" }.expanded)
    assertTrue(sections.first { it.catalog.id == "claude" }.expanded)
    assertFalse(sections.any { it.catalog.id == "pi" })
    assertFalse(sections.any { it.catalog.id == "archived" })
    assertEquals(
      setOf("codex", "claude"),
      toggleSidebarCatalogExpansion(listOf("claude"), "codex").toSet(),
    )
    assertEquals(emptyList<String>(), toggleSidebarCatalogExpansion(listOf("claude"), "claude"))
  }

  @Test
  fun catalogCreationRequiresAdvertisedCapabilityAndWriteScope() {
    val creatable = SessionCatalog(id = "codex", label = "Codex", hosts = emptyList(), canCreateSession = true)
    val unavailable = creatable.copy(canCreateSession = false)

    assertTrue(sidebarCatalogSessionCreationEnabled(creatable, canMutateSessions = true))
    assertFalse(sidebarCatalogSessionCreationEnabled(creatable, canMutateSessions = false))
    assertFalse(sidebarCatalogSessionCreationEnabled(unavailable, canMutateSessions = true))
  }

  @Test
  fun collapsedCatalogRefreshesWhenSelectedAgentChanges() {
    assertTrue(
      sidebarCatalogRefreshNeeded(
        catalogAgentId = "main",
        selectedAgentId = "jarvis",
        anyCatalogExpanded = false,
        catalogDiscoveryNeeded = false,
      ),
    )
  }

  @Test
  fun collapsedCatalogDoesNotPollWhenOwnerMatches() {
    assertFalse(
      sidebarCatalogRefreshNeeded(
        catalogAgentId = "jarvis",
        selectedAgentId = " jarvis ",
        anyCatalogExpanded = false,
        catalogDiscoveryNeeded = false,
      ),
    )
    assertTrue(
      sidebarCatalogRefreshNeeded(
        catalogAgentId = "jarvis",
        selectedAgentId = "jarvis",
        anyCatalogExpanded = true,
        catalogDiscoveryNeeded = false,
      ),
    )
  }

  @Test
  fun readOnlyCatalogCanOpenAdoptedSessionsButCannotContinueRemoteRows() {
    val adopted = entry("adopted", cwd = "/work/adopted", recency = 2.0)
    val remote = entry("remote", cwd = "/work/remote", recency = 1.0).copy(sessionKey = null)
    val unavailable = remote.copy(canContinue = false)

    assertTrue(sidebarCatalogSessionSelectionEnabled(adopted, canMutateSessions = false))
    assertFalse(sidebarCatalogSessionSelectionEnabled(remote, canMutateSessions = false))
    assertTrue(sidebarCatalogSessionSelectionEnabled(remote, canMutateSessions = true))
    assertFalse(sidebarCatalogSessionSelectionEnabled(unavailable, canMutateSessions = true))
  }

  private fun host(
    catalogId: String,
    sessions: List<SessionCatalogEntry> = emptyList(),
    nextCursor: String? = null,
    errorText: String? = null,
  ): SessionCatalogHost =
    SessionCatalogHost(
      catalogId = catalogId,
      hostId = "desktop",
      label = "Desktop",
      kind = "node",
      connected = true,
      sessions = sessions,
      nextCursor = nextCursor,
      errorText = errorText,
    )

  private fun entry(
    threadId: String,
    cwd: String?,
    recency: Double,
    archived: Boolean = false,
    catalogId: String = "codex",
  ): SessionCatalogEntry =
    SessionCatalogEntry(
      catalogId = catalogId,
      hostId = "desktop",
      threadId = threadId,
      name = threadId,
      cwd = cwd,
      status = "idle",
      recencyAt = recency,
      archived = archived,
      sessionKey = "agent:main:$threadId",
      canContinue = true,
    )
}
