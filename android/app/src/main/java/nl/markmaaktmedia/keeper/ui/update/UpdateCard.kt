package nl.markmaaktmedia.keeper.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.launch
import nl.markmaaktmedia.keeper.R
import nl.markmaaktmedia.keeper.graph
import nl.markmaaktmedia.keeper.ui.components.bouncyClickable
import nl.markmaaktmedia.keeper.ui.theme.KeeperIcons
import nl.markmaaktmedia.keeper.ui.theme.KeeperMotion
import nl.markmaaktmedia.keeper.ui.theme.LocalKeeperExtraColors
import nl.markmaaktmedia.keeper.ui.theme.PillShape
import nl.markmaaktmedia.keeper.update.ReleaseInfo
import nl.markmaaktmedia.keeper.update.UpdateState
import nl.markmaaktmedia.keeper.update.releaseHighlights

/** Where the card is: waiting for a tap, downloading, or (the download is done) installing. */
private enum class Phase { Rest, Busy, Installing }

/** What the card shows, kept while it slides away: by then the state has moved on and would change the card on its way out. */
private class Shown(var release: ReleaseInfo? = null, var failure: UpdateState.Failed? = null, var phase: Phase = Phase.Rest)

private val CardShape = RoundedCornerShape(24.dp)
private val IconSize = 46.dp
private val ButtonHeight = 46.dp

