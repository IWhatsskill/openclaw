package ai.openclaw.app.ui

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.ui.design.ClawDesignTheme
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
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

  @Before
  fun disableAnimations() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
  }

  @Test
  fun searchToggleFocusesTheFieldAndClearsTheQueryWhenClosed() {
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
    val searchField =
      composeRule
        .onNodeWithTag("sidebar-search")
        .assertIsDisplayed()
        .assertIsFocused()
    searchField.performTextInput("release")
    searchField.assertTextContains("release")

    composeRule.onNodeWithTag("sidebar-search-toggle").performClick()
    composeRule.onNodeWithTag("sidebar-search").assertDoesNotExist()

    composeRule.onNodeWithTag("sidebar-search-toggle").performClick()
    val reopenedSearchField =
      composeRule
        .onNodeWithTag("sidebar-search")
        .assertIsDisplayed()
        .assertIsFocused()
    assertEquals("", reopenedSearchField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
  }
}
