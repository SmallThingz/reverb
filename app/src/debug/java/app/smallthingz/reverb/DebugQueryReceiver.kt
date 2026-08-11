package app.smallthingz.reverb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DebugQueryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        resultCode = 1
        resultData = DebugStatusStore.read(context)
    }
}