/**
 * The card at the top of the app when a new version is out: an icon, "New version X", what is new in two short lines, and Later and
 * Update side by side across the whole width. Update morphs into a progress bar over the whole card while Later slides away; when the
 * download is done it installs at once (no second tap: see [nl.markmaaktmedia.keeper.update.UpdateRepository.install]) and the bar
 * says so. Android asks for a confirmation itself the first time, or when it will not install quietly; the question opens by itself.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = context.graph
    val updater = graph.updater
    val state by updater.state.collectAsState()
    val dismissed by graph.prefs.dismissedUpdate.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Back from the "install unknown apps" screen with the switch on: carry on where it stopped (in the app's scope, not this screen's).
        updater.resume()
        // An update that finished downloading while the app was out of sight asks its question now.
        updater.resumeConfirmation()
    }

    val release: ReleaseInfo? = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.ReadyToInstall -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.AwaitingConfirmation -> s.release
        else -> null
    }
    val failed = state as? UpdateState.Failed
    // A failure stays until it is retried or the app is reopened; a release once put off with Later stays hidden.
    val visible = failed != null || (release != null && release.tag != dismissed)
    val shown = remember { Shown() }
    if (visible) {
        shown.release = release
        shown.failure = failed
        shown.phase = when (state) {
            is UpdateState.Downloading -> Phase.Busy
            is UpdateState.ReadyToInstall, is UpdateState.Installing, is UpdateState.AwaitingConfirmation -> Phase.Installing
            else -> Phase.Rest
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(KeeperMotion.fadeSpec()) + expandVertically(KeeperMotion.sizeSpring()),
        exit = fadeOut(KeeperMotion.fadeSpec()) + shrinkVertically(KeeperMotion.sizeSpring()),
        modifier = modifier,
    ) {
        val failure = shown.failure
        val shownRelease = shown.release
        when {
            failure != null -> FailedCard(failure.reason) { updater.startRetry() }
            shownRelease != null -> UpdateContent(
                release = shownRelease,
                phase = shown.phase,
                state = state,
                currentVersion = updater.currentVersion,
                onBar = {
                    when (val s = state) {
                        is UpdateState.Available -> updater.startUpdate(s.release)
                        is UpdateState.NeedsPermission -> updater.openInstallPermissionSettings()
                        // Android did not let the question open by itself (the app was not in front): the bar opens it.
                        is UpdateState.AwaitingConfirmation -> updater.launchConfirmation()
                        else -> Unit
                    }
                },
                onLater = { scope.launch { graph.prefs.setDismissedUpdate(shownRelease.tag) } },
            )
        }
    }
}

@Composable
private fun UpdateContent(
    release: ReleaseInfo, phase: Phase, state: UpdateState, currentVersion: String, onBar: () -> Unit, onLater: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dutch = LocalConfiguration.current.locales[0].language == "nl"
    val lines = remember(release.body, dutch) { releaseHighlights(release.body, dutch) }
    val real = (state as? UpdateState.Downloading)?.progress
    // The real progress; the bar and the numbers follow it, the bar a little softer (a spring) since it arrives in steps of a percent.
    val progress = when (phase) {
        Phase.Rest -> 0f
        Phase.Busy -> real ?: 0f
        Phase.Installing -> 1f
    }
    Column(Modifier.fillMaxWidth().clip(CardShape).background(colors.surfaceContainer).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdateIcon(done = phase == Phase.Installing)
            AnimatedContent(
                targetState = phase, transitionSpec = slideUp(2), modifier = Modifier.weight(1f), label = "updateText",
            ) { p ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(if (p == Phase.Installing) R.string.update_card_installing_title else R.string.update_card_title, release.versionName),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (p) {
                            Phase.Rest -> if (state is UpdateState.NeedsPermission) stringResource(R.string.update_needs_permission) else stringResource(R.string.update_on_version, currentVersion)
                            Phase.Busy -> downloaded(release, progress)
                            Phase.Installing -> if (state is UpdateState.AwaitingConfirmation) stringResource(R.string.update_confirm_hint) else stringResource(R.string.update_card_installing_sub)
                        },
                        // Gelijke cijferbreedte alleen bij de teller, zodat die niet heen en weer springt; in een versienummer maakt het de punten te wijd.
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = if (p == Phase.Busy) "tnum" else null),
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Two short lines of what is new, under the title; they slide away when the install starts.
        AnimatedVisibility(
            visible = lines.isNotEmpty() && phase != Phase.Installing,
            enter = fadeIn(KeeperMotion.fadeSpec()) + expandVertically(KeeperMotion.sizeSpring()),
            exit = fadeOut(KeeperMotion.fadeSpec()) + shrinkVertically(KeeperMotion.sizeSpring()),
        ) {
            Column(Modifier.padding(start = IconSize + 12.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Actions(
            phase = phase, progress = progress,
            restLabel = stringResource(if (state is UpdateState.NeedsPermission) R.string.update_allow else R.string.update_card_update),
            barEnabled = phase == Phase.Rest || state is UpdateState.AwaitingConfirmation,
            onBar = onBar, onLater = onLater,
        )
    }
}

/** "6.3 of 22 MB", or just the percentage when the release does not say how big the file is. */
@Composable
private fun downloaded(release: ReleaseInfo, progress: Float): String {
    val total = release.apkSizeBytes
    if (total <= 0) return stringResource(R.string.update_downloading, (progress * 100).toInt())
    val symbols = DecimalFormatSymbols(Locale.getDefault())
    val done = DecimalFormat("0.0", symbols).format(total * progress / 1e6)
    val all = DecimalFormat("0.#", symbols).format(total / 1e6)
    return stringResource(R.string.update_card_progress, done, all)
}

