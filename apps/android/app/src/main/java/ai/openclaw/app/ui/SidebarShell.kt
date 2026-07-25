package ai.openclaw.app.ui

import ai.openclaw.app.ui.design.ClawTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal const val sidebarPersistentWidthThresholdDp = 980f
internal const val sidebarMaximumWidthDp = 340f
internal const val sidebarPersistentIdealWidthDp = 316f
internal const val sidebarCompactWidthFraction = 0.86f

internal enum class SidebarLayoutMode {
  Drawer,
  Persistent,
}

internal fun sidebarLayoutMode(availableWidthDp: Float): SidebarLayoutMode =
  if (availableWidthDp >= sidebarPersistentWidthThresholdDp) {
    SidebarLayoutMode.Persistent
  } else {
    SidebarLayoutMode.Drawer
  }

internal fun sidebarWidthDp(availableWidthDp: Float): Float =
  when (sidebarLayoutMode(availableWidthDp)) {
    SidebarLayoutMode.Drawer -> min(sidebarMaximumWidthDp, availableWidthDp * sidebarCompactWidthFraction)
    SidebarLayoutMode.Persistent ->
      (availableWidthDp * 0.25f).coerceIn(sidebarPersistentIdealWidthDp, sidebarMaximumWidthDp)
  }

internal fun sidebarContentWidthDp(availableWidthDp: Float): Float =
  when (sidebarLayoutMode(availableWidthDp)) {
    SidebarLayoutMode.Drawer -> availableWidthDp
    SidebarLayoutMode.Persistent -> (availableWidthDp - sidebarWidthDp(availableWidthDp)).coerceAtLeast(0f)
  }

internal fun sidebarContentTranslationPx(
  drawerOpen: Boolean,
  persistent: Boolean,
  sidebarWidthPx: Float,
  rightToLeft: Boolean,
): Float {
  if (persistent || !drawerOpen) return 0f
  return if (rightToLeft) -sidebarWidthPx else sidebarWidthPx
}

/**
 * Stable adaptive shell shared by every top-level destination.
 *
 * The destination subtree is always composed exactly once. Compact layouts reveal
 * the sidebar by translating that subtree; wide layouts inset the same subtree
 * beside a persistent sidebar.
 */
@Composable
internal fun AdaptiveSidebarShell(
  drawerOpen: Boolean,
  onDrawerOpenChange: (Boolean) -> Unit,
  sidebar: @Composable (showCloseButton: Boolean) -> Unit,
  content: @Composable (showSidebarButton: Boolean) -> Unit,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val persistent = sidebarLayoutMode(maxWidth.value) == SidebarLayoutMode.Persistent
    val sidebarWidth = sidebarWidthDp(maxWidth.value).dp
    val contentWidth = sidebarContentWidthDp(maxWidth.value).dp
    val density = LocalDensity.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sidebarWidthPx = with(density) { sidebarWidth.toPx() }
    val targetTranslation =
      sidebarContentTranslationPx(
        drawerOpen = drawerOpen,
        persistent = persistent,
        sidebarWidthPx = sidebarWidthPx,
        rightToLeft = rightToLeft,
      )
    val contentTranslation by
      animateFloatAsState(
        targetValue = targetTranslation,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "sidebar-content-translation",
      )
    val sidebarVisible = persistent || drawerOpen
    val compactShape =
      RoundedCornerShape(
        topStart = 8.dp,
        topEnd = 28.dp,
        bottomStart = 28.dp,
        bottomEnd = 28.dp,
      )

    LaunchedEffect(persistent) {
      if (persistent && drawerOpen) onDrawerOpenChange(false)
    }
    BackHandler(enabled = drawerOpen && !persistent) {
      onDrawerOpenChange(false)
    }

    Surface(
      modifier =
        Modifier
          .width(sidebarWidth)
          .fillMaxHeight()
          .align(Alignment.CenterStart)
          .testTag("adaptive-sidebar")
          .then(if (sidebarVisible) Modifier else Modifier.clearAndSetSemantics {}),
      color = ClawTheme.colors.canvas,
    ) {
      sidebar(!persistent)
    }

    Surface(
      modifier =
        Modifier
          .width(contentWidth)
          .fillMaxHeight()
          .align(if (persistent) Alignment.CenterEnd else Alignment.CenterStart)
          .graphicsLayer {
            translationX = contentTranslation
            shape = if (!persistent && drawerOpen) compactShape else RectangleShape
            clip = !persistent && drawerOpen
            shadowElevation = if (!persistent && drawerOpen) 18.dp.toPx() else 0f
          }.clip(if (!persistent && drawerOpen) compactShape else RectangleShape)
          .testTag("adaptive-sidebar-content")
          .then(if (drawerOpen && !persistent) Modifier.clearAndSetSemantics {} else Modifier),
      color = ClawTheme.colors.canvas,
    ) {
      content(!persistent)
    }

    if (drawerOpen && !persistent) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = contentTranslation }
            .pointerInput(onDrawerOpenChange) {
              detectTapGestures { onDrawerOpenChange(false) }
            }.clearAndSetSemantics {},
      )
    }
  }
}
