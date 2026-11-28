package app.smallthingz.reverb

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper

/** Manifest startup hook only: no query/mutation API and no Activity launch or service command. */
class DebugUiInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        DebugUiRuntime.controls(checkNotNull(context).applicationContext as Application)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = unsupported()
    override fun getType(uri: Uri): String? = unsupported()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("Debug UI initializer has no data API")
}

/** One process-lifetime registry; its Activity and control-leaf references are weak. */
internal object DebugUiRuntime {
    private var application: Application? = null
    private var activities: DebugUiActivities? = null
    private var controller: DebugUiControls? = null
    fun controls(owner: Application): DebugUiControls {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (application !== owner) {
            // Normally there is one Application for the process; also release a retired host-test owner.
            activities?.let { application?.unregisterActivityLifecycleCallbacks(it) }
            val next = DebugUiActivities()
            owner.registerActivityLifecycleCallbacks(next)
            activities = next
            controller = DebugUiControls(next)
            application = owner
        }
        return checkNotNull(controller)
    }
}
