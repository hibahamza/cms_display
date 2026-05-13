package com.cms.display.api

import com.cms.display.model.MediaItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiService(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun base(): String = baseUrl.removeSuffix("/")

    private fun pathStyleUri(mac: String): String {
        val base = base()
        val encodedMac = URLEncoder.encode(mac, "UTF-8")
        return "$base/api/devices/$encodedMac/media"
    }

    fun getDeviceMedia(mac: String): List<MediaItem> {
        val pathStyle = pathStyleUri(mac)
        val queryStyle = "${base()}/api/media?mac=${URLEncoder.encode(mac, "UTF-8")}"
        val urlsToTry = listOf(pathStyle, queryStyle)
        var lastError: ApiException? = null
        for (url in urlsToTry) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (response.code == 404) {
                    lastError = ApiException("Not found (404). Try in browser: $url", 404)
                    response.close()
                    continue
                }
                if (response.code != 200) {
                    lastError = ApiException("Server error: ${response.code}", response.code)
                    response.close()
                    continue
                }
                val body = response.body?.string() ?: "{}"
                response.close()
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return emptyList()
                return parseMediaList(data, mac)
            } catch (e: Exception) {
                lastError = ApiException("Cannot reach server. Try in browser: $url", null)
            }
        }
        throw lastError ?: ApiException("Cannot reach server.", null)
    }

    private fun parseMediaList(data: org.json.JSONArray, mac: String): List<MediaItem> {
        val base = base()
        val list = mutableListOf<MediaItem>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val previewUrl = obj.optString("preview_url").takeIf { it.isNotEmpty() }
                ?: "$base/media/devices/${URLEncoder.encode(mac, "UTF-8")}/${obj.optInt("id")}"
            val scheduleSeconds: Long? = when {
                obj.has("playback_schedule_seconds") && !obj.isNull("playback_schedule_seconds") ->
                    obj.optLong("playback_schedule_seconds").takeIf { it > 0L }
                obj.has("playbackScheduleSeconds") && !obj.isNull("playbackScheduleSeconds") ->
                    obj.optLong("playbackScheduleSeconds").takeIf { it > 0L }
                else -> null
            }
            list.add(
                MediaItem(
                    id = obj.optInt("id"),
                    title = obj.optString("title").takeIf { it.isNotEmpty() },
                    fileType = obj.optString("file_type", "image/jpeg"),
                    fileSize = obj.optInt("file_size", 0).takeIf { obj.has("file_size") },
                    previewUrl = previewUrl,
                    playbackScheduleSeconds = scheduleSeconds,
                    updatedAt = obj.optString("updated_at").takeIf { it.isNotEmpty() }
                )
            )
        }
        return list
    }
}

class ApiException(message: String, val statusCode: Int?) : Exception(message)
