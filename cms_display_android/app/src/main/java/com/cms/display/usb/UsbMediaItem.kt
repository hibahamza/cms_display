package com.cms.display.usb

import android.net.Uri
import java.io.File

/**
 * Represents a media file (image or video) from USB storage.
 * [localPath] is set when the file has been copied to app cache for offline playback.
 */
data class UsbMediaItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val localPath: String? = null
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isPlayable: Boolean get() = isImage || isVideo
    val isCached: Boolean get() = localPath != null && File(localPath).exists()

    /** Use for playback: local file URI if cached, otherwise content URI. */
    fun playbackUri(): Uri = if (isCached && localPath != null) Uri.fromFile(File(localPath)) else uri
}
