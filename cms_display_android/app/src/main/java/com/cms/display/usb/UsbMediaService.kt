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

    /**
     * Load media from the granted USB / document tree when the pendrive is connected.
     * Walks subfolders (not only the root). Does **not** copy whole files up front — playback uses
     * each document [Uri] so large videos start without waiting for a full cache copy.
     * Cached copies from older runs are still used by [loadCachedMediaList] when the drive is unplugged.
     */
    fun loadMediaFromFolder(treeUri: Uri): List<UsbMediaItem> {
        val list = mutableListOf<UsbMediaItem>()
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return list
        if (!tree.exists() || !tree.isDirectory) return list
        walkTree(tree, relativePrefix = "", out = list)
        return list.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    private fun walkTree(dir: DocumentFile, relativePrefix: String, out: MutableList<UsbMediaItem>) {
        for (file in dir.listFiles()) {
            when {
                file.isDirectory -> {
                    val segment = file.name ?: continue
                    val nextPrefix =
                        if (relativePrefix.isEmpty()) segment else "$relativePrefix/$segment"
                    walkTree(file, nextPrefix, out)
                }
                file.isFile -> addIfPlayableMedia(file, relativePrefix, out)
                else -> Unit
            }
        }
    }

    private fun addIfPlayableMedia(
        file: DocumentFile,
        relativePrefix: String,
        out: MutableList<UsbMediaItem>,
    ) {
        val mime = file.type?.lowercase(Locale.ROOT) ?: ""
        val name = file.name ?: return
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isImage = mime.startsWith("image/") || ext in imageExts
        val isVideo = mime.startsWith("video/") || ext in videoExts
        if (!isImage && !isVideo) return

        val displayName =
            if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
        val effectiveMime = when {
            isImage && mime.isEmpty() -> "image/$ext".takeIf { ext.isNotEmpty() } ?: "image/jpeg"
            isVideo && mime.isEmpty() -> "video/$ext".takeIf { ext.isNotEmpty() } ?: "video/mp4"
            else -> mime.ifEmpty { "application/octet-stream" }
        }
        out.add(
            UsbMediaItem(
                uri = file.uri,
                displayName = displayName,
                mimeType = effectiveMime,
                localPath = null,
            ),
        )
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
                    localPath = localPath,
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

    private fun listKey(treeUriStr: String): String =
        "usb_list_${treeUriStr.replace(Regex("[^a-zA-Z0-9]"), "_").take(64)}"

    companion object {
        private const val PREFS_NAME = "cms_display_usb_cache"
    }
}
