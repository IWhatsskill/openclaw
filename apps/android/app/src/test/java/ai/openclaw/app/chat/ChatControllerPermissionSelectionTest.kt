package ai.openclaw.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerPermissionSelectionTest {
  private val json = chatControllerTestJson

  @Test
  fun selectionUsesSessionPatchAndTracksExplicitDefaultEvents() =
    runTest {
      val patches = mutableListOf<String>()
      var sessionRow = """{"key":"main","permissionMode":"guarded","permissionModePending":false}"""
      val controller =
        createScriptedChatController {
          respond("sessions.list") { """{"sessions":[$sessionRow]}""" }
          respond("sessions.patch") { paramsJson ->
            patches += paramsJson.orEmpty()
            sessionRow = """{"key":"main","permissionMode":"full","permissionModePending":false}"""
            "{}"
          }
        }

      controller.refreshSessions()
      advanceUntilIdle()
      assertEquals(
        ChatPermissionMode.Guarded,
        controller.sessions.value
          .single()
          .permissionMode,
      )

      assertTrue(controller.setSessionPermissionModeAwait("main", ChatPermissionMode.Full))
      assertEquals(
        false,
        controller.sessions.value
          .single()
          .permissionModePending,
      )
      val params = json.parseToJsonElement(patches.single()) as JsonObject
      assertEquals("full", (params["permissionMode"] as JsonPrimitive).content)
      assertEquals(
        ChatPermissionMode.Full,
        controller.sessions.value
          .single()
          .permissionMode,
      )

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","permissionMode":null}}""",
      )
      advanceUntilIdle()
      assertNull(
        controller.sessions.value
          .single()
          .permissionMode,
      )
      assertTrue(
        controller.sessions.value
          .single()
          .hasPermissionModeMetadata,
      )
    }

  @Test
  fun successfulPermissionResponsePreservesNewerCanonicalModeAndPendingState() =
    runTest {
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      var sessionRow = """{"key":"main","agentId":"main","permissionMode":"guarded","permissionModePending":false}"""
      val controller =
        createScriptedChatController {
          respond("sessions.list") { """{"sessions":[$sessionRow]}""" }
          respond("sessions.patch") {
            patchStarted.complete(Unit)
            releasePatch.await()
            """{"entry":{"key":"main","permissionMode":"workspace"},"resolved":{}}"""
          }
        }
      controller.refreshSessions()
      advanceUntilIdle()

      val patch = async { controller.setSessionPermissionModeAwait("main", ChatPermissionMode.Workspace) }
      patchStarted.await()
      for ((mode, pending) in listOf("workspace" to true, "workspace" to false, "read-only" to true)) {
        sessionRow = """{"key":"main","agentId":"main","permissionMode":"$mode","permissionModePending":$pending}"""
        controller.handleGatewayEvent(
          "sessions.changed",
          """{"sessionKey":"main","agentId":"main","phase":"message","session":$sessionRow}""",
        )
      }
      val newerState =
        controller.sessions.value
          .single()
          .let { it.permissionMode to it.permissionModePending }
      assertEquals(ChatPermissionMode.ReadOnly to true, newerState)

      releasePatch.complete(Unit)
      assertTrue(patch.await())
      advanceUntilIdle()
      assertEquals(
        newerState,
        controller.sessions.value
          .single()
          .let { it.permissionMode to it.permissionModePending },
      )

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","phase":"message","session":{"key":"main","permissionMode":"read-only","permissionModePending":false}}""",
      )
      assertEquals(
        ChatPermissionMode.ReadOnly to false,
        controller.sessions.value
          .single()
          .let { it.permissionMode to it.permissionModePending },
      )
    }

  @Test
  fun sessionsRefreshPreservesNewerPermissionSnapshotsEvenWhenTheModeRepeats() =
    runTest {
      for (modeRepeats in listOf(false, true)) {
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val olderRow = """{"key":"main","agentId":"main","permissionMode":"workspace","permissionModePending":false}"""
        val newerRow = """{"key":"main","agentId":"main","permissionMode":"read-only","permissionModePending":true}"""
        var sessionRow = if (modeRepeats) newerRow else olderRow
        var listRequests = 0
        val controller =
          createScriptedChatController {
            respond("sessions.list") {
              val response = """{"sessions":[$sessionRow]}"""
              if (++listRequests == 2) {
                listStarted.complete(Unit)
                releaseList.await()
              }
              response
            }
          }
        controller.refreshSessions()
        advanceUntilIdle()
        sessionRow = olderRow
        controller.refreshSessions()
        listStarted.await()

        sessionRow = newerRow
        val event =
          if (modeRepeats) {
            """{"sessionKey":"main","agentId":"main","reason":"patch","permissionMode":"read-only","permissionModePending":true}"""
          } else {
            """{"sessionKey":"main","phase":"message","session":$sessionRow}"""
          }
        controller.handleGatewayEvent("sessions.changed", event)
        releaseList.complete(Unit)
        advanceUntilIdle()

        val session = controller.sessions.value.single()
        assertEquals(ChatPermissionMode.ReadOnly, session.permissionMode)
        assertEquals(true, session.permissionModePending)
        assertEquals(3, listRequests)
      }
    }

  @Test
  fun unchangedMessageSnapshotsDoNotRestartSessionsRefresh() =
    runTest {
      val listStarted = CompletableDeferred<Unit>()
      val releaseList = CompletableDeferred<Unit>()
      var listRequests = 0
      val controller =
        createScriptedChatController {
          respond("sessions.list") {
            if (++listRequests == 2) {
              listStarted.complete(Unit)
              releaseList.await()
            }
            """{"sessions":[{"key":"main","permissionModePending":false,"effectiveFastMode":false,"thinkingLevel":"off","thinkingLevels":[{"id":"off","label":"Off"}],"thinkingDefault":"off"}]}"""
          }
        }
      controller.refreshSessions()
      advanceUntilIdle()
      controller.refreshSessions()
      listStarted.await()
      controller.handleGatewayEvent(
        "session.message",
        """{"session":{"key":"main","agentId":"main","displayName":"Current conversation"}}""",
      )
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","phase":"message","session":{"key":"main","permissionMode":null,"permissionModePending":false,"effectiveFastMode":false,"thinkingLevel":"off"}}""",
      )
      releaseList.complete(Unit)
      advanceUntilIdle()

      assertNull(
        controller.sessions.value
          .single()
          .permissionMode,
      )
      assertEquals(2, listRequests)
    }

  @Test
  fun sameValueMessageSnapshotKeepsNewerPermissionStateOverAnOlderList() =
    runTest {
      val sessionKey = "agent:main:conversation"
      val listStarted = CompletableDeferred<Unit>()
      val releaseList = CompletableDeferred<Unit>()
      var permissionPending = false
      var listRequests = 0
      val controller =
        createScriptedChatController {
          respond("sessions.list") {
            val response = """{"sessions":[{"key":"$sessionKey","permissionMode":"read-only","permissionModePending":$permissionPending,"effectiveFastMode":false}]}"""
            if (++listRequests == 2) {
              listStarted.complete(Unit)
              releaseList.await()
            }
            response
          }
        }
      controller.refreshSessions()
      advanceUntilIdle()
      permissionPending = true
      controller.refreshSessions()
      listStarted.await()

      permissionPending = false
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"$sessionKey","agentId":"main","phase":"message","session":{"key":"$sessionKey","permissionMode":"read-only","permissionModePending":false,"effectiveFastMode":false}}""",
      )
      releaseList.complete(Unit)
      advanceUntilIdle()

      assertEquals(
        false,
        controller.sessions.value
          .single()
          .permissionModePending,
      )
    }

  @Test
  fun acceptedPermissionResponseDoesNotRefreshAReplacedGatewayOrAgent() =
    runTest {
      for (replaceGateway in listOf(true, false)) {
        val patchStarted = CompletableDeferred<Unit>()
        val releasePatch = CompletableDeferred<Unit>()
        var gatewayScope = ChatCacheScope(gatewayId = "gateway-a", connectionGeneration = 1)
        var agentId = "main"
        var listRequests = 0
        val controller =
          createChatController(cacheScope = { gatewayScope }, currentDefaultAgentId = { agentId }) { method, _ ->
            when (method) {
              "sessions.patch" -> {
                patchStarted.complete(Unit)
                releasePatch.await()
              }
              "sessions.list" -> listRequests += 1
            }
            "{}"
          }
        val patch = async { controller.setSessionPermissionModeAwait("main", ChatPermissionMode.Workspace) }
        patchStarted.await()
        if (replaceGateway) {
          gatewayScope = ChatCacheScope(gatewayId = "gateway-b", connectionGeneration = 2)
          controller.onGatewayScopeChanging()
        } else {
          agentId = "ops"
        }
        releasePatch.complete(Unit)

        assertTrue(patch.await())
        advanceUntilIdle()
        assertEquals(0, listRequests)
        assertTrue(controller.sessions.value.isEmpty())
      }
    }

  @Test
  fun permissionWritePropagatesCancellation() =
    runTest {
      val controller =
        createScriptedChatController {
          respond("sessions.patch") { throw CancellationException("Write cancelled") }
        }

      val result = runCatching { controller.setSessionPermissionModeAwait("main", ChatPermissionMode.Workspace) }

      assertTrue(result.exceptionOrNull() is CancellationException)
      assertTrue(controller.pendingSessionSettingsKeys.value.isEmpty())
    }

  @Test
  fun fastModeSelectionParsesBooleanSessionAndResolvedState() =
    runTest {
      val patches = mutableListOf<String>()
      val controller =
        createScriptedChatController {
          respond(
            "sessions.list",
            """{"sessions":[{"key":"main","fastMode":false,"effectiveFastMode":false}]}""",
          )
          respond("sessions.patch") { paramsJson ->
            patches += paramsJson.orEmpty()
            """{"entry":{"key":"main","fastMode":true},"resolved":{}}"""
          }
        }

      controller.refreshSessions()
      advanceUntilIdle()
      assertEquals(
        ChatFastMode.Off,
        controller.sessions.value
          .single()
          .fastMode,
      )
      assertEquals(
        ChatFastMode.Off,
        controller.sessions.value
          .single()
          .effectiveFastMode,
      )

      controller.setSessionFastMode("main", enabled = true)
      advanceUntilIdle()

      val params = json.parseToJsonElement(patches.single()) as JsonObject
      assertEquals("true", (params["fastMode"] as JsonPrimitive).content)
      val session = controller.sessions.value.single()
      assertEquals(ChatFastMode.On, session.fastMode)
      assertEquals(ChatFastMode.On, session.effectiveFastMode)
      assertTrue(session.hasFastModeMetadata)
      assertTrue(session.hasEffectiveFastModeMetadata)
    }

  @Test
  fun fastModeSelectionWaitsForAcceptanceAndPreservesStateAfterRejection() =
    runTest {
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      val controller =
        createScriptedChatController {
          respond(
            "sessions.list",
            """{"sessions":[{"key":"main","fastMode":"off","effectiveFastMode":"off"}]}""",
          )
          respond("sessions.patch") {
            patchStarted.complete(Unit)
            releasePatch.await()
            throw IllegalStateException("rejected")
          }
        }

      controller.refreshSessions()
      advanceUntilIdle()

      controller.setSessionFastMode("main", enabled = true)

      val pending = controller.sessions.value.single()
      assertEquals(ChatFastMode.Off, pending.fastMode)
      assertEquals(ChatFastMode.Off, pending.effectiveFastMode)
      assertEquals(setOf("main"), controller.pendingSessionSettingsKeys.value)

      patchStarted.await()
      releasePatch.complete(Unit)
      advanceUntilIdle()

      val unchanged = controller.sessions.value.single()
      assertEquals(ChatFastMode.Off, unchanged.fastMode)
      assertEquals(ChatFastMode.Off, unchanged.effectiveFastMode)
      assertFalse(controller.pendingSessionSettingsKeys.value.contains("main"))
    }

  @Test
  fun failedFastModeResponseDoesNotOverwriteAnAuthoritativeSessionEvent() =
    runTest {
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      var storedFastMode = false
      val controller =
        createScriptedChatController {
          respond("sessions.list") {
            """{"sessions":[{"key":"main","fastMode":$storedFastMode,"effectiveFastMode":$storedFastMode}]}"""
          }
          respond("sessions.patch") {
            storedFastMode = true
            patchStarted.complete(Unit)
            releasePatch.await()
            throw IllegalStateException("Patch response lost")
          }
        }
      controller.refreshSessions()
      advanceUntilIdle()
      controller.setSessionFastMode("main", enabled = true)
      patchStarted.await()

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","phase":"message","session":{"key":"main","permissionMode":null,"permissionModePending":false,"fastMode":$storedFastMode,"effectiveFastMode":$storedFastMode}}""",
      )
      assertEquals(
        ChatFastMode.On,
        controller.sessions.value
          .single()
          .effectiveFastMode,
      )
      releasePatch.complete(Unit)
      advanceUntilIdle()

      val accepted = controller.sessions.value.single()
      assertEquals(ChatFastMode.On, accepted.fastMode)
      assertEquals(ChatFastMode.On, accepted.effectiveFastMode)
      assertFalse(controller.pendingSessionSettingsKeys.value.contains("main"))
    }

  @Test
  fun identityOnlyPatchInvalidationSuppressesAnOlderSettingsResponseUntilRefresh() =
    runTest {
      val sessionKey = "agent:main:conversation"
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      val refreshStarted = CompletableDeferred<Unit>()
      val releaseRefresh = CompletableDeferred<Unit>()
      var sessionRow: String? = """{"key":"$sessionKey","permissionMode":"guarded","permissionModePending":false}"""
      var listRequests = 0
      val controller =
        createScriptedChatController {
          respond("sessions.list") {
            val response = """{"sessions":[${sessionRow.orEmpty()}]}"""
            if (++listRequests > 1) {
              refreshStarted.complete(Unit)
              releaseRefresh.await()
            }
            response
          }
          respond("sessions.patch") {
            sessionRow = """{"key":"$sessionKey","permissionMode":"workspace","permissionModePending":false}"""
            patchStarted.complete(Unit)
            releasePatch.await()
            """{"entry":{"key":"$sessionKey","permissionMode":"workspace"},"resolved":{}}"""
          }
        }
      controller.refreshSessions()
      advanceUntilIdle()
      val patch = async { controller.setSessionPermissionModeAwait(sessionKey, ChatPermissionMode.Workspace) }
      patchStarted.await()

      // Patch effects publish after releasing the mutation lane. A concurrent deletion
      // can remove the row before the broadcaster loads it, leaving identity only.
      sessionRow = null
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"$sessionKey","agentId":"main","reason":"patch","ts":1}""",
      )
      releasePatch.complete(Unit)
      assertTrue(patch.await())
      refreshStarted.await()
      try {
        assertEquals(
          ChatPermissionMode.Guarded,
          controller.sessions.value
            .single()
            .permissionMode,
        )
      } finally {
        releaseRefresh.complete(Unit)
        advanceUntilIdle()
      }
      assertTrue(controller.sessions.value.isEmpty())
      assertEquals(2, listRequests)
    }

  @Test
  fun successfulFastModeResponseDoesNotOverwriteANewerSessionSnapshot() =
    runTest {
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      var sessionRow = """{"key":"main","permissionMode":null,"permissionModePending":false,"fastMode":false,"effectiveFastMode":false}"""
      val controller =
        createScriptedChatController {
          respond("sessions.list") { """{"sessions":[$sessionRow]}""" }
          respond("sessions.patch") {
            sessionRow = """{"key":"main","permissionMode":null,"permissionModePending":false,"fastMode":true,"effectiveFastMode":true}"""
            patchStarted.complete(Unit)
            releasePatch.await()
            """{"entry":{"key":"main","fastMode":true,"effectiveFastMode":true},"resolved":{}}"""
          }
        }
      controller.refreshSessions()
      advanceUntilIdle()
      controller.setSessionFastMode("main", enabled = true)
      patchStarted.await()
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","phase":"message","session":$sessionRow}""",
      )

      sessionRow = """{"key":"main","permissionMode":null,"permissionModePending":false,"fastMode":false,"effectiveFastMode":false}"""
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","phase":"message","session":$sessionRow}""",
      )
      assertEquals(
        ChatFastMode.Off,
        controller.sessions.value
          .single()
          .fastMode,
      )

      releasePatch.complete(Unit)
      advanceUntilIdle()

      val session = controller.sessions.value.single()
      assertEquals(ChatFastMode.Off, session.fastMode)
      assertFalse(requireNotNull(session.effectiveFastMode).isEnabled)
      assertTrue(controller.pendingSessionSettingsKeys.value.isEmpty())
    }

  @Test
  fun unrelatedSessionSnapshotDoesNotSupersedeAcceptedFastMode() =
    runTest {
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      val controller =
        createScriptedChatController {
          respond("sessions.list", """{"sessions":[{"key":"main","fastMode":false,"effectiveFastMode":false}]}""")
          respond("sessions.patch") {
            patchStarted.complete(Unit)
            releasePatch.await()
            """{"entry":{"key":"main","fastMode":true,"effectiveFastMode":true},"resolved":{}}"""
          }
        }
      controller.refreshSessions()
      advanceUntilIdle()
      controller.setSessionFastMode("main", enabled = true)
      patchStarted.await()
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"other","agentId":"main","phase":"message","session":{"key":"other","fastMode":false,"effectiveFastMode":false,"permissionMode":null,"permissionModePending":false}}""",
      )
      releasePatch.complete(Unit)
      advanceUntilIdle()

      val session = controller.sessions.value.first { it.key == "main" }
      assertEquals(ChatFastMode.On, session.fastMode)
      assertTrue(requireNotNull(session.effectiveFastMode).isEnabled)
    }

  @Test
  fun clearingFastModeRefreshesInheritedEffectiveStateFromSessionList() =
    runTest {
      val patches = mutableListOf<String>()
      var sessionListCalls = 0
      val controller =
        createScriptedChatController {
          respond("sessions.list") {
            sessionListCalls += 1
            if (sessionListCalls == 1) {
              """{"sessions":[{"key":"main","fastMode":"on","effectiveFastMode":"on"}]}"""
            } else {
              """{"sessions":[{"key":"main","effectiveFastMode":"on"}]}"""
            }
          }
          respond("sessions.patch") { paramsJson ->
            patches += paramsJson.orEmpty()
            """{"entry":{"key":"main"},"resolved":{}}"""
          }
        }

      controller.refreshSessions()
      advanceUntilIdle()

      controller.setSessionFastMode(
        sessionKey = "main",
        enabled = false,
        clearOverride = true,
      )
      advanceUntilIdle()

      val params = json.parseToJsonElement(patches.single()) as JsonObject
      assertTrue(params["fastMode"] is JsonNull)
      val session = controller.sessions.value.single()
      assertEquals(null, session.fastMode)
      assertEquals(ChatFastMode.On, session.effectiveFastMode)
      assertFalse(controller.pendingSessionSettingsKeys.value.contains("main"))
    }

  @Test
  fun clearingFastModeDoesNotOverwriteAnInheritedEffectiveEvent() =
    runTest {
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      var sessionListCalls = 0
      val controller =
        createScriptedChatController {
          respond("sessions.list") {
            sessionListCalls += 1
            if (sessionListCalls == 1) {
              """{"sessions":[{"key":"main","fastMode":"on","effectiveFastMode":"on"}]}"""
            } else {
              """{"sessions":[{"key":"main","effectiveFastMode":"on"}]}"""
            }
          }
          respond("sessions.patch") {
            patchStarted.complete(Unit)
            releasePatch.await()
            """{"entry":{"key":"main"},"resolved":{}}"""
          }
        }

      controller.refreshSessions()
      advanceUntilIdle()
      controller.setSessionFastMode(
        sessionKey = "main",
        enabled = false,
        clearOverride = true,
      )
      patchStarted.await()

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","session":{"key":"main","fastMode":null,"effectiveFastMode":"on"}}""",
      )
      assertNull(
        controller.sessions.value
          .single()
          .fastMode,
      )
      assertEquals(
        ChatFastMode.On,
        controller.sessions.value
          .single()
          .effectiveFastMode,
      )

      releasePatch.complete(Unit)
      advanceUntilIdle()

      val accepted = controller.sessions.value.single()
      assertNull(accepted.fastMode)
      assertEquals(ChatFastMode.On, accepted.effectiveFastMode)
      assertTrue(accepted.hasEffectiveFastModeMetadata)
      assertFalse(controller.pendingSessionSettingsKeys.value.contains("main"))
    }

  @Test
  fun authoritativeSessionChangeClearsRemovedOverridesButPartialMessagePreservesThem() =
    runTest {
      val controller =
        createScriptedChatController {
          respond(
            "sessions.list",
            """{"sessions":[{"key":"main","agentId":"main","permissionMode":"guarded","permissionModePending":true,"fastMode":"on","effectiveFastMode":"on"}]}""",
          )
        }

      controller.refreshSessions()
      advanceUntilIdle()

      controller.handleGatewayEvent(
        "session.message",
        """{"session":{"key":"main","agentId":"main","displayName":"Renamed"}}""",
      )
      val afterPartial = controller.sessions.value.single()
      assertEquals(ChatPermissionMode.Guarded, afterPartial.permissionMode)
      assertEquals(true, afterPartial.permissionModePending)
      assertEquals(ChatFastMode.On, afterPartial.fastMode)
      assertEquals(ChatFastMode.On, afterPartial.effectiveFastMode)

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","session":{"key":"main","agentId":"main","effectiveFastMode":"on"}}""",
      )
      val afterAuthoritative = controller.sessions.value.single()
      assertNull(afterAuthoritative.permissionMode)
      assertNull(afterAuthoritative.permissionModePending)
      assertNull(afterAuthoritative.fastMode)
      assertEquals(ChatFastMode.On, afterAuthoritative.effectiveFastMode)
      assertFalse(afterAuthoritative.hasPermissionModeMetadata)
      assertFalse(afterAuthoritative.hasFastModeMetadata)
      assertTrue(afterAuthoritative.hasEffectiveFastModeMetadata)
    }

  @Test
  fun defaultSelectionSendsJsonNull() =
    runTest {
      val patches = mutableListOf<String>()
      val controller =
        createScriptedChatController {
          respond("sessions.patch") { paramsJson ->
            patches += paramsJson.orEmpty()
            "{}"
          }
          respond("sessions.list", """{"sessions":[{"key":"main","permissionModePending":false}]}""")
        }

      assertTrue(controller.setSessionPermissionModeAwait("main", null))

      val params = json.parseToJsonElement(patches.single()) as JsonObject
      assertTrue(params["permissionMode"] is JsonNull)
      assertNull(
        controller.sessions.value
          .single()
          .permissionMode,
      )
    }

  @Test
  fun immediateSendWaitsForPendingPermissionSelection() =
    runTest {
      val patchStarted = CompletableDeferred<Unit>()
      val releasePatch = CompletableDeferred<Unit>()
      val requests = mutableListOf<String>()
      val controller =
        createChatController { method, _ ->
          requests += method
          when (method) {
            "sessions.patch" -> {
              patchStarted.complete(Unit)
              releasePatch.await()
              "{}"
            }
            "chat.send" -> """{"runId":"run-ok","status":"ok"}"""
            else -> "{}"
          }
        }
      controller.handleGatewayEvent("health", null)

      controller.setSessionPermissionMode("main", ChatPermissionMode.Workspace)
      patchStarted.await()
      val send =
        async {
          controller.sendMessageAwaitAcceptance(
            message = "hello",
            thinkingLevel = "off",
            attachments = emptyList(),
          )
        }
      yield()
      assertEquals(listOf("sessions.patch"), requests.filter { it == "sessions.patch" || it == "chat.send" })

      releasePatch.complete(Unit)
      assertTrue(send.await())
      assertEquals(
        listOf("sessions.patch", "chat.send"),
        requests.filter { it == "sessions.patch" || it == "chat.send" },
      )
    }
}
