package ai.openclaw.app.ui.chat

import ai.openclaw.app.AndroidScreenshotFixture
import ai.openclaw.app.AndroidScreenshotScene
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
import ai.openclaw.app.NodeRuntimeMode
import ai.openclaw.app.SecurePrefs
import ai.openclaw.app.chat.ChatController
import ai.openclaw.app.ui.design.ClawDesignTheme
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class ChatComposerLayoutTest {
  @get:Rule
  val composeRule = createComposeRule()

  private lateinit var app: NodeApp
  private lateinit var prefs: SecurePrefs
  private lateinit var runtime: NodeRuntime
  private lateinit var controller: ChatController
  private var originalRuntime: NodeRuntime? = null
  private val viewModelStore = ViewModelStore()
  private var originalAnimatorScale: String? = null

  @Before
  fun setUp() {
    app = RuntimeEnvironment.getApplication() as NodeApp
    prefs = SecurePrefs(app, app.getSharedPreferences("chat-composer-${UUID.randomUUID()}", Context.MODE_PRIVATE))
    AndroidScreenshotFixture.configure(AndroidScreenshotScene.Chat)
    runtime = NodeRuntime(app, prefs, NodeRuntimeMode.ScreenshotFixture)
    originalRuntime = app.peekRuntime()
    setApplicationRuntime(runtime)
    controller =
      NodeRuntime::class.java
        .getDeclaredField("chat")
        .apply { isAccessible = true }
        .get(runtime) as ChatController
    originalAnimatorScale = Settings.Global.getString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    setApplicationRuntime(originalRuntime)
    runtime.disconnect()
    AndroidScreenshotFixture.configure(AndroidScreenshotScene.Home)
    Settings.Global.putString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalAnimatorScale)
  }

  @Test
  fun slashSuggestionsKeepEditorAndStopVisibleAndLastSuggestionReachable() {
    showChat()
    val editor = composeRule.onNode(hasSetTextAction())
    editor.performTextReplacement("/")
    editor.assertTextEquals("/")

    assertEditorAndStopVisible()
    val lastSuggestion = composeRule.onNodeWithText("/loop").performScrollTo().assertIsDisplayed()
    assertEditorAndStopVisible()
    lastSuggestion.performClick()
    editor.assertTextEquals("/loop ")
    assertEditorAndStopVisible()
  }

  @Test
  fun normalTextAndShortSuggestionListsKeepComposerVisible() {
    showChat()
    val editor = composeRule.onNode(hasSetTextAction())
    listOf("hello", "/help", "/unknown").forEach { input ->
      editor.performTextReplacement(input)
      editor.assertTextEquals(input)
      assertEditorAndStopVisible()
    }
  }

  @Test
  fun textDraftKeepsDisabledSendWhileAnotherAdmissionIsPending() {
    assertDraftKeepsDisabledSendWhileAdmissionIsPending(text = "Still writing the next message")
  }

  @Test
  fun attachmentOnlyDraftKeepsDisabledSendWhileAnotherAdmissionIsPending() {
    assertDraftKeepsDisabledSendWhileAdmissionIsPending(
      attachment = PendingAttachment(id = "note", fileName = "note.txt", mimeType = "text/plain", base64 = "SGVsbG8="),
    )
  }

  @Test
  fun longProgressPlanKeepsEditorAndStopVisibleAndLastStepReachable() {
    showChat()
    val steps = List(20) { index -> "Step ${index + 1}: verify the Android chat behavior and document the result." }
    val response =
      buildJsonObject {
        put(
          "card",
          buildJsonObject {
            put("sessionKey", JsonPrimitive(controller.sessionKey.value))
            put("revision", JsonPrimitive(1))
            put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
            put(
              "steps",
              buildJsonArray {
                steps.forEachIndexed { index, step ->
                  add(
                    buildJsonObject {
                      put("step", JsonPrimitive(step))
                      put("status", JsonPrimitive(if (index == 0) "in_progress" else "pending"))
                    },
                  )
                }
              },
            )
          },
        )
      }.toString()
    val requestField = ChatController::class.java.getDeclaredField("requestGateway").apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    val request = requestField.get(controller) as suspend (String, String?) -> String
    val progressRequest: suspend (String, String?) -> String = { method, params ->
      if (method == "progressCard.get") response else request(method, params)
    }
    composeRule.runOnIdle {
      requestField.set(controller, progressRequest)
      controller.handleGatewayEvent(
        "progressCard.changed",
        """{"sessionKey":"${controller.sessionKey.value}","revision":1}""",
      )
    }
    composeRule.waitUntil {
      controller.progressCard.value
        ?.steps
        ?.size == steps.size
    }

    assertEditorAndStopVisible()
    if (composeRule.onAllNodesWithContentDescription("Expand progress card").fetchSemanticsNodes().isNotEmpty()) {
      composeRule.onNodeWithContentDescription("Expand progress card").performClick()
    }
    assertEditorAndStopVisible()
    composeRule.onNodeWithText(steps.last()).performScrollTo().assertIsDisplayed()
    assertEditorAndStopVisible()
  }

  @Test
  fun pendingPermissionsStayVisibleAndDisabledUntilTheGatewayAppliesThem() {
    showChat()

    fun permissions(
      mode: String,
      pending: Boolean,
    ) {
      composeRule.runOnIdle {
        controller.handleGatewayEvent(
          "sessions.changed",
          """{"sessionKey":"${controller.sessionKey.value}","session":{"key":"${controller.sessionKey.value}","agentId":"main","permissionMode":"$mode","permissionModePending":$pending}}""",
        )
      }
    }

    permissions("full", pending = false)
    composeRule.onNode(hasSetTextAction()).performClick()
    composeRule.onNodeWithContentDescription("Permissions: Full access").assertIsEnabled().performClick()
    composeRule.onNodeWithText("PERMISSIONS").assertIsDisplayed()

    permissions("read-only", pending = true)
    composeRule.onAllNodesWithText("PERMISSIONS").assertCountEquals(0)
    composeRule.onNodeWithText("Applying permissions…").assertIsDisplayed()
    composeRule
      .onNode(hasContentDescription("Applying permissions", substring = true))
      .assertIsDisplayed()
      .assertIsNotEnabled()

    permissions("read-only", pending = false)
    composeRule.onNodeWithContentDescription("Permissions: Read only").assertIsDisplayed().assertIsEnabled()
  }

  @Test
  fun effortPickerDisplaysEffectiveLevelMissingFromAdvertisedOptions() {
    showChat()
    composeRule.runOnIdle {
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"${controller.sessionKey.value}","session":{"key":"${controller.sessionKey.value}","agentId":"main","thinkingLevel":"ultra","thinkingLevels":[{"id":"off","label":"Off"},{"id":"high","label":"High"},{"id":"xhigh","label":"Xhigh"},{"id":"max","label":"Max"}]}}""",
      )
    }
    composeRule.onNode(hasSetTextAction()).performClick()
    composeRule.onNodeWithTag("chat-composer-thinking").performClick()

    composeRule.onNodeWithText("Ultra").assertIsDisplayed()
    composeRule
      .onNode(hasContentDescription("Effort") and hasText("Ultra"))
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ultra"))
      .performClick()
    composeRule.onAllNodesWithText("Ultra").assertCountEquals(1)
    composeRule.onNodeWithText("High").assertIsEnabled().performClick()
    composeRule.waitUntil {
      controller.errorText.value?.contains("Screenshot fixture does not implement gateway method sessions.patch") == true
    }
    assertTrue(
      controller.errorText.value
        .orEmpty()
        .contains("\"thinkingLevel\":\"high\""),
    )
  }

  @Test
  fun narrowToolbarKeepsModelEffortMicAndPrimaryActionVisible() {
    verifyNarrowToolbar(fontScale = 1f)
  }

  @Test
  fun largeFontNarrowToolbarKeepsModelEffortMicAndPrimaryActionVisible() {
    verifyNarrowToolbar(fontScale = 1.3f)
  }

  private fun verifyNarrowToolbar(fontScale: Float) {
    val viewModel = showChat(viewportWidth = 320.dp, fontScale = fontScale)
    composeRule.runOnIdle {
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"${controller.sessionKey.value}","session":{"key":"${controller.sessionKey.value}","agentId":"main","modelProvider":"openai","model":"gpt-5.2","totalTokens":18420,"contextTokens":200000}}""",
      )
    }
    composeRule.waitUntil {
      viewModel.chatSessions.value.any {
        it.key == controller.sessionKey.value && it.totalTokens == 18_420L && it.contextTokens == 200_000L
      }
    }
    composeRule.onNode(hasSetTextAction()).performClick()
    composeRule.waitForIdle()

    val viewport = composeRule.onNodeWithTag("chat-viewport").getUnclippedBoundsInRoot()
    val model = composeRule.onNodeWithTag("chat-composer-model")
    val context = composeRule.onNode(hasContentDescription("Context ", substring = true)).assertIsDisplayed()
    val controls =
      listOf(
        "model" to model,
        "permissions" to composeRule.onNode(hasContentDescription("Permissions:", substring = true)),
        "context" to context,
        "effort" to composeRule.onNodeWithTag("chat-composer-thinking"),
        "mic" to composeRule.onNodeWithTag("chat-composer-mic"),
        "attachment" to composeRule.onNodeWithContentDescription("Add attachment"),
        "stop" to composeRule.onNodeWithContentDescription("Stop"),
      )
    val controlBounds =
      controls.map { (name, node) ->
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("$name must retain a 48dp touch target: $bounds", bounds.right - bounds.left >= 48.dp)
        assertTrue("$name must retain a 48dp touch target: $bounds", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("$name must stay inside the viewport: $bounds inside $viewport", bounds.left >= viewport.left)
        assertTrue("$name must stay inside the viewport: $bounds inside $viewport", bounds.right <= viewport.right)
        assertTrue("$name must stay inside the viewport: $bounds inside $viewport", bounds.top >= viewport.top)
        assertTrue("$name must stay inside the viewport: $bounds inside $viewport", bounds.bottom <= viewport.bottom)
        node.assertIsDisplayed()
        name to bounds
      }
    controlBounds.forEachIndexed { index, (name, bounds) ->
      controlBounds.drop(index + 1).forEach { (otherName, otherBounds) ->
        assertTrue(
          "$name must not overlap $otherName: $bounds vs $otherBounds",
          bounds.right <= otherBounds.left ||
            otherBounds.right <= bounds.left ||
            bounds.bottom <= otherBounds.top ||
            otherBounds.bottom <= bounds.top,
        )
      }
    }

    val label =
      composeRule.onNode(
        hasAnyAncestor(hasTestTag("chat-composer-model")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
      )
    label.assertIsDisplayed()
    val layouts = mutableListOf<TextLayoutResult>()
    label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
    assertTrue("The model label must paint text, not just its dropdown arrow", layouts.single().getLineEnd(0, visibleEnd = true) > 0)

    model.assertIsEnabled().performTouchInput { click(center) }
    val expectedModel = AndroidScreenshotFixture.models.single()
    composeRule
      .onNode(hasText(expectedModel.name) and hasText(expectedModel.provider.orEmpty()))
      .assertIsDisplayed()
      .assertHasClickAction()
  }

  @Test
  fun primaryActionKeeps48DpTouchTargetAround40DpVisualSurface() {
    showChat()

    val touchTarget = composeRule.onNodeWithContentDescription("Stop").getUnclippedBoundsInRoot()
    val visualSurface =
      composeRule
        .onNodeWithTag("chat-composer-primary-action-visual", useUnmergedTree = true)
        .getUnclippedBoundsInRoot()
    assertEquals(48.dp, touchTarget.right - touchTarget.left)
    assertEquals(48.dp, touchTarget.bottom - touchTarget.top)
    assertEquals(40.dp, visualSurface.right - visualSurface.left)
    assertEquals(40.dp, visualSurface.bottom - visualSurface.top)
  }

  @Test
  fun hiddenAuxiliaryToolbarKeepsPrimaryActionAtTrailingEdge() {
    showChat()
    composeRule.mainClock.autoAdvance = false

    composeRule.onNode(hasSetTextAction()).performClick()
    composeRule.mainClock.advanceTimeBy(500L)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("chat-composer-model").assertIsDisplayed()
    val expandedActionRight =
      composeRule.onNodeWithContentDescription("Stop").getUnclippedBoundsInRoot().right

    composeRule.mainClock.advanceTimeBy(CHAT_COMPOSER_AUXILIARY_IDLE_MS + 500L)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("chat-composer-model").assertDoesNotExist()
    val collapsedActionRight =
      composeRule.onNodeWithContentDescription("Stop").getUnclippedBoundsInRoot().right

    assertEquals(expandedActionRight, collapsedActionRight)
  }

  @Test
  fun keyboardFocusedAuxiliaryToolbarStaysVisibleUntilFocusLeaves() {
    showChat()
    composeRule.mainClock.autoAdvance = false

    val editor = composeRule.onNode(hasSetTextAction())
    editor.performClick()
    composeRule.mainClock.advanceTimeBy(500L)
    composeRule.waitForIdle()
    val model = composeRule.onNodeWithTag("chat-composer-model").assertIsDisplayed()
    model.performSemanticsAction(SemanticsActions.RequestFocus) { requestFocus ->
      assertTrue("The model control must accept keyboard focus", requestFocus())
    }
    model.assertIsFocused()

    composeRule.mainClock.advanceTimeBy(CHAT_COMPOSER_AUXILIARY_IDLE_MS + 500L)
    composeRule.waitForIdle()
    model.assertIsDisplayed().assertIsFocused()

    editor.performSemanticsAction(SemanticsActions.RequestFocus) { requestFocus ->
      assertTrue(requestFocus())
    }
    editor.assertIsFocused()
    composeRule.mainClock.advanceTimeBy(CHAT_COMPOSER_AUXILIARY_IDLE_MS + 500L)
    composeRule.waitForIdle()
    model.assertDoesNotExist()
  }

  private fun assertDraftKeepsDisabledSendWhileAdmissionIsPending(
    text: String = "",
    attachment: PendingAttachment? = null,
  ) {
    val viewModel = showChat()
    val owner = viewModel.captureChatShareOwner()
    composeRule.runOnIdle {
      val runId = requireNotNull(controller.selectedActiveRunPresentation.value.runId)
      controller.handleGatewayEvent(
        "agent",
        """{"sessionKey":"${controller.sessionKey.value}","runId":"$runId","seq":1,"stream":"lifecycle","data":{"phase":"end"}}""",
      )
      viewModel.chatComposerState.addAttachments(owner, listOfNotNull(attachment))
    }
    val editor = composeRule.onNode(hasSetTextAction())
    if (text.isNotEmpty()) editor.performTextReplacement(text)
    composeRule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled()

    val admissionId = composeRule.runOnIdle { requireNotNull(viewModel.chatComposerState.tryBeginTrackedSend(owner)) }
    try {
      composeRule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsNotEnabled()
      composeRule.onNodeWithContentDescription("Start Talk").assertDoesNotExist()
    } finally {
      composeRule.runOnIdle { viewModel.chatComposerState.finishTrackedSend(admissionId) }
    }

    composeRule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled()
    if (text.isNotEmpty()) editor.assertTextEquals(text)
    attachment?.let { composeRule.onNodeWithText(it.fileName).assertIsDisplayed() }
  }

  private fun showChat(
    viewportWidth: Dp = 360.dp,
    fontScale: Float = 1f,
  ): MainViewModel {
    val viewModel = MainViewModel(app, prefs, SavedStateHandle())
    viewModelStore.put("chat", viewModel)
    viewModel.enterScreenshotFixtureMode(AndroidScreenshotScene.Chat)
    composeRule.setContent {
      DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
        ClawDesignTheme {
          // A portrait phone's remaining content viewport after its IME opens.
          Box(Modifier.size(width = viewportWidth, height = 400.dp).clipToBounds().testTag("chat-viewport")) {
            ChatScreen(
              viewModel = viewModel,
              talkActive = false,
              showSidebarButton = true,
              onOpenSidebar = {},
              onToggleTalk = {},
              onOpenDashboard = {},
              onOpenGatewaySettings = {},
            )
          }
        }
      }
    }
    composeRule.waitUntil {
      viewModel.chatCommands.value.size == 6 && viewModel.chatMessages.value.size >= 24 && !viewModel.chatHistoryLoading.value
    }
    return viewModel
  }

  private fun assertEditorAndStopVisible() {
    val viewport = composeRule.onNodeWithTag("chat-viewport").getUnclippedBoundsInRoot()
    val editorNode = composeRule.onNode(hasSetTextAction())
    val stopNode = composeRule.onNodeWithContentDescription("Stop")
    val editor = editorNode.getUnclippedBoundsInRoot()
    val stop = stopNode.getUnclippedBoundsInRoot()
    assertTrue("Editor must retain a visible line: $editor inside $viewport", editor.bottom > editor.top)
    assertTrue("Stop must retain its touch target: $stop inside $viewport", stop.bottom - stop.top >= 48.dp)
    for (bounds in listOf(editor, stop)) {
      assertTrue("Composer control must stay below the viewport top", bounds.top >= viewport.top)
      assertTrue("Composer control must stay above the viewport bottom", bounds.bottom <= viewport.bottom)
    }
    editorNode.assertIsDisplayed()
    stopNode.assertIsDisplayed().assertHasClickAction()
  }

  private fun setApplicationRuntime(value: NodeRuntime?) {
    NodeApp::class.java
      .getDeclaredField("runtimeInstance")
      .apply { isAccessible = true }
      .set(app, value)
  }
}
