package com.cms.display.settings

import android.content.Context
import android.content.SharedPreferences

class SettingsService(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var macAddress: String
        get() = prefs.getString(KEY_MAC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MAC, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.removeSuffix("/") ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim().removeSuffix("/")).apply()

    val hasMac: Boolean get() = macAddress.trim().isNotEmpty()

    companion object {
        private const val PREFS_NAME = "cms_display_prefs"
        private const val KEY_MAC = "media_player_mac_address"
        private const val KEY_BASE_URL = "media_player_base_url"
        private const val DEFAULT_BASE_URL = "https://abettech.com/cms/public"
    }
}
