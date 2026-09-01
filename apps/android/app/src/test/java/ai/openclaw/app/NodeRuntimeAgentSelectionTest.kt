package ai.openclaw.app

import ai.openclaw.app.chat.ChatSessionDeletion
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.gateway.GatewayEndpoint
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeRuntimeAgentSelectionTest {
  @Test
  fun selectingAgentRebindsCanonicalMainSession() {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.secure.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val runtime = NodeRuntime(app, SecurePrefs(app, securePrefsOverride = securePrefs))

    runtime.selectChatAgent(" scout ")

    assertEquals("scout", resolveAgentIdFromMainSessionKey(runtime.mainSessionKey.value))
    assertEquals(runtime.mainSessionKey.value, runtime.chatSessionKey.value)
  }

  @Test
  fun manualSessionSelectionWinsOverLateCatalogContinuation() =
    runBlocking {
      val runtime = createConnectedRuntime()
      try {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
          check(method == "sessions.catalog.continue")
          requestStarted.complete(Unit)
          releaseResponse.await()
          """{"sessionKey":"agent:main:catalog"}"""
        }
        val entry =
          SessionCatalogEntry(
            catalogId = "codex",
            hostId = "desktop",
            threadId = "thread-1",
            agentId = "main",
            status = "idle",
            archived = false,
            canContinue = true,
          )

        val continuation = async { runtime.continueSessionCatalogEntry(entry) }
        withTimeout(2_000) { requestStarted.await() }
        runtime.switchChatSession("agent:main:user")
        assertEquals(null, runtime.sessionCatalogState.value.continuingEntryId)
        releaseResponse.complete(Unit)

        assertFalse(withTimeout(2_000) { continuation.await() })
        assertEquals("agent:main:user", runtime.chatSessionKey.value)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun agentSessionSelectionRestoresRememberedThenFallsBackToNewestNonMain() {
    val candidates =
      listOf(
        ChatSessionEntry(
          key = "agent:scout:main",
          updatedAtMs = 500,
          ownerAgentId = "scout",
          isMain = true,
        ),
        ChatSessionEntry(
          key = "agent:scout:remembered",
          updatedAtMs = 10,
          ownerAgentId = "scout",
        ),
        ChatSessionEntry(
          key = "agent:scout:newest",
          updatedAtMs = 20,
          ownerAgentId = "scout",
        ),
        ChatSessionEntry(
          key = "agent:scout:archived",
          updatedAtMs = 30,
          ownerAgentId = "scout",
          archived = true,
        ),
        ChatSessionEntry(
          key = "agent:other:wrong-owner",
          updatedAtMs = 40,
          ownerAgentId = "other",
        ),
      )

    assertEquals(
      "agent:scout:remembered",
      selectChatAgentSessionKey(candidates, "scout", "agent:scout:remembered", "agent:scout:main"),
    )
    assertEquals(
      "agent:scout:newest",
      selectChatAgentSessionKey(candidates, "scout", "agent:scout:missing", "agent:scout:main"),
    )
    assertEquals(
      "agent:scout:main",
      selectChatAgentSessionKey(candidates.take(1), "scout", null, "agent:scout:main"),
    )
  }

  @Test
  fun explicitSessionSelectionWinsOverLateAgentSessionLookup() =
    runBlocking {
      val runtime = createConnectedRuntime()
      try {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        runtime.chatAgentSessionCandidatesOverrideForTests = {
          requestStarted.complete(Unit)
          releaseResponse.await()
          listOf(ChatSessionEntry(key = "agent:scout:late", updatedAtMs = 20, ownerAgentId = "scout"))
        }

        runtime.selectChatAgent("scout")
        withTimeout(2_000) { requestStarted.await() }
        runtime.switchChatSession("agent:scout:chosen")
        releaseResponse.complete(Unit)
        delay(100)

        assertEquals("agent:scout:chosen", runtime.chatSessionKey.value)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun newerAgentSelectionWinsOverLatePreviousAgentLookup() =
    runBlocking {
      val runtime = createConnectedRuntime()
      try {
        val scoutStarted = CompletableDeferred<Unit>()
        val releaseScout = CompletableDeferred<Unit>()
        runtime.chatAgentSessionCandidatesOverrideForTests = { agentId ->
          if (agentId == "scout") {
            scoutStarted.complete(Unit)
            releaseScout.await()
            listOf(ChatSessionEntry(key = "agent:scout:late", updatedAtMs = 20, ownerAgentId = "scout"))
          } else {
            emptyList()
          }
        }

        runtime.selectChatAgent("scout")
        withTimeout(2_000) { scoutStarted.await() }
        runtime.selectChatAgent("writer")
        val writerMain = runtime.mainSessionKey.value
        releaseScout.complete(Unit)
        delay(100)

        assertEquals("writer", resolveAgentIdFromMainSessionKey(writerMain))
        assertEquals(writerMain, runtime.chatSessionKey.value)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun agentSelectionRestoresLastExplicitSessionForThatAgent() =
    runBlocking {
      val runtime = createConnectedRuntime()
      try {
        runtime.chatAgentSessionCandidatesOverrideForTests = { agentId ->
          when (agentId) {
            "scout" ->
              listOf(
                ChatSessionEntry(key = "agent:scout:chosen", updatedAtMs = 10, ownerAgentId = "scout"),
                ChatSessionEntry(key = "agent:scout:newest", updatedAtMs = 20, ownerAgentId = "scout"),
              )
            else -> emptyList()
          }
        }
        runtime.switchChatSession("agent:scout:chosen")
        runtime.selectChatAgent("writer")
        runtime.selectChatAgent("scout")

        withTimeout(2_000) {
          while (runtime.chatSessionKey.value != "agent:scout:chosen") delay(10)
        }
        assertEquals("agent:scout:chosen", runtime.chatSessionKey.value)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun sessionDeletionInvalidatesLateAgentSessionLookup() =
    runBlocking {
      val runtime = createConnectedRuntime()
      try {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        runtime.chatAgentSessionCandidatesOverrideForTests = {
          requestStarted.complete(Unit)
          releaseResponse.await()
          listOf(ChatSessionEntry(key = "agent:scout:deleted", updatedAtMs = 20, ownerAgentId = "scout"))
        }

        runtime.selectChatAgent("scout")
        withTimeout(2_000) { requestStarted.await() }
        val scoutMain = runtime.mainSessionKey.value
        ReflectionHelpers.callInstanceMethod<Unit>(
          runtime,
          "publishChatSessionDeletion",
          ReflectionHelpers.ClassParameter.from(
            ChatSessionDeletion::class.java,
            ChatSessionDeletion(
              gatewayId = GatewayEndpoint.manual("127.0.0.1", 18789).stableId,
              agentId = "scout",
              sessionKey = "agent:scout:deleted",
              mainSessionKey = scoutMain,
            ),
          ),
        )
        releaseResponse.complete(Unit)
        delay(100)

        assertEquals(scoutMain, runtime.chatSessionKey.value)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  private fun createConnectedRuntime(): NodeRuntime {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.session.selection.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    return NodeRuntime(app, SecurePrefs(app, securePrefsOverride = securePrefs)).also { runtime ->
      ReflectionHelpers.setField(runtime, "connectedEndpoint", GatewayEndpoint.manual("127.0.0.1", 18789))
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
    }
  }
}
