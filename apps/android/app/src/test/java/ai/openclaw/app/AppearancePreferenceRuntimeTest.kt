package ai.openclaw.app

import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.node.ConnectionManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private const val APPEARANCE_CONNECTION_TIMEOUT_MS = 8_000L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearancePreferenceRuntimeTest {
  @Test
  fun concurrentWritesForOneKeyFinishWithTheLatestValue() =
    runBlocking {
      withAppearanceGateway {
        connect()
        val profile = profileScope("profile-a")
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        respond = { request ->
          if (request.method == "users.prefs.set" && request.entry("ui.theme") == "claw") {
            firstWriteStarted.complete(Unit)
            releaseFirstWrite.await()
          }
          null
        }
        try {
          prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Claw, pendingSync = true, pendingScope = profile)
          val first = async { runtime.setProfileAppearancePreference("ui.theme", "claw") }
          withTimeout(2_000) { firstWriteStarted.await() }
          prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Dash, pendingSync = true, pendingScope = profile)
          val second = async { runtime.setProfileAppearancePreference("ui.theme", "dash") }
          releaseFirstWrite.complete(Unit)

          assertTrue(withTimeout(2_000) { first.await() })
          assertTrue(withTimeout(2_000) { second.await() })
          assertEquals(listOf("claw", "dash"), writes.map { it.entry("ui.theme") })
          assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
          assertTrue(prefs.pendingAppearancePreferenceEntries(profile).isEmpty())
        } finally {
          releaseFirstWrite.complete(Unit)
        }
      }
    }

  @Test
  fun readOnlyProfileRefreshPreservesGatewayScopedPendingTheme() =
    runBlocking {
      withAppearanceGateway {
        val pendingScope = profileScope(null)
        prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Dash, pendingSync = true, pendingScope = pendingScope)
        setProfilePreferences("profile-a", """{"ui.theme":"claw","ui.themeMode":"light"}""")
        connect(scopes = listOf("operator.read"))

        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
        assertEquals(mapOf("ui.theme" to "dash"), prefs.pendingAppearancePreferenceEntries(pendingScope))
        assertTrue(writes.isEmpty())
      }
    }

  @Test
  fun noDurableIdentityUsesGatewayThemeFallbacks() =
    runBlocking {
      withAppearanceGateway {
        prefs.setAppearanceAccentArgb(0xFF5A9BEFL)
        config = """{"ui":{"prefs":{"theme":"dash","themeMode":"light","accent":"#14B8A6"}}}"""
        connect(profileId = null)

        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
        assertEquals(null, prefs.appearanceAccentArgb.value)
        assertEquals(0xFF14B8A6L, runtime.gatewayAccentArgb.value)
        assertEquals(AppearancePreferenceEditMode.DeviceLocal, runtime.appearancePreferenceEditTargetSnapshot().mode)
      }
    }

  @Test
  fun noDurableIdentityRetainsProfileEditUntilItsOwnerReconnects() =
    runBlocking {
      withAppearanceGateway {
        val profileA = profileScope("profile-a")
        prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Dash, pendingSync = true, pendingScope = profileA)
        prefs.setAppearanceAccentArgb(0xFFE96CB7L, pendingSync = true)
        connect(profileId = null)

        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertTrue(prefs.pendingAppearancePreferenceEntries().isEmpty())
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileScope(null)).isEmpty())
        assertEquals(mapOf("ui.theme" to "dash"), prefs.pendingAppearancePreferenceEntries(profileA))
        assertFalse(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
        assertTrue(prefs.isAppearancePreferenceLocalOnly("ui.accent"))

        connect(profileId = "profile-b")
        assertTrue(writes.isEmpty())
        assertEquals(AppearanceThemeFamily.Claw, prefs.appearanceThemeFamily.value)
        assertEquals(mapOf("ui.theme" to "dash"), prefs.pendingAppearancePreferenceEntries(profileA))

        connect(profileId = "profile-a")
        assertEquals(listOf("profile-a" to "dash"), writes.map { it.profileId to it.entry("ui.theme") })
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileA).isEmpty())
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
      }
    }

  @Test
  fun unavailableProfileReadPreservesExistingAppearance() =
    runBlocking {
      withAppearanceGateway {
        setProfilePreferences("profile-a", """{"ui.theme":"tide","ui.themeMode":"dark","ui.accent":"#5A9BEF"}""")
        connect()
        config = """{"ui":{"prefs":{"theme":"dash","themeMode":"light","accent":"#14B8A6"}}}"""
        for (payload in listOf(JsonPrimitive("not-json").toString(), """{"status":"ok"}""", """{"status":"unavailable"}""")) {
          respond = { request -> payload.takeIf { request.method == "users.prefs.get" } }
          refresh()
          assertEquals(AppearanceThemeFamily.Tide, prefs.appearanceThemeFamily.value)
          assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
          assertEquals(0xFF5A9BEFL, prefs.appearanceAccentArgb.value)
          assertEquals(0xFF5A9BEFL, runtime.gatewayAccentArgb.value)
        }
      }
    }

  @Test
  fun olderGatewayWithoutProfilePreferencesUsesConfigFallbacks() =
    runBlocking {
      for (catalogOmitsMethod in listOf(true, false)) {
        withAppearanceGateway {
          prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Tide)
          prefs.setAppearanceThemeMode(AppearanceThemeMode.Dark)
          config = """{"ui":{"prefs":{"theme":"dash","themeMode":"light","accent":"#14B8A6"}}}"""
          respond = { request ->
            if (request.method == "users.prefs.get") {
              throw GatewayRequestRejected(GatewaySession.ErrorShape("INVALID_REQUEST", "unknown method: users.prefs.get"))
            }
            null
          }
          connect(methods = if (catalogOmitsMethod) setOf("config.get") else null)

          assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
          assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
          assertEquals(0xFF14B8A6L, runtime.gatewayAccentArgb.value)
          assertEquals(if (catalogOmitsMethod) 0 else 1, requests.count { it.method == "users.prefs.get" })
        }
      }
    }

  @Test
  fun readOnlyViewModelAppearanceChangesStayLocal() =
    runBlocking {
      withAppearanceGateway {
        connect(scopes = listOf("operator.read"))
        val viewModel = viewModel()
        viewModel.setAppearanceThemeFamily(AppearanceThemeFamily.Dash)
        viewModel.setAppearanceThemeMode(AppearanceThemeMode.Dark)
        viewModel.setAppearanceAccentArgb(0xFFE96CB7L)
        repeat(2) { refresh() }

        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
        assertEquals(0xFFE96CB7L, prefs.appearanceAccentArgb.value)
        for (key in listOf("ui.theme", "ui.themeMode", "ui.accent")) {
          assertTrue(prefs.isAppearancePreferenceLocalOnly(key))
        }
        assertTrue(writes.isEmpty())
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileScope("profile-a")).isEmpty())
        assertTrue(prefs.pendingAppearancePreferenceEntries().isEmpty())

        connect()
        viewModel.setAppearanceThemeFamily(AppearanceThemeFamily.Tide)
        withTimeout(2_000) {
          while (writes.size != 1 || prefs.pendingAppearancePreferenceEntries(profileScope("profile-a")).isNotEmpty()) yield()
        }
        assertEquals(AppearanceThemeFamily.Tide, prefs.appearanceThemeFamily.value)
        assertFalse(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
      }
    }

  @Test
  fun offlineViewModelAppearanceEditStaysPendingForReconnect() =
    runBlocking {
      withAppearanceGateway {
        viewModel().setAppearanceThemeFamily(AppearanceThemeFamily.Dash)

        assertEquals(mapOf("ui.theme" to "dash"), prefs.pendingAppearancePreferenceEntries())
        assertFalse(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
        connect()
        assertEquals(listOf("dash"), writes.map { it.entry("ui.theme") })
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileScope("profile-a")).isEmpty())
      }
    }

  @Test
  fun legacyThemeModeRemainsDeviceLocalAcrossWritableProfileRefresh() =
    runBlocking {
      withAppearanceGateway(legacyThemeMode = AppearanceThemeMode.Light) {
        setProfilePreferences("profile-a", """{"ui.themeMode":"dark"}""")
        connect()

        assertTrue(writes.isEmpty())
        assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
        assertTrue(prefs.isAppearancePreferenceLocalOnly("ui.themeMode"))
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileScope("profile-a")).isEmpty())
        assertTrue(prefs.pendingAppearancePreferenceEntries().isEmpty())
      }
    }

  @Test
  fun missingProfileAccentKeepsGatewayFallbackOutOfTheLocalOverride() =
    runBlocking {
      withAppearanceGateway {
        prefs.setAppearanceAccentArgb(0xFF5A9BEFL)
        config = """{"ui":{"prefs":{"accent":"#14B8A6"}}}"""
        connect()

        assertEquals(null, prefs.appearanceAccentArgb.value)
        assertEquals(0xFF14B8A6L, runtime.gatewayAccentArgb.value)
      }
    }

  @Test
  fun olderProfileRefreshCannotOverwriteNewerAppearance() =
    runBlocking {
      withAppearanceGateway {
        connect()
        val firstConfigStarted = CompletableDeferred<Unit>()
        val releaseFirstConfig = CompletableDeferred<Unit>()
        val configReads = AtomicInteger()
        val preferenceReads = AtomicInteger()
        respond = { request ->
          when (request.method) {
            "config.get" -> {
              if (configReads.incrementAndGet() == 1) {
                firstConfigStarted.complete(Unit)
                releaseFirstConfig.await()
              }
              null
            }
            "users.prefs.get" ->
              if (preferenceReads.incrementAndGet() == 1) {
                """{"status":"ok","entries":{"ui.theme":"dash","ui.themeMode":"dark"}}"""
              } else {
                """{"status":"ok","entries":{"ui.theme":"claw","ui.themeMode":"light"}}"""
              }
            else -> null
          }
        }
        try {
          val older = async { refresh() }
          withTimeout(2_000) { firstConfigStarted.await() }
          refresh()
          releaseFirstConfig.complete(Unit)
          withTimeout(2_000) { older.await() }

          assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
          assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
        } finally {
          releaseFirstConfig.complete(Unit)
        }
      }
    }

  @Test
  fun gatewaySwitchCannotInterleaveWithUnscopedPreferenceAdoption() =
    runBlocking {
      withAppearanceGateway {
        connect(scopes = listOf("operator.read"))
        prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Dash, pendingSync = true)
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        respond = { request ->
          if (request.method == "users.self") {
            lookupStarted.complete(Unit)
            releaseLookup.await()
          }
          null
        }
        val prefsLocked = CountDownLatch(1)
        val releasePrefs = CountDownLatch(1)
        val prefsLockOwner =
          Thread {
            synchronized(prefs) {
              prefsLocked.countDown()
              releasePrefs.await()
            }
          }
        var disconnect: Thread? = null
        try {
          val pendingRefresh = async(Dispatchers.IO) { refresh() }
          withTimeout(2_000) { lookupStarted.await() }
          prefsLockOwner.start()
          assertTrue(prefsLocked.await(10, TimeUnit.SECONDS))
          val gatewayLock = ReflectionHelpers.getField<Any>(runtime, "gatewayDataScopeLock")
          releaseLookup.complete(Unit)
          assertTrue(awaitMonitorOwned(gatewayLock))
          val disconnectThread = Thread { runtime.disconnect() }
          disconnect = disconnectThread
          disconnectThread.start()
          assertTrue(awaitThreadState(disconnectThread, Thread.State.BLOCKED))
          releasePrefs.countDown()
          withTimeout(10_000) { pendingRefresh.await() }
          disconnectThread.join(10_000)

          assertFalse(disconnectThread.isAlive)
          assertEquals(mapOf("ui.theme" to "dash"), prefs.pendingAppearancePreferenceEntries(profileScope(null)))
          assertTrue(prefs.pendingAppearancePreferenceEntries().isEmpty())
        } finally {
          releaseLookup.complete(Unit)
          releasePrefs.countDown()
          prefsLockOwner.join(10_000)
          disconnect?.join(10_000)
        }
      }
    }

  @Test
  fun brandingRefreshPropagatesCancellation() =
    runBlocking {
      withAppearanceGateway {
        connect()
        val configRead = CompletableDeferred<Unit>()
        val releaseConfig = CompletableDeferred<Unit>()
        respond = { request ->
          if (request.method == "config.get") {
            configRead.complete(Unit)
            releaseConfig.await()
          }
          null
        }
        var returnedNormally = false
        val pendingRefresh =
          launch {
            refresh()
            returnedNormally = true
          }
        try {
          withTimeout(2_000) { configRead.await() }
          pendingRefresh.cancel(CancellationException("refresh cancelled"))
          withTimeout(2_000) { pendingRefresh.join() }
          assertFalse(returnedNormally)
        } finally {
          releaseConfig.complete(Unit)
        }
      }
    }

  @Test
  fun completedWriteCannotOverwriteANewerProfileOwner() =
    runBlocking {
      withAppearanceGateway {
        connect()
        val profileA = profileScope("profile-a")
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        respond = { request ->
          if (request.method == "users.prefs.set") {
            writeStarted.complete(Unit)
            releaseWrite.await()
          }
          null
        }
        try {
          prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Dash, pendingSync = true, pendingScope = profileA)
          val write = async { runtime.setProfileAppearancePreference("ui.theme", "dash") }
          withTimeout(2_000) { writeStarted.await() }
          changeCurrentProfile("profile-b")
          refresh()
          releaseWrite.complete(Unit)

          assertTrue(withTimeout(2_000) { write.await() })
          assertEquals(AppearanceThemeFamily.Claw, prefs.appearanceThemeFamily.value)
          assertEquals(mapOf("ui.theme" to "dash"), prefs.pendingAppearancePreferenceEntries(profileA))
        } finally {
          releaseWrite.complete(Unit)
        }
      }
    }

  @Test
  fun reconnectDoesNotWriteAPreviousProfilesPendingPreferenceOnTheNewSocket() =
    runBlocking {
      withAppearanceGateway {
        connect()
        val profileA = profileScope("profile-a")
        prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Rose, pendingSync = true, pendingScope = profileA)
        val secondBrandingRead = CompletableDeferred<Unit>()
        val releaseBranding = CompletableDeferred<Unit>()
        respond = { request ->
          if (request.method == "config.get" && request.profileId == "profile-b") {
            secondBrandingRead.complete(Unit)
            releaseBranding.await()
          }
          null
        }
        try {
          connect(profileId = "profile-b", waitForBranding = false)
          withTimeout(2_000) { secondBrandingRead.await() }
          val written = withTimeout(2_000) { runtime.setProfileAppearancePreference("ui.theme", "rose") }

          assertEquals("The new socket must not receive another profile's queued edit", emptyList<AppearanceRpcRequest>(), writes)
          assertFalse(written)
          assertEquals(mapOf("ui.theme" to "rose"), prefs.pendingAppearancePreferenceEntries(profileA))
        } finally {
          releaseBranding.complete(Unit)
        }
      }
    }

  private fun awaitThreadState(
    thread: Thread,
    expected: Thread.State,
  ): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (thread.state != expected && System.nanoTime() < deadline) Thread.sleep(10)
    return thread.state == expected
  }

  private fun awaitMonitorOwned(monitor: Any): Boolean {
    val identity = System.identityHashCode(monitor)
    val threads = ManagementFactory.getThreadMXBean()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
      if (threads.dumpAllThreads(true, false).any { thread -> thread.lockedMonitors.any { it.identityHashCode == identity } }) return true
      Thread.sleep(10)
    }
    return false
  }
}

