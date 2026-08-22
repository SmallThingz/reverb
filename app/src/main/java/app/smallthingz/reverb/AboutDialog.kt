package app.smallthingz.reverb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.net.toUri

private const val GITHUB_REPO_URL = "https://github.com/SmallThingz/reverb"
private const val GITHUB_REPO_LABEL = "SmallThingz/reverb"

@Composable
fun AboutDialog(onDismiss: () -> Unit = {}) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val versionName = remember {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName.orEmpty()
    }
    val versionText = resources.getString(R.string.about_version, versionName)
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    var dismissing by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }

    fun requestDismiss() {
        if (!dismissing) {
            dismissing = true
            visibility.targetState = false
        }
    }

    LaunchedEffect(visibility.isIdle, visibility.currentState, dismissing) {
        if (dismissing && visibility.isIdle && !visibility.currentState) onDismiss()
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.TOP)
        }

        AnimatedVisibility(
            visibleState = visibility,
            enter = slideInVertically(animationSpec = tween(220), initialOffsetY = { -it }) + fadeIn(tween(160)),
            exit = slideOutVertically(animationSpec = tween(190), targetOffsetY = { -it }) + fadeOut(tween(140)),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        IconButton(
                            onClick = ::requestDismiss,
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .size(104.dp),
                            shape = RoundedCornerShape(30.dp),
                            color = Color(0xFF0D1324),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_brand_mark),
                                contentDescription = resources.getString(R.string.app_name),
                                tint = Color.Unspecified,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = resources.getString(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = versionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(18.dp))

                    Surface(
                        onClick = {
                            linkError = if (openGithub(context)) null else resources.getString(R.string.no_app_available)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = resources.getString(R.string.github_repo),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = GITHUB_REPO_LABEL,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    linkError?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        FeedbackCard(
                            message = message,
                            tone = FeedbackTone.ERROR,
                        )
                    }
                }
            }
        }
    }
}

private fun openGithub(context: Context): Boolean {
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_REPO_URL.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
