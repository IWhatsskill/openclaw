package ai.openclaw.app.ui

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.ui.design.ClawDesignTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h720dp-420dpi")
class SidebarContentTest {
  @get:Rule
  val composeRule = createComposeRule()

  @After
  fun restoreAutomaticClockAdvancement() {
    composeRule.mainClock.autoAdvance = true
  }

  @Test
  fun searchToggleFocusesTheFieldAndClearsTheQueryWhenClosed() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      ClawDesignTheme(dark = false) {
        OpenClawSidebar(
          agents = emptyList(),
          selectedAgentId = null,
          sessions = emptyList(),
          activeSessionKey = "",
          activeDestination = null,
          connection = GatewayConnectionDisplay(isConnected = false, statusText = "Offline", problem = null),
          showCloseButton = true,
          onClose = {},
          onOpenSettings = {},
          onSelectAgent = {},
          onSelectSession = {},
          onSelectDestination = {},
        )
      }
    }

    composeRule.onNodeWithTag("sidebar-search").assertDoesNotExist()

    composeRule.onNodeWithTag("sidebar-search-toggle").performClick()
    advanceFrame()
    val searchField =
      composeRule
        .onNodeWithTag("sidebar-search")
        .assertIsDisplayed()
        .assertIsFocused()
    searchField.performTextInput("release")
    advanceFrame()
    searchField.assertTextContains("release")

    composeRule.onNodeWithTag("sidebar-search-toggle").performClick()
    advanceFrame()
    composeRule.onNodeWithTag("sidebar-search").assertDoesNotExist()

    composeRule.onNodeWithTag("sidebar-search-toggle").performClick()
    advanceFrame()
    val reopenedSearchField =
      composeRule
        .onNodeWithTag("sidebar-search")
        .assertIsDisplayed()
        .assertIsFocused()
    assertEquals("", reopenedSearchField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
  }

  private fun advanceFrame() {
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }
}
