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

    /** "cms" or "usb" */
    var sourceMode: String
        get() = prefs.getString(KEY_SOURCE_MODE, SOURCE_CMS) ?: SOURCE_CMS
        set(value) = prefs.edit().putString(KEY_SOURCE_MODE, value).apply()

    var usbFolderUri: String?
        get() = prefs.getString(KEY_USB_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_USB_FOLDER_URI, value).apply()

    var usbFolderDisplayName: String?
        get() = prefs.getString(KEY_USB_FOLDER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USB_FOLDER_NAME, value).apply()

    val hasMac: Boolean get() = macAddress.trim().isNotEmpty()
    val hasUsbFolder: Boolean get() = !usbFolderUri.isNullOrEmpty()
    val isUsbMode: Boolean get() = sourceMode == SOURCE_USB
    val isCmsMode: Boolean get() = sourceMode == SOURCE_CMS
    val hasConfig: Boolean get() = (isCmsMode && hasMac) || (isUsbMode && hasUsbFolder)

    companion object {
        const val SOURCE_CMS = "cms"
        const val SOURCE_USB = "usb"
        private const val PREFS_NAME = "cms_display_prefs"
        private const val KEY_MAC = "media_player_mac_address"
        private const val KEY_BASE_URL = "media_player_base_url"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_USB_FOLDER_URI = "usb_folder_uri"
        private const val KEY_USB_FOLDER_NAME = "usb_folder_name"
        private const val DEFAULT_BASE_URL = "https://abettech.com/cms/public"
    }
}
