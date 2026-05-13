package com.cms.display.offline

import android.content.Context
import com.cms.display.model.MediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.regex.Pattern

class OfflineMediaService(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listPrefix = "list_"
    private val pathsPrefix = "paths_"
    private val mediaDirName = "cms_media"

    fun saveMediaList(mac: String, list: List<MediaItem>) {
        val arr = JSONArray()
        for (m in list) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("title", m.title)
                put("file_type", m.fileType)
                put("file_size", m.fileSize)
                put("preview_url", m.previewUrl)
                m.playbackScheduleSeconds?.let { put("playback_schedule_seconds", it) }
                put("updated_at", m.updatedAt)
                put("local_path", getLocalPath(mac, m.id) ?: m.localPath)
            }
            arr.put(obj)
        }
        prefs.edit().putString(listKey(mac), arr.toString()).apply()
    }

    fun getMediaList(mac: String): List<MediaItem>? {
        val json = prefs.getString(listKey(mac), null) ?: return null
        return try {
            val arr = JSONArray(json)
            val paths = getPathsMap(mac)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optInt("id")
                val localPath = paths[id.toString()] ?: obj.optString("local_path").takeIf { it.isNotEmpty() }
                val scheduleSeconds: Long? = if (obj.has("playback_schedule_seconds") && !obj.isNull("playback_schedule_seconds")) {
                    obj.optLong("playback_schedule_seconds", 0L).takeIf { it > 0L }
                } else null
                MediaItem(
                    id = id,
                    title = obj.optString("title").takeIf { it.isNotEmpty() },
                    fileType = obj.optString("file_type", "image/jpeg"),
                    fileSize = obj.optInt("file_size", 0),
                    previewUrl = obj.optString("preview_url"),
                    playbackScheduleSeconds = scheduleSeconds,
                    updatedAt = obj.optString("updated_at").takeIf { it.isNotEmpty() },
                    localPath = localPath
                )
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun getLocalPath(mac: String, mediaId: Int): String? =
        getPathsMap(mac)[mediaId.toString()]

    fun saveLocalPath(mac: String, mediaId: Int, filePath: String) {
        val paths = getPathsMap(mac).toMutableMap()
        paths[mediaId.toString()] = filePath
        val obj = JSONObject()
        paths.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(pathsKey(mac), obj.toString()).apply()
    }

    fun mergeWithLocalPaths(mac: String, fromApi: List<MediaItem>): List<MediaItem> {
        val paths = getPathsMap(mac)
        return fromApi.map { m ->
            paths[m.id.toString()]?.let { path -> m.withLocalPath(path) } ?: m
        }
    }

    fun cacheAllInBackground(mac: String, list: List<MediaItem>) {
        Thread {
            for (media in list) {
                if (!media.isPlayable || getLocalPath(mac, media.id) != null) continue
                cacheMediaFile(mac, media)
            }
            refreshListWithPaths(mac)
        }.start()
    }

    fun cacheMediaFile(mac: String, media: MediaItem): String? {
        return try {
            val dir = mediaDir(mac)
            val ext = extensionFromMime(media.fileType)
            val file = File(dir, "${media.id}.$ext")
            URL(media.safePreviewUrl).openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            saveLocalPath(mac, media.id, file.absolutePath)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun listKey(mac: String) = listPrefix + mac
    private fun pathsKey(mac: String) = pathsPrefix + mac

    private fun getPathsMap(mac: String): Map<String, String> {
        val json = prefs.getString(pathsKey(mac), null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optString(it, "") }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mediaDir(mac: String): File {
        val dir = File(context.filesDir, "$mediaDirName/${sanitizeMac(mac)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeMac(mac: String) = mac.replace(Regex("[^a-zA-Z0-9]"), "_")

    private fun extensionFromMime(fileType: String): String {
        return when {
            fileType.contains("jpeg") || fileType.contains("jpg") -> "jpg"
            fileType.contains("png") -> "png"
            fileType.contains("gif") -> "gif"
            fileType.contains("webp") -> "webp"
            fileType.contains("mp4") -> "mp4"
            fileType.contains("webm") -> "webm"
            fileType.contains("mov") -> "mov"
            else -> "bin"
        }
    }

    private fun refreshListWithPaths(mac: String) {
        val list = getMediaList(mac) ?: return
        val updated = list.map { m ->
            getLocalPath(mac, m.id)?.let { m.withLocalPath(it) } ?: m
        }
        saveMediaList(mac, updated)
    }

    companion object {
        private const val PREFS_NAME = "display_cache"
    }
}
