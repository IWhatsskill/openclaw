package ai.openclaw.app

import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearancePreferenceRuntimeTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun concurrentWritesForOneKeyFinishWithTheLatestValue() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
      val scope = AppearancePreferenceScope(endpoint.stableId, "profile-a")
      val firstWriteStarted = CompletableDeferred<Unit>()
      val releaseFirstWrite = CompletableDeferred<Unit>()
      val writtenValues = mutableListOf<String?>()

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      runtime.gatewayDataRequestOverrideForTests = { _, method, params ->
        when (method) {
          "users.self" -> """{"profile":{"id":"profile-a"}}"""
          "users.prefs.set" -> {
            val value =
              json
                .parseToJsonElement(checkNotNull(params))
                .jsonObject["entries"]
                ?.jsonObject
                ?.get("ui.theme")
                ?.jsonPrimitive
                ?.content
            writtenValues += value
            if (value == AppearanceThemeFamily.Claw.rawValue) {
              firstWriteStarted.complete(Unit)
              releaseFirstWrite.await()
            }
            """{"status":"ok"}"""
          }
          else -> error("Unexpected method: $method")
        }
      }

      try {
        prefs.setAppearanceThemeFamily(
          AppearanceThemeFamily.Claw,
          pendingSync = true,
          pendingScope = scope,
        )
        val first =
          async {
            runtime.setProfileAppearancePreference("ui.theme", AppearanceThemeFamily.Claw.rawValue)
          }
        withTimeout(2_000) { firstWriteStarted.await() }

        prefs.setAppearanceThemeFamily(
          AppearanceThemeFamily.Dash,
          pendingSync = true,
          pendingScope = scope,
        )
        val second =
          async {
            runtime.setProfileAppearancePreference("ui.theme", AppearanceThemeFamily.Dash.rawValue)
          }
        releaseFirstWrite.complete(Unit)

        assertTrue(withTimeout(2_000) { first.await() })
        assertTrue(withTimeout(2_000) { second.await() })
        assertEquals(listOf("claw", "dash"), writtenValues)
        assertFalse(prefs.pendingAppearancePreferenceEntries(scope).containsKey("ui.theme"))
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun readOnlyProfileRefreshPreservesGatewayScopedPendingTheme() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
      val pendingScope = AppearancePreferenceScope(endpoint.stableId, profileId = null)

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      prefs.setAppearanceThemeFamily(
        AppearanceThemeFamily.Dash,
        pendingSync = true,
        pendingScope = pendingScope,
      )
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" -> "{}"
          "users.prefs.get" ->
            """{"status":"ok","entries":{"ui.theme":"claw","ui.themeMode":"dark"}}"""
          "users.self" -> error("operator.write unavailable")
          else -> error("Unexpected method: $method")
        }
      }

      try {
        ReflectionHelpers.callInstanceMethod<Unit>(
          runtime,
          "handleGatewayEvent",
          ClassParameter.from(String::class.java, "users.prefs.changed"),
          ClassParameter.from(String::class.java, null),
        )

        withTimeout(2_000) {
          prefs.appearanceThemeMode.first { it == AppearanceThemeMode.Dark }
        }
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(
          AppearanceThemeFamily.Dash.rawValue,
          prefs.pendingAppearancePreferenceEntries(pendingScope)["ui.theme"],
        )
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun noDurableIdentityUsesGatewayThemeFallbacks() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      prefs.setAppearanceAccentArgb(0xFF5A9BEFL)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" ->
            """{"config":{"ui":{"prefs":{"theme":"dash","themeMode":"light","accent":"#14B8A6"}}}}"""
          "users.prefs.get" -> """{"status":"no_durable_identity"}"""
          else -> error("Unexpected method: $method")
        }
      }

      try {
        invokeRefreshBrandingFromGateway(runtime)

        withTimeout(2_000) {
          prefs.appearanceThemeFamily.first { it == AppearanceThemeFamily.Dash }
        }
        withTimeout(2_000) {
          prefs.appearanceThemeMode.first { it == AppearanceThemeMode.Light }
        }
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
        assertEquals(null, prefs.appearanceAccentArgb.value)
        assertEquals(0xFF14B8A6L, runtime.gatewayAccentArgb.value)
        assertEquals(
          AppearancePreferenceEditMode.DeviceLocal,
          runtime.appearancePreferenceEditTargetSnapshot().mode,
        )
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun noDurableIdentityRetainsProfileEditUntilItsOwnerReconnects() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)

      val previousProfileScope = AppearancePreferenceScope(endpoint.stableId, "profile-a")
      prefs.setAppearanceThemeFamily(
        AppearanceThemeFamily.Dash,
        pendingSync = true,
        pendingScope = previousProfileScope,
      )
      prefs.setAppearanceAccentArgb(0xFFE96CB7L, pendingSync = true)
      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      val writtenThemes = mutableListOf<String?>()
      val writtenProfileIds = mutableListOf<String>()
      var durableProfileId = "profile-b"
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" ->
            """{"config":{"ui":{"prefs":{"theme":"claw"}}}}"""
          "users.prefs.get" -> """{"status":"no_durable_identity"}"""
          else -> error("Unexpected anonymous method: $method")
        }
      }

      try {
        invokeRefreshBrandingFromGateway(runtime)

        val gatewayScope = AppearancePreferenceScope(endpoint.stableId, profileId = null)
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertTrue(prefs.pendingAppearancePreferenceEntries().isEmpty())
        assertTrue(prefs.pendingAppearancePreferenceEntries(gatewayScope).isEmpty())
        assertEquals(
          AppearanceThemeFamily.Dash.rawValue,
          prefs.pendingAppearancePreferenceEntries(previousProfileScope)["ui.theme"],
        )
        assertFalse(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
        assertTrue(prefs.isAppearancePreferenceLocalOnly("ui.accent"))

        runtime.gatewayDataRequestOverrideForTests = { _, method, params ->
          when (method) {
            "config.get" -> "{}"
            "users.prefs.get" ->
              """{"status":"ok","entries":{"ui.theme":"claw"}}"""
            "users.self" -> """{"profile":{"id":"$durableProfileId"}}"""
            "users.prefs.set" -> {
              writtenProfileIds += durableProfileId
              writtenThemes +=
                json
                  .parseToJsonElement(checkNotNull(params))
                  .jsonObject["entries"]
                  ?.jsonObject
                  ?.get("ui.theme")
                  ?.jsonPrimitive
                  ?.content
              """{"status":"ok"}"""
            }
            else -> error("Unexpected durable method: $method")
          }
        }

        invokeRefreshBrandingFromGateway(runtime)

        assertTrue(writtenProfileIds.isEmpty())
        assertTrue(writtenThemes.isEmpty())
        assertEquals(
          AppearanceThemeFamily.Dash.rawValue,
          prefs.pendingAppearancePreferenceEntries(previousProfileScope)["ui.theme"],
        )
        assertEquals(AppearanceThemeFamily.Claw, prefs.appearanceThemeFamily.value)

        durableProfileId = "profile-a"
        invokeRefreshBrandingFromGateway(runtime)

        assertEquals(listOf("profile-a"), writtenProfileIds)
        assertEquals(listOf(AppearanceThemeFamily.Dash.rawValue), writtenThemes)
        assertTrue(prefs.pendingAppearancePreferenceEntries(previousProfileScope).isEmpty())
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertFalse(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun unavailableProfileReadPreservesExistingAppearance() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Tide)
      prefs.setAppearanceThemeMode(AppearanceThemeMode.Dark)
      prefs.setAppearanceAccentArgb(0xFF5A9BEFL)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
      val previousGatewayAccent = 0xFF123456L

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      ReflectionHelpers
        .getField<MutableStateFlow<Long?>>(runtime, "_gatewayAccentArgb")
        .value = previousGatewayAccent
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" ->
            """{"config":{"ui":{"prefs":{"theme":"dash","themeMode":"light","accent":"#14B8A6"}}}}"""
          "users.prefs.get" -> "not-json"
          else -> error("Unexpected method: $method")
        }
      }

      try {
        invokeRefreshBrandingFromGateway(runtime)

        assertEquals(AppearanceThemeFamily.Tide, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
        assertEquals(0xFF5A9BEFL, prefs.appearanceAccentArgb.value)
        assertEquals(previousGatewayAccent, runtime.gatewayAccentArgb.value)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun olderGatewayWithoutProfilePreferencesUsesConfigFallbacks() =
    runBlocking {
      for (catalogOmitsMethod in listOf(true, false)) {
        val app = RuntimeEnvironment.getApplication()
        val sharedPrefs =
          app.getSharedPreferences(
            "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
          )
        val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
        prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Tide)
        prefs.setAppearanceThemeMode(AppearanceThemeMode.Dark)
        val runtime = NodeRuntime(app, prefs)
        val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
        val preferenceRequests = AtomicInteger(0)

        ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
        ReflectionHelpers.setField(runtime, "operatorConnected", true)
        ReflectionHelpers
          .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
          .value =
          GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
        if (catalogOmitsMethod) {
          ReflectionHelpers.setField(runtime, "gatewayAdvertisedMethods", setOf("config.get"))
        }
        runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
          when (method) {
            "config.get" ->
              """{"config":{"ui":{"prefs":{"theme":"dash","themeMode":"light","accent":"#14B8A6"}}}}"""
            "users.prefs.get" -> {
              preferenceRequests.incrementAndGet()
              throw GatewayRequestRejected(
                GatewaySession.ErrorShape(
                  "INVALID_REQUEST",
                  "unknown method: users.prefs.get",
                ),
              )
            }
            else -> error("Unexpected method: $method")
          }
        }

        try {
          invokeRefreshBrandingFromGateway(runtime)

          assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
          assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
          assertEquals(0xFF14B8A6L, runtime.gatewayAccentArgb.value)
          assertEquals(if (catalogOmitsMethod) 0 else 1, preferenceRequests.get())
        } finally {
          closeNodeRuntimeTestFixture(runtime)
        }
      }
    }

  @Test
  fun readOnlyViewModelAppearanceChangesStayLocal() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication() as NodeApp
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val viewModel = MainViewModel(app, prefs, SavedStateHandle())
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
      val profileScope = AppearancePreferenceScope(endpoint.stableId, "profile-a")
      val preferenceWrites = AtomicInteger(0)

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      val operatorScopes =
        ReflectionHelpers.getField<MutableStateFlow<List<String>>>(runtime, "_operatorScopes")
      operatorScopes.value = listOf("operator.read")
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" -> "{}"
          "users.prefs.get" -> """{"status":"ok","entries":{}}"""
          "users.self" -> """{"profile":{"id":"profile-a"}}"""
          "users.prefs.set" -> {
            preferenceWrites.incrementAndGet()
            """{"status":"ok"}"""
          }
          else -> error("Unexpected method: $method")
        }
      }

      try {
        invokeRefreshBrandingFromGateway(runtime)
        ReflectionHelpers
          .getField<MutableStateFlow<NodeRuntime?>>(viewModel, "runtimeRef")
          .value = runtime

        assertEquals(
          AppearancePreferenceEditMode.DeviceLocal,
          runtime.appearancePreferenceEditTargetSnapshot().mode,
        )
        viewModel.setAppearanceThemeFamily(AppearanceThemeFamily.Dash)
        viewModel.setAppearanceThemeMode(AppearanceThemeMode.Dark)
        viewModel.setAppearanceAccentArgb(0xFFE96CB7L)

        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
        assertEquals(0xFFE96CB7L, prefs.appearanceAccentArgb.value)
        assertTrue(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
        assertTrue(prefs.isAppearancePreferenceLocalOnly("ui.themeMode"))
        assertTrue(prefs.isAppearancePreferenceLocalOnly("ui.accent"))
        repeat(2) {
          invokeRefreshBrandingFromGateway(runtime)
        }
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
        assertEquals(0xFFE96CB7L, prefs.appearanceAccentArgb.value)
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileScope).isEmpty())
        assertTrue(prefs.pendingAppearancePreferenceEntries().isEmpty())
        repeat(20) { yield() }
        assertEquals(0, preferenceWrites.get())

        operatorScopes.value = listOf("operator.write")
        viewModel.setAppearanceThemeFamily(AppearanceThemeFamily.Tide)
        withTimeout(2_000) {
          while (
            preferenceWrites.get() != 1 ||
            prefs.pendingAppearancePreferenceEntries(profileScope).containsKey("ui.theme")
          ) {
            yield()
          }
        }

        assertEquals(AppearanceThemeFamily.Tide, prefs.appearanceThemeFamily.value)
        assertFalse(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileScope).isEmpty())
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun offlineViewModelAppearanceEditStaysPendingForReconnect() {
    val app = RuntimeEnvironment.getApplication() as NodeApp
    app
      .getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    val sharedPrefs =
      app.getSharedPreferences(
        "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
    val viewModel = MainViewModel(app, prefs, SavedStateHandle())

    viewModel.setAppearanceThemeFamily(AppearanceThemeFamily.Dash)

    assertEquals(
      AppearanceThemeFamily.Dash.rawValue,
      prefs.pendingAppearancePreferenceEntries()["ui.theme"],
    )
    assertFalse(prefs.isAppearancePreferenceLocalOnly("ui.theme"))
  }

  @Test
  fun legacyThemeModeRemainsDeviceLocalAcrossWritableProfileRefresh() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      app
        .getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .putString("appearance.themeMode", AppearanceThemeMode.Light.rawValue)
        .commit()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
      val writtenModes = mutableListOf<String?>()

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      ReflectionHelpers
        .getField<MutableStateFlow<List<String>>>(runtime, "_operatorScopes")
        .value = listOf("operator.write")
      runtime.gatewayDataRequestOverrideForTests = { _, method, params ->
        when (method) {
          "config.get" -> "{}"
          "users.prefs.get" ->
            """{"status":"ok","entries":{"ui.themeMode":"dark"}}"""
          "users.self" -> """{"profile":{"id":"profile-a"}}"""
          "users.prefs.set" -> {
            writtenModes +=
              json
                .parseToJsonElement(checkNotNull(params))
                .jsonObject["entries"]
                ?.jsonObject
                ?.get("ui.themeMode")
                ?.jsonPrimitive
                ?.content
            """{"status":"ok"}"""
          }
          else -> error("Unexpected method: $method")
        }
      }

      try {
        invokeRefreshBrandingFromGateway(runtime)

        assertTrue(writtenModes.isEmpty())
        assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
        assertTrue(prefs.isAppearancePreferenceLocalOnly("ui.themeMode"))
        val profileScope = AppearancePreferenceScope(endpoint.stableId, "profile-a")
        assertTrue(prefs.pendingAppearancePreferenceEntries(profileScope).isEmpty())
        assertTrue(prefs.pendingAppearancePreferenceEntries().isEmpty())
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun missingProfileAccentKeepsGatewayFallbackOutOfTheLocalOverride() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      app
        .getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      prefs.setAppearanceAccentArgb(0xFF5A9BEFL)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" ->
            """{"config":{"ui":{"prefs":{"accent":"#14B8A6"}}}}"""
          "users.prefs.get" -> """{"status":"ok","entries":{}}"""
          "users.self" -> """{"profile":{"id":"profile-a"}}"""
          else -> error("Unexpected method: $method")
        }
      }

      try {
        invokeRefreshBrandingFromGateway(runtime)

        assertEquals(null, prefs.appearanceAccentArgb.value)
        assertEquals(0xFF14B8A6L, runtime.gatewayAccentArgb.value)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun olderProfileRefreshCannotOverwriteNewerAppearance() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
      val firstConfigStarted = CompletableDeferred<Unit>()
      val releaseFirstConfig = CompletableDeferred<Unit>()
      val configRequests = AtomicInteger(0)
      val preferenceRequests = AtomicInteger(0)

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" -> {
            if (configRequests.incrementAndGet() == 1) {
              firstConfigStarted.complete(Unit)
              releaseFirstConfig.await()
            }
            "{}"
          }
          "users.prefs.get" ->
            if (preferenceRequests.incrementAndGet() == 1) {
              """{"status":"ok","entries":{"ui.theme":"dash","ui.themeMode":"dark"}}"""
            } else {
              """{"status":"ok","entries":{"ui.theme":"claw","ui.themeMode":"light"}}"""
            }
          "users.self" -> """{"profile":{"id":"profile-a"}}"""
          else -> error("Unexpected method: $method")
        }
      }

      try {
        val older = async(Dispatchers.IO) { invokeRefreshBrandingFromGateway(runtime) }
        withTimeout(2_000) { firstConfigStarted.await() }
        val newer = async(Dispatchers.IO) { invokeRefreshBrandingFromGateway(runtime) }

        withTimeout(2_000) { newer.await() }
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)

        releaseFirstConfig.complete(Unit)
        withTimeout(2_000) { older.await() }
        assertEquals(AppearanceThemeFamily.Dash, prefs.appearanceThemeFamily.value)
        assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
      } finally {
        releaseFirstConfig.complete(Unit)
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  @Test
  fun gatewaySwitchCannotInterleaveWithUnscopedPreferenceAdoption() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val endpointA = GatewayEndpoint.manual("127.0.0.1", 18789)
      val endpointB = GatewayEndpoint.manual("127.0.0.2", 18789)
      val profileLookupStarted = CompletableDeferred<Unit>()
      val releaseProfileLookup = CompletableDeferred<Unit>()
      val prefsLockAcquired = CountDownLatch(1)
      val releasePrefsLock = CountDownLatch(1)
      val prefsLockOwner =
        Thread {
          synchronized(prefs) {
            prefsLockAcquired.countDown()
            releasePrefsLock.await()
          }
        }

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpointA)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      prefs.setAppearanceThemeFamily(AppearanceThemeFamily.Dash, pendingSync = true)
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" -> "{}"
          "users.prefs.get" ->
            """{"status":"ok","entries":{"ui.theme":"claw","ui.themeMode":"dark"}}"""
          "users.self" -> {
            profileLookupStarted.complete(Unit)
            releaseProfileLookup.await()
            error("operator.write unavailable")
          }
          else -> error("Unexpected method: $method")
        }
      }

      var gatewaySwitch: Thread? = null
      try {
        val refresh =
          async(Dispatchers.IO) {
            invokeRefreshBrandingFromGateway(runtime)
          }
        withTimeout(10_000) { profileLookupStarted.await() }

        prefsLockOwner.start()
        assertTrue(prefsLockAcquired.await(10, TimeUnit.SECONDS))
        val gatewayDataScopeLock = ReflectionHelpers.getField<Any>(runtime, "gatewayDataScopeLock")
        releaseProfileLookup.complete(Unit)
        assertTrue(awaitMonitorOwned(gatewayDataScopeLock))

        val gatewaySwitchStarted = CountDownLatch(1)
        gatewaySwitch =
          Thread {
            gatewaySwitchStarted.countDown()
            synchronized(gatewayDataScopeLock) {
              ReflectionHelpers.setField(runtime, "connectedEndpoint", endpointB)
            }
          }
        gatewaySwitch.start()
        assertTrue(gatewaySwitchStarted.await(10, TimeUnit.SECONDS))
        assertTrue(awaitThreadState(gatewaySwitch, Thread.State.BLOCKED))

        releasePrefsLock.countDown()
        withTimeout(10_000) { refresh.await() }
        gatewaySwitch.join(10_000)
        assertFalse(gatewaySwitch.isAlive)

        val adoptedScope = AppearancePreferenceScope(endpointA.stableId, profileId = null)
        assertEquals(
          AppearanceThemeFamily.Dash.rawValue,
          prefs.pendingAppearancePreferenceEntries(adoptedScope)["ui.theme"],
        )
        assertFalse(prefs.pendingAppearancePreferenceEntries().containsKey("ui.theme"))
      } finally {
        releaseProfileLookup.complete(Unit)
        releasePrefsLock.countDown()
        prefsLockOwner.join(10_000)
        gatewaySwitch?.join(10_000)
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  private fun awaitThreadState(
    thread: Thread,
    expected: Thread.State,
  ): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (thread.state != expected && System.nanoTime() < deadline) {
      Thread.sleep(10)
    }
    return thread.state == expected
  }

  private fun awaitMonitorOwned(monitor: Any): Boolean {
    val monitorIdentity = System.identityHashCode(monitor)
    val threadMxBean = ManagementFactory.getThreadMXBean()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
      val owned =
        threadMxBean
          .dumpAllThreads(true, false)
          .any { thread ->
            thread.lockedMonitors.any { locked -> locked.identityHashCode == monitorIdentity }
          }
      if (owned) return true
      Thread.sleep(10)
    }
    return false
  }

  @Test
  fun brandingRefreshPropagatesCancellation() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val runtime = NodeRuntime(app, SecurePrefs(app, securePrefsOverride = sharedPrefs))
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        if (method == "config.get") throw CancellationException("refresh cancelled")
        error("Unexpected method: $method")
      }

      try {
        var propagated: CancellationException? = null
        try {
          invokeRefreshBrandingFromGateway(runtime)
        } catch (cancelled: CancellationException) {
          propagated = cancelled
        }

        assertEquals("refresh cancelled", propagated?.message)
      } finally {
        closeNodeRuntimeTestFixture(runtime)
      }
    }

  private suspend fun invokeRefreshBrandingFromGateway(runtime: NodeRuntime) =
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

  @Test
  fun completedWriteCannotOverwriteANewerProfileOwner() =
    runBlocking {
      val app = RuntimeEnvironment.getApplication()
      val sharedPrefs =
        app.getSharedPreferences(
          "openclaw.node.appearance.runtime.test.${UUID.randomUUID()}",
          Context.MODE_PRIVATE,
        )
      val prefs = SecurePrefs(app, securePrefsOverride = sharedPrefs)
      val runtime = NodeRuntime(app, prefs)
      val endpoint = GatewayEndpoint.manual("127.0.0.1", 18789)
      val profileA = AppearancePreferenceScope(endpoint.stableId, "profile-a")
      val writeStarted = CompletableDeferred<Unit>()
      val releaseWrite = CompletableDeferred<Unit>()
      var activeProfileId = "profile-a"

      ReflectionHelpers.setField(runtime, "connectedEndpoint", endpoint)
      ReflectionHelpers.setField(runtime, "operatorConnected", true)
      ReflectionHelpers
        .getField<MutableStateFlow<GatewayConnectionDisplay>>(runtime, "_gatewayConnectionDisplay")
        .value =
        GatewayConnectionDisplay(isConnected = true, statusText = "Connected", problem = null)
      runtime.gatewayDataRequestOverrideForTests = { _, method, _ ->
        when (method) {
          "config.get" -> "{}"
          "users.prefs.get" ->
            """{"status":"ok","entries":{"ui.theme":"claw"}}"""
          "users.self" -> """{"profile":{"id":"$activeProfileId"}}"""
          "users.prefs.set" -> {
            writeStarted.complete(Unit)
            releaseWrite.await()
            """{"status":"ok"}"""
          }
          else -> error("Unexpected method: $method")
        }
      }

      try {
        prefs.setAppearanceThemeFamily(
          AppearanceThemeFamily.Dash,
          pendingSync = true,
          pendingScope = profileA,
        )
        val profileAWrite =
          async {
            runtime.setProfileAppearancePreference(
              "ui.theme",
              AppearanceThemeFamily.Dash.rawValue,
            )
          }
        withTimeout(2_000) { writeStarted.await() }

        activeProfileId = "profile-b"
        invokeRefreshBrandingFromGateway(runtime)

        assertEquals(AppearanceThemeFamily.Claw, prefs.appearanceThemeFamily.value)
        assertEquals(
          AppearanceThemeFamily.Dash.rawValue,
          prefs.pendingAppearancePreferenceEntries(profileA)["ui.theme"],
        )

        releaseWrite.complete(Unit)

        assertTrue(withTimeout(2_000) { profileAWrite.await() })
        assertEquals(AppearanceThemeFamily.Claw, prefs.appearanceThemeFamily.value)
        assertEquals(
          AppearanceThemeFamily.Dash.rawValue,
          prefs.pendingAppearancePreferenceEntries(profileA)["ui.theme"],
        )
      } finally {
        releaseWrite.complete(Unit)
        closeNodeRuntimeTestFixture(runtime)
      }
    }
}
