package com.bukovinafilip.gradey.feature.recap

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.bukovinafilip.gradey.domain.Recap
import com.bukovinafilip.gradey.domain.RecapSlide
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.RecapColors
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How long a slide stays before the next one. */
private const val SlideSeconds = 6.5f

/** The share image is drawn once a slide has settled, and skipped for slides tapped past. */
private const val ShareSettleMillis = 600L

/**
 * The slides are posters set in very large type. With large text switched on the whole story is
 * drawn bigger, in proportion, up to this much; past it the slides would no longer fit a phone.
 */
private const val MaximumZoom = 1.3f

private val Ink = RecapColors.Ink

/**
 * Gradey Recap as full-screen stories (iOS `RecapStoryView`): tap the right side for the next
 * slide, the left third for the previous one, hold to pause, swipe down to close. With TalkBack on
 * the slides stay put and Previous and Next buttons move between them. The surface is always light,
 * with ink text on the slide's colour.
 */
@Composable
fun RecapStoryScreen(
    store: RecapStore,
    periodID: String?,
    source: RecapSource,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val period = remember(periodID) { store.period(periodID) }
    if (period == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val texts = rememberRecapTexts()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val talkBack = rememberTouchExploration()
    val currentSource by rememberUpdatedState(source)

    var recap by remember(period.id) { mutableStateOf<Recap?>(null) }
    // The slide and the pause survive rotation. A story that was closed, or another period's, starts over:
    // the shell keeps this destination's saved state after it is left, and restoring ignores the period.
    val position = rememberSaveable(saver = StoryPositionSaver) { mutableStateOf(StoryPosition(period.id)) }
    remember(period.id) { if (position.value.closed || position.value.periodID != period.id) position.value = StoryPosition(period.id) }
    val index = position.value.index
    val pausedByUser = position.value.paused
    fun show(slideIndex: Int) { position.value = position.value.copy(index = slideIndex) }
    fun togglePause() { position.value = position.value.copy(paused = !position.value.paused) }
    val progress = remember(period.id) { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    val paused by rememberUpdatedState(held || pausedByUser)
    val close = { position.value = position.value.copy(closed = true); onClose() }
    BackHandler(onBack = close)
    val slides = recap?.slides.orEmpty()
    val slide = slides.getOrNull(index)
    val currentSlide by rememberUpdatedState(slide)
    // Read through state, so a timer that started before the full recap arrived still sees its slides.
    val lastIndex by rememberUpdatedState(slides.lastIndex)

    fun next() { position.value.index.let { if (it < lastIndex) show(it + 1) } }
    fun previous() { position.value.index.let { if (it > 0) show(it - 1) else progress.floatValue = 0f } }

    LaunchedEffect(period.id) {
        // Seen once it is opened, even if it is closed before it finishes loading.
        store.hide(period.id)
        if (recap == null) recap = store.summary(period, currentSource)
        val full = store.recap(period, currentSource)
        // The timetable slide may arrive late; stay on the slide being read.
        val reading = currentSlide
        recap = full
        show((full.slides.indexOf(reading).takeIf { it >= 0 } ?: position.value.index).coerceIn(0, full.slides.lastIndex.coerceAtLeast(0)))
    }
    // The last slide stays put; TalkBack users move on with the buttons.
    LaunchedEffect(index, recap != null, talkBack) {
        progress.floatValue = if (lastIndex >= 0 && index == lastIndex) 1f else 0f
        if (recap == null || talkBack || index >= lastIndex) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (progress.floatValue < 1f) {
            val now = withFrameNanos { it }
            if (!paused) progress.floatValue = (progress.floatValue + (now - last) / 1_000_000_000f / SlideSeconds).coerceAtMost(1f)
            last = now
        }
        next()
    }
    var feltIndex by remember { mutableIntStateOf(index) }
    LaunchedEffect(index) {
        if (feltIndex != index) { feltIndex = index; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    LightSystemBars()

    val shareLayer = rememberGraphicsLayer()
    var shareSlide by remember { mutableStateOf<RecapSlide?>(null) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(index, recap) {
        delay(ShareSettleMillis)
        shareSlide = slide
    }

    val background by animateColorAsState(
        targetValue = recap?.let { slide?.backgroundColor(it) } ?: RecapColors.Emerald,
        animationSpec = tween(if (reduceMotion) 0 else 350), label = "recapBackground",
    )
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val systemDensity = LocalDensity.current
    val density = remember(systemDensity) { Density(systemDensity.density * systemDensity.fontScale.coerceIn(1f, MaximumZoom), fontScale = 1f) }
    CompositionLocalProvider(LocalDensity provides density) {
        Box(
            modifier.fillMaxSize().drawBehind { drawRect(background) }.testTag("recapStory")
                .focusRequester(focus).focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> { next(); true }
                        Key.DirectionLeft -> { previous(); true }
                        Key.Spacebar -> { togglePause(); true }
                        else -> false
                    }
                },
        ) {
            val shown = recap
            if (shown != null && slide != null) {
                AnimatedContent(
                    targetState = slide,
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
                    contentAlignment = Alignment.TopCenter,
                    transitionSpec = {
                        val enter = if (reduceMotion) fadeIn(tween(200)) else fadeIn(tween(300)) + slideInVertically(tween(300)) { with(density) { 24.dp.roundToPx() } }
                        enter togetherWith fadeOut(tween(if (reduceMotion) 200 else 150))
                    },
                    label = "recapSlide",
                ) { target ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        RecapSlideContent(
                            shown, target, texts,
                            Modifier.widthIn(max = 520.dp).padding(start = GradeySpacing.xl, end = GradeySpacing.xl, top = 72.dp, bottom = 96.dp),
                        )
                    }
                }
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(GradeySpacing.xl), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
                ) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = Ink, strokeWidth = 3.dp)
                    Text(texts.string(R.string.recap_loading), style = GradeyType.title3, color = Ink)
                }
            }

            // Taps, holds and the swipe down. The buttons sit above this layer.
            Box(
                Modifier.fillMaxSize().clearAndSetSemantics { }.pointerInput(Unit) {
                    val tapSlop = 20.dp.toPx()
                    val closeDistance = 120.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        held = true
                        var position = down.position
                        var released = false
                        var releasedAt = down.uptimeMillis
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                position = change.position
                                if (!change.pressed) { released = true; releasedAt = change.uptimeMillis; break }
                            }
                        } finally {
                            held = false
                        }
                        val dx = position.x - down.position.x
                        val dy = position.y - down.position.y
                        when {
                            dy > closeDistance && abs(dx) < dy -> close()
                            released && releasedAt - down.uptimeMillis < 300 && abs(dx) < tapSlop && abs(dy) < tapSlop ->
                                if (down.position.x < size.width / 3f) previous() else next()
                        }
                    }
                },
            )

            // Header: one segment per slide, the period, pause and close.
            Column(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.systemBars).widthIn(max = 560.dp).fillMaxWidth()
                    .padding(start = GradeySpacing.lg, end = GradeySpacing.xs, top = GradeySpacing.md),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
            ) {
                val progressLabel = texts.string(R.string.recap_progress, index + 1, maxOf(slides.size, 1))
                StoryProgress(
                    count = maxOf(slides.size, 1), index = index, progress = { progress.floatValue },
                    modifier = Modifier.padding(end = GradeySpacing.md).clearAndSetSemantics { contentDescription = progressLabel },
                )
                Row(Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch), verticalAlignment = Alignment.CenterVertically) {
                    // The intro and summary already say which period it is.
                    Box(Modifier.weight(1f)) {
                        if (slide != RecapSlide.INTRO && slide != RecapSlide.SUMMARY) Text(
                            texts.title(period), style = GradeyType.subheadlineSemibold.tabular(), color = Ink.copy(alpha = 0.75f), maxLines = 1,
                        )
                    }
                    if (!talkBack) StoryIconButton(
                        if (pausedByUser) HugeIcons.Play else HugeIcons.Pause, texts.string(if (pausedByUser) R.string.recap_play else R.string.recap_pause),
                        Modifier.testTag("recapPauseButton"),
                    ) { togglePause() }
                    StoryIconButton(HugeIcons.Cancel01, texts.string(R.string.recap_close), Modifier.testTag("recapCloseButton"), onClick = close)
                }
            }

            // Footer: Previous and Next for TalkBack, and Share.
            Row(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.systemBars).widthIn(max = 560.dp).fillMaxWidth()
                    .padding(horizontal = GradeySpacing.lg).padding(bottom = GradeySpacing.lg),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
            ) {
                if (talkBack) {
                    StoryTextButton(texts.string(R.string.recap_previous), enabled = index > 0, modifier = Modifier.testTag("recapPreviousButton")) { previous() }
                    StoryTextButton(texts.string(R.string.recap_next), enabled = index < slides.lastIndex, modifier = Modifier.testTag("recapNextButton")) { next() }
                }
                Box(Modifier.weight(1f))
                if (shown != null && slide != null) {
                    val ready = shareSlide == slide && !sharing
                    Row(
                        Modifier.heightIn(min = GradeyMetrics.minTouch).clip(CircleShape).background(Ink.copy(alpha = if (ready) 1f else 0.5f))
                            .clickable(enabled = ready, role = Role.Button) {
                                sharing = true
                                scope.launch {
                                    try { RecapShareImage.save(context, shareLayer, shown, index + 1)?.let { RecapShareImage.share(context, it) } }
                                    finally { sharing = false }
                                }
                            }
                            .padding(horizontal = GradeySpacing.lg, vertical = GradeySpacing.md).testTag("recapShareButton"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                    ) {
                        GradeyIcon(HugeIcons.Share03, size = 18.dp, tint = Color.White)
                        Text(texts.string(R.string.recap_share), style = GradeyType.headline, color = Color.White)
                    }
                }
            }

            val shared = shareSlide
            if (shown != null && shared != null && shared in slides) RecapShareCanvas(shown, shared, texts, shareLayer)
        }
    }
}

