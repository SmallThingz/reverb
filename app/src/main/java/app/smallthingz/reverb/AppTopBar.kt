package app.smallthingz.reverb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun AppTopBar(
    onBrandClick: () -> Unit,
    onSettingsClick: () -> Unit,
    applyStatusBarPadding: Boolean = true,
    barHeight: Dp = 66.dp,
) {
    val chrome = appChrome()
    val buttonShape = RoundedCornerShape(15.dp)
    val topBarModifier = Modifier
        .fillMaxWidth()
        .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
        .height(barHeight)
        .padding(horizontal = 14.dp)
    Box(
        modifier = topBarModifier,
    ) {
        Surface(
            onClick = onBrandClick,
            modifier = Modifier
                .align(Alignment.Center)
                .size(46.dp),
            shape = buttonShape,
            color = chrome.field,
            border = BorderStroke(1.dp, chrome.border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                ReverbBrandMark(
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(42.dp),
                )
            }
        }

        Surface(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(46.dp),
            shape = buttonShape,
            color = chrome.field,
            border = BorderStroke(1.dp, chrome.border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.settings,
                    contentDescription = stringResource(R.string.open_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
    }
}