/** The square icon: a sparkle on the primary container, a green circle with a tick once it installs. */
@Composable
private fun UpdateIcon(done: Boolean) {
    val colors = MaterialTheme.colorScheme
    val green = LocalKeeperExtraColors.current.charging
    val radius by animateDpAsState(if (done) IconSize / 2 else 15.dp, KeeperMotion.spatial(), label = "updateIconRadius")
    val fill by animateColorAsState(
        if (done) green.copy(alpha = 0.2f).compositeOver(colors.surfaceContainer) else colors.primaryContainer, KeeperMotion.colourSpec(), label = "updateIconFill",
    )
    val ink by animateColorAsState(if (done) green else colors.onPrimaryContainer, KeeperMotion.colourSpec(), label = "updateIconInk")
    Box(Modifier.size(IconSize).clip(RoundedCornerShape(radius)).background(fill), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = done,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.3f) + fadeIn(tween(220))) togetherWith
                    (scaleOut(tween(160), targetScale = 0.3f) + fadeOut(tween(160)))
            },
            label = "updateIcon",
        ) { d ->
            Icon(if (d) KeeperIcons.Check else KeeperIcons.Sparkle, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Later and Update share the width (one part and two parts). When the update starts Later shrinks to nothing and fades, and Update
 * grows into a bar over the whole width: the fill follows the download, the percentage sits in the middle. Done, the bar stays full
 * and says "Installing…" with a soft sheen.
 */
@Composable
private fun Actions(phase: Phase, progress: Float, restLabel: String, barEnabled: Boolean, onBar: () -> Unit, onLater: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val laterShare by animateFloatAsState(
        if (phase == Phase.Rest) 1f else 0f,
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow), label = "laterShare",
    )
    // Doorzichtigheid is een tween, geen veer: een veer laat het knopje flikkeren.
    val laterAlpha by animateFloatAsState(if (phase == Phase.Rest) 1f else 0f, tween(260), label = "laterAlpha")
    val barFill by animateColorAsState(
        when (phase) { Phase.Rest -> colors.primary; Phase.Busy -> colors.surfaceContainerHighest; Phase.Installing -> colors.primaryContainer },
        KeeperMotion.colourSpec(), label = "barFill",
    )
    val ink by animateColorAsState(if (phase == Phase.Rest) colors.onPrimary else colors.onPrimaryContainer, KeeperMotion.colourSpec(), label = "barInk")
    val shownProgress by animateFloatAsState(progress, KeeperMotion.spatial(), label = "updateProgress")
    val fillAlpha by animateFloatAsState(if (phase == Phase.Busy) 1f else 0f, tween(400), label = "fillAlpha")

    Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(ButtonHeight)) {
        if (laterShare > 0.005f) {
            val share = laterShare.coerceIn(0f, 1f)
            Box(
                Modifier.weight(share.coerceAtLeast(0.001f)).fillMaxHeight().graphicsLayer { alpha = laterAlpha }
                    .clip(PillShape).background(colors.surfaceContainerHighest).bouncyClickable(enabled = phase == Phase.Rest, onClick = onLater),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.update_later), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.primary, maxLines = 1, softWrap = false,
                )
            }
            Spacer(Modifier.width(8.dp * share))
        }
        Box(
            Modifier.weight(2f).fillMaxHeight().clip(PillShape).background(barFill).bouncyClickable(enabled = barEnabled, onClick = onBar),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(shownProgress.coerceIn(0f, 1f)).graphicsLayer { alpha = fillAlpha }
                    .clip(PillShape).background(colors.primaryContainer),
            )
            if (phase == Phase.Installing) Sheen(Modifier.matchParentSize())
            AnimatedContent(
                targetState = phase, transitionSpec = slideUp(3), modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center, label = "updateLabel",
            ) { p ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when (p) {
                            Phase.Rest -> restLabel
                            Phase.Busy -> "${(progress * 100).toInt()}%"
                            Phase.Installing -> stringResource(R.string.update_card_installing)
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = ink, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A soft light that runs over the bar while it installs. */
@Composable
private fun Sheen(modifier: Modifier) {
    val x by rememberInfiniteTransition(label = "sheen").animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "sheenX")
    Box(
        modifier.drawBehind {
            val band = size.width * 0.5f
            val start = x * (size.width + band) - band
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent), startX = start, endX = start + band),
                topLeft = Offset(start, 0f), size = Size(band, size.height),
            )
        },
    )
}

/** The old text slides up and away, the new one comes in from below: a spring for the movement, tweens for the fade. */
private fun slideUp(divisor: Int): AnimatedContentTransitionScope<Phase>.() -> ContentTransform = {
    val move: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
    (slideInVertically(move) { it / divisor } + fadeIn(tween(260))) togetherWith (slideOutVertically(tween(260, easing = KeeperMotion.Standard)) { -it / divisor } + fadeOut(tween(160)))
}

/** The download or the install failed: why, and one wide button to try again. */
@Composable
private fun FailedCard(reason: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(CardShape).background(colors.surfaceContainer).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(IconSize).clip(RoundedCornerShape(15.dp)).background(colors.errorContainer), contentAlignment = Alignment.Center) {
                Icon(KeeperIcons.Error, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.padding(top = 14.dp).fillMaxWidth().height(ButtonHeight).clip(PillShape).background(colors.primary).bouncyClickable(onClick = onRetry),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.update_card_retry), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = colors.onPrimary)
        }
    }
}
