package com.example.hub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri

// Finds and opens other apps. Prefers the TV (leanback) launch intent and falls back to the
// normal launcher intent, so phone-style apps sideloaded on a TV still open.
object AppLauncher {
    fun isInstalled(context: Context, pkg: String): Boolean = installedVersionCode(context, pkg) != null

    fun installedVersionCode(context: Context, pkg: String): Long? = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) { null }

    fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
    }

    // Returns false if the app has no launchable activity or the start fails.
    // A successful launch is recorded in recents.
    fun launch(context: Context, pkg: String): Boolean {
        val intent = launchIntent(context, pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return try {
            context.startActivity(intent)
            UserPrefs.recordLaunch(context, pkg)
            true
        } catch (e: Exception) { false }
    }

    // Opens the system's uninstall confirmation. Returns false if the TV has no screen for it.
    // The result isn't reported back; screens re-check installedVersionCode on resume.
    fun uninstall(context: Context, pkg: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_DELETE, "package:$pkg".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }
}
