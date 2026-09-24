package app.smallthingz.reverb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val AppTopBarContentHeight = 66.dp

@Composable
internal fun AppTopBar(
    onBrandClick: () -> Unit,
    onIncidentsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    hasIncidents: Boolean,
    onBackClick: (() -> Unit)? = null,
    applyStatusBarPadding: Boolean = true,
    barHeight: Dp = AppTopBarContentHeight,
) {
    val chrome = appChrome()
    val noiseBrush = rememberAppNoiseBrush(APP_NOISE_SEED_TOP_BAR)
    val topBarModifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .appNoise(noiseBrush)
        .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
        .height(barHeight)
        .padding(horizontal = 14.dp)
    Box(
        modifier = topBarModifier,
    ) {
        if (onBackClick != null) {
            TopBarAction(
                icon = AppIcons.back,
                contentDescription = stringResource(R.string.back),
                onClick = onBackClick,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        Surface(
            onClick = onBrandClick,
            modifier = Modifier
                .align(Alignment.Center)
                .size(46.dp),
            shape = ReverbAppIconShape,
            color = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            ReverbBrandMark(
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize(),
            )
        }

        val incidentColor = if (hasIncidents) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        TopBarAction(
            icon = AppIcons.incidents,
            contentDescription = stringResource(R.string.open_incidents),
            onClick = onIncidentsClick,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 56.dp),
            tint = incidentColor,
            borderColor = if (hasIncidents) incidentColor.copy(alpha = 0.55f) else chrome.border,
        )
        TopBarAction(
            icon = AppIcons.settings,
            contentDescription = stringResource(R.string.open_settings),
            onClick = onSettingsClick,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = appChrome().border,
) {
    val chrome = appChrome()
    Surface(
        onClick = onClick,
        modifier = modifier.size(46.dp),
        shape = RoundedCornerShape(15.dp),
        color = chrome.field,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(23.dp), tint)
        }
    }
}