private suspend fun withAppearanceGateway(
  legacyThemeMode: AppearanceThemeMode? = null,
  block: suspend AppearanceGatewayFixture.() -> Unit,
) {
  val fixture = AppearanceGatewayFixture(legacyThemeMode)
  try {
    fixture.block()
  } finally {
    fixture.close()
  }
}

private data class AppearanceRpcRequest(
  val connection: Int,
  val profileId: String?,
  val method: String,
  val params: JsonObject,
) {
  fun entry(key: String): String? =
    params["entries"]
      ?.jsonObject
      ?.get(key)
      ?.takeIf { it !is JsonNull }
      ?.jsonPrimitive
      ?.content
}

private class AppearanceGatewayFixture(
  legacyThemeMode: AppearanceThemeMode?,
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val app = RuntimeEnvironment.getApplication() as NodeApp
  private val server = MockWebServer()
  private val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val sequence = AtomicInteger()
  private val brandingFinished = MutableStateFlow(0)
  private val profiles = ConcurrentHashMap<String, JsonObject>()
  private val connections = ConcurrentHashMap<Int, Connection>()
  private val models = ViewModelStore()

  private class Connection(
    @Volatile var profileId: String?,
    val scopes: List<String>,
    val methods: Set<String>?,
  )

  @Volatile private var nextConnection =
    Connection("profile-a", listOf("operator.read", "operator.write"), appearanceMethods)

  @Volatile var config = "{}"

  @Volatile var respond: suspend (AppearanceRpcRequest) -> String? = { null }
  val requests = CopyOnWriteArrayList<AppearanceRpcRequest>()
  val writes: List<AppearanceRpcRequest> get() = requests.filter { it.method == "users.prefs.set" }
  val prefs: SecurePrefs
  val runtime: NodeRuntime
  val endpoint: GatewayEndpoint
  private val session: GatewaySession
  private var connected = false

  init {
    app.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE).edit().apply {
      clear()
      legacyThemeMode?.let { putString("appearance.themeMode", it.rawValue) }
      commit()
    }
    prefs = SecurePrefs(app, app.getSharedPreferences("appearance-runtime-" + UUID.randomUUID(), Context.MODE_PRIVATE))
    runtime = NodeRuntime(app, prefs)
    session = ReflectionHelpers.getField(runtime, "operatorSession")
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (!request.getHeader("Upgrade").equals("websocket", ignoreCase = true)) return MockResponse().setResponseCode(404)
          val number = sequence.incrementAndGet()
          val identity = nextConnection
          connections[number] = identity
          return MockResponse().withWebSocketUpgrade(listener(number, identity))
        }
      }
    server.start(InetAddress.getByName("127.0.0.1"), 0)
    endpoint = GatewayEndpoint.manual("127.0.0.1", server.port)
  }

  suspend fun connect(
    profileId: String? = "profile-a",
    scopes: List<String> = listOf("operator.read", "operator.write"),
    methods: Set<String>? = appearanceMethods,
    waitForBranding: Boolean = true,
  ) {
    nextConnection = Connection(profileId, scopes, methods)
    val number = sequence.get() + 1
    if (!connected) {
      // Match the established runtime fixture: stop discovery before it can open
      // extra sockets, keeping the normal session callbacks and their IO alive.
      val startupJobs =
        ReflectionHelpers
          .getField<CoroutineScope>(runtime, "scope")
          .coroutineContext.job.children
          .toList()
      startupJobs.forEach { it.cancel() }
      startupJobs.joinAll()
      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      val manager = ReflectionHelpers.getField<ConnectionManager>(runtime, "connectionManager")
      session.connect(endpoint, "synthetic-appearance-proof", null, null, manager.buildOperatorConnectOptions())
      connected = true
    } else {
      session.reconnect()
    }
    withTimeout(APPEARANCE_CONNECTION_TIMEOUT_MS) {
      runtime.serverName.first { it == "appearance-$number" }
      if (waitForBranding) brandingFinished.first { it >= number }
    }
  }

  fun profileScope(profileId: String?) = AppearancePreferenceScope(endpoint.stableId, profileId)

  fun setProfilePreferences(
    profileId: String,
    entries: String,
  ) {
    profiles[profileId] = json.parseToJsonElement(entries).jsonObject
  }

  fun changeCurrentProfile(profileId: String?) {
    connections.getValue(sequence.get()).profileId = profileId
  }

  fun viewModel(): MainViewModel =
    MainViewModel(app, prefs, SavedStateHandle()).also { viewModel ->
      ReflectionHelpers.getField<MutableStateFlow<NodeRuntime?>>(viewModel, "runtimeRef").value = runtime
      models.put("appearance", viewModel)
    }

  suspend fun refresh() {
    try {
      suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
        NodeRuntime::class.java
          .getDeclaredMethod("refreshBrandingFromGateway", Continuation::class.java)
          .apply { isAccessible = true }
          .invoke(runtime, continuation)
      }
    } catch (wrapped: InvocationTargetException) {
      throw wrapped.targetException
    }
  }

  private fun listener(
    number: Int,
    identity: Connection,
  ) = object : WebSocketListener() {
    override fun onOpen(
      webSocket: WebSocket,
      response: Response,
    ) {
      webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"appearance-proof","ts":1700000000123}}""")
    }

    override fun onMessage(
      webSocket: WebSocket,
      text: String,
    ) {
      val frame = json.parseToJsonElement(text).jsonObject
      if (frame["type"]?.jsonPrimitive?.content != "req") return
      val id = checkNotNull(frame["id"])
      val request =
        AppearanceRpcRequest(
          connection = number,
          profileId = identity.profileId,
          method = checkNotNull(frame["method"]).jsonPrimitive.content,
          params = frame["params"] as? JsonObject ?: JsonObject(emptyMap()),
        )
      requests += request
      // The connected callback requests voicewake only after branding returns.
      // This observes completion without replacing the appearance publication owner.
      if (request.method == "voicewake.get") brandingFinished.value = number
      workers.launch {
        try {
          val payload = respond(request) ?: response(request, identity)
          webSocket.send("""{"type":"res","id":$id,"ok":true,"payload":$payload}""")
        } catch (error: GatewayRequestRejected) {
          val code = JsonPrimitive(error.gatewayError.code)
          val message = JsonPrimitive(error.gatewayError.message)
          webSocket.send("""{"type":"res","id":$id,"ok":false,"error":{"code":$code,"message":$message}}""")
        }
      }
    }

    override fun onClosing(
      webSocket: WebSocket,
      code: Int,
      reason: String,
    ) {
      webSocket.close(code, reason)
    }
  }

  private fun response(
    request: AppearanceRpcRequest,
    identity: Connection,
  ): String =
    when (request.method) {
      "connect" ->
        buildJsonObject {
          put("server", buildJsonObject { put("host", JsonPrimitive("appearance-" + request.connection)) })
          identity.methods?.let { methods ->
            put("features", buildJsonObject { put("methods", JsonArray(methods.map(::JsonPrimitive))) })
          }
          put(
            "auth",
            buildJsonObject {
              put("role", JsonPrimitive("operator"))
              put("scopes", JsonArray(identity.scopes.map(::JsonPrimitive)))
            },
          )
          put("snapshot", json.parseToJsonElement("""{"sessionDefaults":{"mainSessionKey":"main"}}"""))
        }.toString()
      "config.get" -> """{"config":$config}"""
      "users.prefs.get" ->
        if (request.profileId == null) {
          """{"status":"no_durable_identity"}"""
        } else {
          val entries = profiles[request.profileId] ?: JsonObject(emptyMap())
          """{"status":"ok","entries":$entries}"""
        }
      "users.self" -> {
        requireWriteScope(identity)
        val id = request.profileId?.let(::JsonPrimitive) ?: JsonNull
        """{"profile":{"id":$id}}"""
      }
      "users.prefs.set" -> {
        requireWriteScope(identity)
        val profileId = checkNotNull(request.profileId)
        val entries = request.params.getValue("entries").jsonObject
        profiles.compute(profileId) { _, previous ->
          JsonObject((previous.orEmpty() + entries).filterValues { it !is JsonNull })
        }
        """{"status":"ok"}"""
      }
      else -> "{}"
    }

  private fun requireWriteScope(identity: Connection) {
    if ("operator.write" !in identity.scopes) {
      throw GatewayRequestRejected(GatewaySession.ErrorShape("INVALID_REQUEST", "missing scope: operator.write"))
    }
  }

  suspend fun close() {
    models.clear()
    try {
      session.disconnectAndJoin()
    } finally {
      try {
        closeNodeRuntimeTestFixture(runtime)
      } finally {
        workers.coroutineContext.job.cancelAndJoin()
        server.shutdown()
      }
    }
  }

  companion object {
    private val appearanceMethods = setOf("config.get", "users.self", "users.prefs.get", "users.prefs.set")
  }
}