/** Where the story is. [closed] is set on the way out, so the next opening starts from the first slide. */
private data class StoryPosition(val periodID: String, val index: Int = 0, val paused: Boolean = false, val closed: Boolean = false)

private val StoryPositionSaver = listSaver<MutableState<StoryPosition>, Any>(
    save = { listOf(it.value.periodID, it.value.index, it.value.paused, it.value.closed) },
    restore = { mutableStateOf(StoryPosition(it[0] as String, it[1] as Int, it[2] as Boolean, it[3] as Boolean)) },
)

/** One segment per slide, like stories: done ones full, the current one filling. [progress] is read while drawing. */
@Composable
private fun StoryProgress(count: Int, index: Int, progress: () -> Float, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { segment ->
            Box(
                Modifier.weight(1f).height(4.dp).drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(Ink.copy(alpha = 0.2f), cornerRadius = radius)
                    val fill = if (segment < index) 1f else if (segment > index) 0f else progress().coerceIn(0f, 1f)
                    if (fill > 0f) drawRoundRect(Ink.copy(alpha = 0.85f), size = Size(maxOf(size.width * fill, size.height), size.height), cornerRadius = radius)
                },
            )
        }
    }
}

@Composable
private fun StoryIconButton(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(GradeyMetrics.minTouch).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { GradeyIcon(icon, size = 20.dp, tint = Ink) }
}

@Composable
private fun StoryTextButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Ink.copy(alpha = if (enabled) 1f else 0.4f)
    Box(
        modifier.heightIn(min = GradeyMetrics.minTouch).clip(CircleShape).border(1.5.dp, color, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = GradeySpacing.lg, vertical = GradeySpacing.sm),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = GradeyType.subheadlineBold, color = color) }
}

/** True while TalkBack (or another touch-exploration service) is on. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** Dark status and navigation bar icons while the light story is on screen; the app's own choice comes back after. */
@Composable
private fun LightSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            if (status != null) controller.isAppearanceLightStatusBars = status
            if (navigation != null) controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
