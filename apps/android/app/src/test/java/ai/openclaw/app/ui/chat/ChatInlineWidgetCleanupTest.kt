package ai.openclaw.app.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInlineWidgetCleanupTest {
  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun pinnedClientCleanupIsDeferredFromTheCaller() =
    runTest {
      val client = OkHttpClient()
      var cleanupRan = false

      closePinnedWidgetClientAsync(
        client = client,
        scope = this,
        cleanup = { cleanupRan = true },
      )

      assertFalse(cleanupRan)

      runCurrent()

      assertTrue(cleanupRan)
    }
}
