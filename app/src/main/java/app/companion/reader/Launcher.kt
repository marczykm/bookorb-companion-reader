package app.companion.reader

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap

/** An app that can be launched, for the All apps screen. */
class AppEntry(val label: String, val packageName: String, val icon: Bitmap)

/**
 * Lets this app be the phone's Home screen. It is off until switched on in Settings: the Home entry
 * is a disabled alias of the main activity, so Android doesn't list the app as a Home app before that.
 * Android decides which Home app is the default, so turning this on asks the user to confirm.
 */
object Launcher {
    private fun alias(context: Context) = ComponentName(context, "app.companion.reader.HomeAlias")

    /** Whether the Home entry is switched on (the app is offered as a Home app). */
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            alias(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Whether pressing Home really opens this app right now. */
    fun isDefaultHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /** The system's own dialog for making this the Home app (Android 10 and later), or its Home settings page before that. */
    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        return settingsIntent()
    }

    /** Android's own page for choosing the Home app. */
    fun settingsIntent() = Intent(Settings.ACTION_HOME_SETTINGS)

    /** Launchable apps other than this one, by name. */
    fun installedApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(96, 96)) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.startActivity(it) }
    }
}
