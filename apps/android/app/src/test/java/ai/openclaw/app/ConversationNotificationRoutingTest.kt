package ai.openclaw.app

import ai.openclaw.app.chat.ChatComposerOwner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationNotificationRoutingTest {
  private val target =
    ConversationNotificationTarget(
      gatewayStableId = "gateway-a",
      agentId = "main",
      sessionKey = "agent:main:main",
      runId = "run-42",
    )

  @Test
  fun preTiramisuSkipsRuntimePermissionCheck() {
    var permissionChecked = false

    val allowed =
      canPostConversationNotifications(sdkInt = 31) {
        permissionChecked = true
        false
      }

    assertTrue(allowed)
    assertFalse(permissionChecked)
    assertFalse(canPostConversationNotifications(sdkInt = 33) { false })
    assertTrue(canPostConversationNotifications(sdkInt = 33) { true })
  }

  @Test
  fun unverifiedOrIncompleteOwnerCannotBecomeNotificationTarget() {
    assertEquals(
      null,
      ConversationNotificationTarget.from(
        ChatComposerOwner(
          gatewayStableId = "gateway-a",
          agentId = "main",
          sessionKey = "main",
          routingVerified = false,
        ),
        "run-42",
      ),
    )
    assertEquals(
      null,
      ConversationNotificationTarget.from(
        ChatComposerOwner(gatewayStableId = null, agentId = "main", sessionKey = "agent:main:main"),
        "run-42",
      ),
    )
  }

  @Test
  fun replyIdempotencyIsStablePerTerminalRun() {
    val first = conversationNotificationReplyIdempotencyKey(target)

    assertEquals(first, conversationNotificationReplyIdempotencyKey(target))
    assertNotEquals(first, conversationNotificationReplyIdempotencyKey(target.copy(runId = "run-43")))
  }

  @Test
  fun replyRoutesGatewayThenSessionThenExistingOwnerSend() =
    runTest {
      val events = mutableListOf<String>()
      var sentOwner: ChatComposerOwner? = null

      val sent =
        routeConversationNotificationReply(
          target = target,
          reply = "Continue",
          idempotencyKey = "idempotency-key",
          activeGatewayStableId = { "gateway-b" },
          switchGateway = { gatewayId ->
            events += "gateway:$gatewayId"
            true
          },
          switchSession = { sessionKey, agentId -> events += "session:$sessionKey:$agentId" },
          send = { owner, message, idempotencyKey ->
            sentOwner = owner
            events += "send:$message:$idempotencyKey"
            true
          },
        )

      assertTrue(sent)
      assertEquals(
        listOf(
          "gateway:gateway-a",
          "session:agent:main:main:main",
          "send:Continue:idempotency-key",
        ),
        events,
      )
      assertEquals(target.toComposerOwner(), sentOwner)
    }

  @Test
  fun failedGatewaySwitchCannotCrossIntoSessionOrOutbox() =
    runTest {
      var sessionSwitched = false
      var sendCalled = false

      val sent =
        routeConversationNotificationReply(
          target = target,
          reply = "Continue",
          idempotencyKey = "idempotency-key",
          activeGatewayStableId = { "gateway-b" },
          switchGateway = { false },
          switchSession = { _, _ -> sessionSwitched = true },
          send = { _, _, _ ->
            sendCalled = true
            true
          },
        )

      assertFalse(sent)
      assertFalse(sessionSwitched)
      assertFalse(sendCalled)
    }
}
