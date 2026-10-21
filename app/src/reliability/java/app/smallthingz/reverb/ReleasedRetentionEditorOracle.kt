package app.smallthingz.reverb

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp

/** Generated source oracle; see RELEASE-ORACLE.json. Do not hand-edit the copied blocks. */
@Composable
internal fun ReleasedRetentionEditorOracle(
    label: String,
    activeMode: RetentionMode,
    timeText: String,
    sizeText: String,
    requestFocus: Boolean,
    onTimeChange: (String) -> Unit,
    onSizeChange: (String) -> Unit,
) {
    val chrome = appChrome()
    val isTime = activeMode == RetentionMode.TIME
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }
    val value = if (isTime) timeText else sizeText
    Row(modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = value,
                onValueChange = if (isTime) onTimeChange else onSizeChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isTime) KeyboardType.Ascii else KeyboardType.Decimal,
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = chrome.ink,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-1.1).sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = label },
            )
    }
}
