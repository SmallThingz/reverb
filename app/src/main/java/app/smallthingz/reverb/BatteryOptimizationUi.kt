package app.smallthingz.reverb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private val BackgroundWarningColor = Color(0xFFF1C789)

@Composable
fun BackgroundOptimizationWarning(
    restricted: Boolean,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = appChrome()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = chrome.field,
        border = BorderStroke(1.dp, chrome.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.backgroundWarning,
                contentDescription = null,
                tint = if (restricted) BackgroundWarningColor else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(
                    if (restricted) R.string.background_use_restricted
                    else R.string.background_use_unrestricted,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (restricted) {
                TextButton(onClick = onReview) {
                    Text(stringResource(R.string.background_review))
                }
            } else {
                Text(
                    text = stringResource(R.string.onboarding_allowed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
