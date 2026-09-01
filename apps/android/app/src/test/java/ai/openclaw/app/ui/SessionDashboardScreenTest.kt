package ai.openclaw.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDashboardScreenTest {
  @Test
  fun dashboardUrlUsesDirectControlUiSessionRoute() {
    val url =
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com:8443/",
        sessionKey = "agent:ops:telegram:12345",
      )

    assertEquals(
      "https://gateway.example.com:8443/dashboard/ops/telegram/12345",
      url,
    )
  }

  @Test
  fun originRuleDropsBasePathAndKeepsPort() {
    assertEquals(
      "https://gateway.example.com:8443",
      controlUiOriginRule("https://gateway.example.com:8443/openclaw"),
    )
    assertEquals("http://[::1]:18789", controlUiOriginRule("http://[::1]:18789"))
  }

  @Test
  fun dashboardUrlKeepsConfiguredControlUiBasePathAndDropsOldQuery() {
    val url =
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com:8443/openclaw?stale=true#old",
        sessionKey = "agent:main:qa",
      )

    assertEquals(
      "https://gateway.example.com:8443/openclaw/dashboard/main/qa",
      url,
    )
  }

  @Test
  fun dashboardUrlCollapsesMainKeysToAgentDashboard() {
    assertEquals(
      "https://gateway.example.com/dashboard/research",
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com",
        sessionKey = "agent:research:workspace",
        mainSessionKey = "agent:research:workspace",
      ),
    )
    assertEquals(
      "https://gateway.example.com/dashboard/research",
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com",
        sessionKey = "main",
        fallbackAgentId = "research",
      ),
    )
  }

  @Test
  fun dashboardUrlEscapesDotTildeAndShortLiteralSegments() {
    assertEquals(
      "https://gateway.example.com/dashboard/main/cron/~dot/~dotdot/~~dot",
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com",
        sessionKey = "agent:main:cron:.:..:~dot",
      ),
    )
    assertEquals(
      "https://gateway.example.com/dashboard/main/~key/release-deadbeef",
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com",
        sessionKey = "agent:main:release-deadbeef",
      ),
    )
    assertEquals(
      "https://gateway.example.com/dashboard/main/channel/release%2Ejs",
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com",
        sessionKey = "agent:main:channel:release.js",
      ),
    )
  }

  @Test
  fun dashboardUrlUsesUuidPrefixLikeTheWebUiContract() {
    assertEquals(
      "https://gateway.example.com/dashboard/main/12345678",
      sessionDashboardUrl(
        baseUrl = "https://gateway.example.com",
        sessionKey = "agent:main:dashboard:12345678-90ab-cdef-1234-567890abcdef",
      ),
    )
  }
}
