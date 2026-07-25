package ai.openclaw.app.ui

import ai.openclaw.app.GatewayAgentSummary
import ai.openclaw.app.chat.ChatSessionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SidebarShellLogicTest {
  @Test
  fun adaptiveModeUsesDrawerBelowTheIosParityThreshold() {
    assertEquals(SidebarLayoutMode.Drawer, sidebarLayoutMode(979.99f))
    assertEquals(SidebarLayoutMode.Persistent, sidebarLayoutMode(980f))
  }

  @Test
  fun drawerWidthUsesEightySixPercentUntilCapped() {
    assertEquals(275.2f, sidebarWidthDp(320f), 0.01f)
    assertEquals(340f, sidebarWidthDp(800f), 0.01f)
  }

  @Test
  fun persistentWidthStaysInsideTheIosSidebarBounds() {
    assertEquals(316f, sidebarWidthDp(980f), 0.01f)
    assertEquals(340f, sidebarWidthDp(1600f), 0.01f)
  }

  @Test
  fun persistentContentOwnsTheSpaceBesideTheSidebar() {
    assertEquals(320f, sidebarContentWidthDp(320f), 0.01f)
    assertEquals(664f, sidebarContentWidthDp(980f), 0.01f)
    assertEquals(1260f, sidebarContentWidthDp(1600f), 0.01f)
    assertEquals(980f, sidebarWidthDp(980f) + sidebarContentWidthDp(980f), 0.01f)
    assertEquals(1600f, sidebarWidthDp(1600f) + sidebarContentWidthDp(1600f), 0.01f)
  }

  @Test
  fun drawerTranslationIsStartRelativeAndPersistentContentNeverMoves() {
    assertEquals(300f, sidebarContentTranslationPx(true, false, 300f, rightToLeft = false), 0f)
    assertEquals(-300f, sidebarContentTranslationPx(true, false, 300f, rightToLeft = true), 0f)
    assertEquals(0f, sidebarContentTranslationPx(false, false, 300f, rightToLeft = false), 0f)
    assertEquals(0f, sidebarContentTranslationPx(true, true, 300f, rightToLeft = false), 0f)
  }

  @Test
  fun agentRosterExcludesSystemAgentsAndKeepsTheSelectedAgentFirst() {
    val roster =
      sidebarAgentRoster(
        agents =
          listOf(
            agent("main"),
            agent("system", kind = "system"),
            agent("ops"),
            agent("main"),
          ),
        selectedAgentId = "ops",
      )

    assertEquals("ops", roster.selected?.id)
    assertEquals(listOf("main"), roster.others.map(GatewayAgentSummary::id))
  }

  @Test
  fun emptySelectableAgentRosterHasNoSyntheticSelection() {
    val roster = sidebarAgentRoster(listOf(agent("system", kind = "system")), selectedAgentId = "main")

    assertNull(roster.selected)
    assertEquals(emptyList<String>(), roster.others.map(GatewayAgentSummary::id))
  }

  @Test
  fun recentSessionsExcludeArchivedRowsAndPrioritizePinsThenActivity() {
    val rows =
      sidebarRecentSessions(
        sessions =
          listOf(
            session("old-pinned", activity = 1, pinned = true),
            session("fresh", activity = 30),
            session("archived", activity = 50, archived = true),
            session("fresh-pinned", activity = 20, pinned = true),
          ),
        query = "",
      )

    assertEquals(listOf("fresh-pinned", "old-pinned", "fresh"), rows.map(ChatSessionEntry::key))
  }

  @Test
  fun recentSessionSearchCoversTitleLabelKeyAndOwnerBeforeApplyingLimit() {
    val rows =
      sidebarRecentSessions(
        sessions =
          listOf(
            session("agent:ops:one", activity = 1, displayName = "Release planning", owner = "ops"),
            session("agent:main:two", activity = 2, displayName = "Product notes", owner = "main"),
            session("agent:main:three", activity = 3, label = "Ops handoff", owner = "main"),
          ),
        query = "ops",
        limit = 1,
      )

    assertEquals(listOf("agent:main:three"), rows.map(ChatSessionEntry::key))
  }

  @Test
  fun recentSessionsStayBoundedInsideTheSharedScrollableSidebar() {
    val rows =
      sidebarRecentSessions(
        sessions = (1L..12L).map { activity -> session("session-$activity", activity = activity) },
        query = "",
      )

    assertEquals(8, rows.size)
  }

  private fun agent(
    id: String,
    kind: String? = null,
  ): GatewayAgentSummary =
    GatewayAgentSummary(
      id = id,
      name = id,
      emoji = null,
      kind = kind,
    )

  private fun session(
    key: String,
    activity: Long,
    pinned: Boolean = false,
    archived: Boolean = false,
    displayName: String? = null,
    label: String? = null,
    owner: String? = null,
  ): ChatSessionEntry =
    ChatSessionEntry(
      key = key,
      updatedAtMs = activity,
      lastActivityAt = activity,
      pinned = pinned,
      archived = archived,
      displayName = displayName,
      label = label,
      ownerAgentId = owner,
    )
}
