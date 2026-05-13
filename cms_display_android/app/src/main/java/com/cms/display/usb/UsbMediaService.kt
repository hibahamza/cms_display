package com.cms.display.usb

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class UsbMediaService(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val videoExts = setOf("mp4", "webm", "3gp", "avi", "mov", "mkv")
    private val cacheDirName = "usb_media_cache"

    /**
     * Load media from USB folder when pendrive is connected.
     * Copies each file to app cache and returns list with localPath set.
     */
    fun loadMediaFromFolder(treeUri: Uri): List<UsbMediaItem> {
        val list = mutableListOf<UsbMediaItem>()
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return list
        if (!tree.exists() || !tree.isDirectory) return list

        val cacheDir = getCacheDirForTree(treeUri)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        for (file in tree.listFiles()) {
            if (file.isFile) {
                val mime = file.type?.lowercase(Locale.ROOT) ?: ""
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

                val isImage = mime.startsWith("image/") || ext in imageExts
                val isVideo = mime.startsWith("video/") || ext in videoExts

                if (isImage || isVideo) {
                    val uri = file.uri
                    val effectiveMime = when {
                        isImage && mime.isEmpty() -> "image/$ext"
                        isVideo && mime.isEmpty() -> "video/$ext"
                        else -> mime.ifEmpty { "application/octet-stream" }
                    }
                    val localPath = copyToCache(uri, name, cacheDir)
                    list.add(UsbMediaItem(uri = uri, displayName = name, mimeType = effectiveMime, localPath = localPath))
                }
            }
        }

        return list.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    /**
     * Load saved media list when pendrive may be disconnected.
     * Returns only items that have a valid cached file.
     */
    fun loadCachedMediaList(treeUriStr: String): List<UsbMediaItem> {
        val json = prefs.getString(listKey(treeUriStr), null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val localPath = obj.optString("local_path").takeIf { it.isNotEmpty() }
                if (localPath == null || !File(localPath).exists()) return@mapNotNull null
                UsbMediaItem(
                    uri = Uri.EMPTY,
                    displayName = obj.optString("display_name", ""),
                    mimeType = obj.optString("mime_type", "image/jpeg"),
                    localPath = localPath
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCachedMediaList(treeUriStr: String, list: List<UsbMediaItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("display_name", item.displayName)
                put("mime_type", item.mimeType)
                put("local_path", item.localPath ?: "")
            }
            arr.put(obj)
        }
        prefs.edit().putString(listKey(treeUriStr), arr.toString()).apply()
    }

    private fun copyToCache(uri: Uri, displayName: String, cacheDir: File): String? {
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val baseName = if (ext in imageExts || ext in videoExts) safeName else "$safeName.$ext"
        val target = File(cacheDir, baseName)
        if (target.exists() && target.length() > 0) return target.absolutePath

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return if (target.exists() && target.length() > 0) target.absolutePath else null
        } catch (_: Exception) {
            return null
        }
    }

    private fun getCacheDirForTree(treeUri: Uri): File {
        val key = treeUri.toString().replace(Regex("[^a-zA-Z0-9]"), "_").take(64)
        return File(context.filesDir, "$cacheDirName/$key")
    }

    private fun listKey(treeUriStr: String): String = "usb_list_${treeUriStr.replace(Regex("[^a-zA-Z0-9]"), "_").take(64)}"

    companion object {
        private const val PREFS_NAME = "cms_display_usb_cache"
    }
}
