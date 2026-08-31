package ai.openclaw.app

import ai.openclaw.app.gateway.GatewayEndpoint
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionCatalogRuntimeTest {
  @Test
  fun loadMoreDuringRefreshIsIgnoredAndRetryKeepsTheAppendedPage() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val securePrefs =
        app.getSharedPreferences(
          "openclaw.node.session.catalog.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val runtime = NodeRuntime(app, SecurePrefs(app, securePrefsOverride = securePrefs))
      val refreshStarted = CompletableDeferred<Unit>()
      val releaseRefresh = CompletableDeferred<Unit>()
      val loadMoreStarted = CompletableDeferred<Unit>()
      try {
        ReflectionHelpers.setField(runtime, "connectedEndpoint", GatewayEndpoint.manual("127.0.0.1", 18789))
        ReflectionHelpers.setField(runtime, "operatorConnected", true)
        ReflectionHelpers.getField<MutableStateFlow<Boolean>>(runtime, "_sessionCatalogAvailable").value = true
        ReflectionHelpers.getField<MutableStateFlow<SessionCatalogState>>(runtime, "_sessionCatalogState").value =
          SessionCatalogState(
            agentId = "main",
            catalogs =
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
                        sessions = emptyList(),
                        nextCursor = "cursor-2",
                      ),
                    ),
                ),
              ),
          )
        runtime.gatewayDataRequestOverrideForTests = { _, method, params ->
          check(method == "sessions.catalog.list")
          val request = Json.parseToJsonElement(requireNotNull(params)).jsonObject
          if ("cursors" in request) {
            loadMoreStarted.complete(Unit)
            """{"catalogs":[{"id":"codex","label":"Codex","hosts":[{"hostId":"desktop","label":"Desktop","kind":"node","connected":true,"sessions":[{"threadId":"page-2","status":"idle","canContinue":true}]}]}]}"""
          } else {
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            """{"catalogs":[{"id":"codex","label":"Codex","hosts":[{"hostId":"desktop","label":"Desktop","kind":"node","connected":true,"nextCursor":"cursor-2","sessions":[{"threadId":"first","status":"idle","canContinue":true}]}]}]}"""
          }
        }

        runtime.refreshSessionCatalog("main")
        withTimeout(2_000) { refreshStarted.await() }
        val ignoredLoadMore =
          async(start = CoroutineStart.UNDISPATCHED) {
            invokeLoadMoreSessionCatalogFromGateway(runtime, "codex")
          }
        withTimeout(2_000) { ignoredLoadMore.await() }
        assertFalse(loadMoreStarted.isCompleted)

        releaseRefresh.complete(Unit)
        withTimeout(2_000) {
          while (runtime.sessionCatalogState.value.loading) delay(10)
        }

        val retriedLoadMore =
          async(start = CoroutineStart.UNDISPATCHED) {
            invokeLoadMoreSessionCatalogFromGateway(runtime, "codex")
          }
        withTimeout(2_000) { loadMoreStarted.await() }
        withTimeout(2_000) { retriedLoadMore.await() }

        val state = runtime.sessionCatalogState.value
        assertEquals(
          listOf("first", "page-2"),
          state.catalogs
            .single()
            .hosts
            .single()
            .sessions
            .map(SessionCatalogEntry::threadId),
        )
        assertEquals(1, state.loadedPageDepthsByHost[sessionCatalogHostKey("codex", "desktop")])
      } finally {
        releaseRefresh.complete(Unit)
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun newerAgentRefreshPublishesWithoutWaitingForTheOlderNetworkCall() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val securePrefs =
        app.getSharedPreferences(
          "openclaw.node.session.catalog.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val runtime = NodeRuntime(app, SecurePrefs(app, securePrefsOverride = securePrefs))
      val mainStarted = CompletableDeferred<Unit>()
      val releaseMain = CompletableDeferred<Unit>()
      val workStarted = CompletableDeferred<Unit>()
      try {
        ReflectionHelpers.setField(runtime, "connectedEndpoint", GatewayEndpoint.manual("127.0.0.1", 18789))
        ReflectionHelpers.setField(runtime, "operatorConnected", true)
        ReflectionHelpers.getField<MutableStateFlow<Boolean>>(runtime, "_sessionCatalogAvailable").value = true
        runtime.gatewayDataRequestOverrideForTests = { _, method, params ->
          check(method == "sessions.catalog.list")
          val agentId =
            Json
              .parseToJsonElement(requireNotNull(params))
              .jsonObject["agentId"]
              ?.jsonPrimitive
              ?.content
          when (agentId) {
            "main" -> {
              mainStarted.complete(Unit)
              releaseMain.await()
              """{"catalogs":[{"id":"codex","label":"Main catalog","hosts":[]}]}"""
            }
            "work" -> {
              workStarted.complete(Unit)
              """{"catalogs":[{"id":"codex","label":"Work catalog","hosts":[]}]}"""
            }
            else -> error("Unexpected agent: $agentId")
          }
        }

        val mainRefresh =
          async(start = CoroutineStart.UNDISPATCHED) {
            invokeRefreshSessionCatalogFromGateway(runtime, "main")
          }
        withTimeout(2_000) { mainStarted.await() }

        val workRefresh =
          async(start = CoroutineStart.UNDISPATCHED) {
            invokeRefreshSessionCatalogFromGateway(runtime, "work")
          }
        withTimeout(2_000) { workStarted.await() }
        withTimeout(2_000) { workRefresh.await() }

        assertEquals("work", runtime.sessionCatalogState.value.agentId)
        assertEquals(
          "Work catalog",
          runtime.sessionCatalogState.value.catalogs
            .single()
            .label,
        )
        assertFalse(runtime.sessionCatalogState.value.loading)

        releaseMain.complete(Unit)
        withTimeout(2_000) { mainRefresh.await() }

        assertEquals("work", runtime.sessionCatalogState.value.agentId)
        assertEquals(
          "Work catalog",
          runtime.sessionCatalogState.value.catalogs
            .single()
            .label,
        )
      } finally {
        releaseMain.complete(Unit)
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun completedRefreshRetiresProgressOwner() {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.session.catalog.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val runtime = NodeRuntime(app, SecurePrefs(app, securePrefsOverride = securePrefs))
    try {
      ReflectionHelpers.setField(runtime, "connectedEndpoint", GatewayEndpoint.manual("127.0.0.1", 18789))
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers.getField<MutableStateFlow<Boolean>>(runtime, "_sessionCatalogAvailable").value = true
      val requestParams = CompletableDeferred<String>()
      runtime.gatewayDataRequestOverrideForTests = { _, method, params ->
        check(method == "sessions.catalog.list")
        requestParams.complete(requireNotNull(params))
        """{"catalogs":[]}"""
      }

      runtime.refreshSessionCatalog("main")

      runBlocking {
        val params = withTimeout(2_000) { requestParams.await() }
        val progressId =
          Json
            .parseToJsonElement(params)
            .jsonObject["progressId"]
            ?.jsonPrimitive
            ?.content
        assertTrue(!progressId.isNullOrBlank())
        withTimeout(2_000) {
          while (runtime.sessionCatalogState.value.loading) delay(10)
        }
      }
      val owner =
        ReflectionHelpers.getField<AtomicReference<Any?>>(runtime, "sessionCatalogProgressOwner")
      assertNull(owner.get())
    } finally {
      closeNodeRuntimeTestFixture(runtime)
    }
  }

  private suspend fun invokeRefreshSessionCatalogFromGateway(
    runtime: NodeRuntime,
    agentId: String,
  ) = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
    NodeRuntime::class.java
      .getDeclaredMethod("refreshSessionCatalogFromGateway", String::class.java, Continuation::class.java)
      .apply { isAccessible = true }
      .invoke(runtime, agentId, continuation)
  }

  private suspend fun invokeLoadMoreSessionCatalogFromGateway(
    runtime: NodeRuntime,
    catalogId: String,
  ) = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
    NodeRuntime::class.java
      .getDeclaredMethod("loadMoreSessionCatalogFromGateway", String::class.java, Continuation::class.java)
      .apply { isAccessible = true }
      .invoke(runtime, catalogId, continuation)
  }
}
