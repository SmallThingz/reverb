package app.smallthingz.reverb

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

@Composable
internal fun ReverbBrandMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_brand_mark_accent),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            painter = painterResource(R.drawable.ic_brand_mark_main),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
