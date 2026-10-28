package com.example.hub

import android.content.Context
import androidx.core.content.edit

// Viewer settings from the Settings screen. Read synchronously, since the catalog filter uses them.
object HubSettings {
    private const val FILE = "hub_settings"
    private const val KEY_HIDE_MATURE = "hide_mature"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hideMature(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HIDE_MATURE, false)
    fun setHideMature(ctx: Context, hide: Boolean) = prefs(ctx).edit { putBoolean(KEY_HIDE_MATURE, hide) }
    fun clear(ctx: Context) = prefs(ctx).edit { clear() }
}

object SetupPrefs {
    private const val FILE = "hub_setup"
    private const val KEY_DONE = "setup_done"
    private const val KEY_INTERESTS = "interests"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isSetupDone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DONE, false)
    fun markSetupDone(ctx: Context) = prefs(ctx).edit { putBoolean(KEY_DONE, true) }

    fun getInterests(ctx: Context): Set<Category> =
        prefs(ctx).getStringSet(KEY_INTERESTS, emptySet()).orEmpty()
            .mapNotNull { name -> Category.values().firstOrNull { it.name == name } }.toSet()

    fun clear(ctx: Context) = prefs(ctx).edit { clear() }

    fun saveInterests(ctx: Context, interests: Set<Category>) =
        prefs(ctx).edit { putStringSet(KEY_INTERESTS, interests.map { it.name }.toSet()) }
}
