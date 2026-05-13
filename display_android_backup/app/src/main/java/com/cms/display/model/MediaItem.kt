package com.cms.display.model

import com.google.gson.annotations.SerializedName

/**
 * Represents a single media item from the CMS API.
 * API: GET /api/devices/{mac}/media returns { "data": [ MediaItem ] }
 */
data class MediaItem(
    val id: Int,
    val title: String?,
    @SerializedName("file_type") val fileType: String,
    @SerializedName("file_size") val fileSize: Int?,
    @SerializedName("preview_url") val previewUrl: String?,
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("local_path") val localPath: String? = null
) {
    val safeTitle: String get() = title ?: ""
    val safePreviewUrl: String get() = previewUrl ?: ""
    val safeFileSize: Int get() = fileSize ?: 0

    val isCached: Boolean get() = !localPath.isNullOrEmpty()
    val isImage: Boolean get() = fileType.startsWith("image/")
    val isVideo: Boolean get() = fileType.startsWith("video/")
    val isPlayable: Boolean get() = isImage || isVideo

    fun withLocalPath(path: String?) = copy(localPath = path)
}
