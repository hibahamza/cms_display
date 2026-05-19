package com.cms.cms_display_app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.documentfile.provider.DocumentFile
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        super.onCreate(savedInstanceState)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var pendingOpenTree: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "openDocumentTree" -> {
                    if (pendingOpenTree != null) {
                        result.error("busy", "Picker already open", null)
                        return@setMethodCallHandler
                    }
                    pendingOpenTree = result
                    try {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        }
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQUEST_OPEN_TREE)
                    } catch (e: Exception) {
                        pendingOpenTree = null
                        result.error("open_tree", e.message, null)
                    }
                }

                "listTreeMedia" -> {
                    val uriStr = call.arguments as? String
                    if (uriStr.isNullOrEmpty()) {
                        result.error("bad_arg", "missing tree uri", null)
                        return@setMethodCallHandler
                    }
                    executor.execute {
                        try {
                            val list = listUsbMedia(Uri.parse(uriStr))
                            runOnUiThread { result.success(list) }
                        } catch (e: Exception) {
                            runOnUiThread {
                                result.error("list_failed", e.message, null)
                            }
                        }
                    }
                }

                "copyUriToFile" -> {
                    val map = call.arguments as? Map<*, *>
                    val uriStr = map?.get("uri") as? String
                    val destStr = map?.get("destPath") as? String
                    if (uriStr.isNullOrEmpty() || destStr.isNullOrEmpty()) {
                        result.error("bad_arg", "uri and destPath required", null)
                        return@setMethodCallHandler
                    }
                    executor.execute {
                        try {
                            val dest = File(destStr)
                            dest.parentFile?.mkdirs()
                            val input = contentResolver.openInputStream(Uri.parse(uriStr))
                            if (input == null) {
                                runOnUiThread {
                                    result.error("no_stream", "cannot open uri", null)
                                }
                                return@execute
                            }
                            input.use { inp ->
                                dest.outputStream().use { out -> inp.copyTo(out) }
                            }
                            if (!dest.exists() || dest.length() == 0L) {
                                runOnUiThread {
                                    result.error("empty", "empty output", null)
                                }
                            } else {
                                runOnUiThread { result.success(null) }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                result.error("copy_uri_file", e.message, null)
                            }
                        }
                    }
                }

                "copyUriToCache" -> {
                    val uriStr = call.arguments as? String
                    if (uriStr.isNullOrEmpty()) {
                        result.error("bad_arg", "missing uri", null)
                        return@setMethodCallHandler
                    }
                    executor.execute {
                        try {
                            val uri = Uri.parse(uriStr)
                            val mime = contentResolver.getType(uri) ?: ""
                            val ext = extensionForMime(mime, uri)
                            val out = File(cacheDir, "usb_img_${uri.hashCode()}.$ext")
                            contentResolver.openInputStream(uri)?.use { input ->
                                out.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (!out.exists() || out.length() == 0L) {
                                runOnUiThread {
                                    result.error("copy_empty", "empty file", null)
                                }
                            } else {
                                runOnUiThread { result.success(out.absolutePath) }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                result.error("copy_failed", e.message, null)
                            }
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_TREE) return
        val pr = pendingOpenTree
        pendingOpenTree = null
        if (resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
            pr?.success(uri.toString())
        } else {
            pr?.success(null)
        }
    }

    private fun listUsbMedia(treeUri: Uri): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        val tree = DocumentFile.fromTreeUri(this, treeUri) ?: return results
        if (!tree.exists() || !tree.isDirectory) return results

        val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        val videoExts = setOf("mp4", "webm", "3gp", "avi", "mov", "mkv")

        fun walk(dir: DocumentFile) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                when {
                    child.isDirectory -> walk(child)
                    child.isFile -> {
                        val mime = child.type?.lowercase(Locale.ROOT) ?: ""
                        val name = child.name ?: continue
                        val ext = name.substringAfterLast('.', "")
                            .lowercase(Locale.ROOT)
                        val isImage = mime.startsWith("image/") || ext in imageExts
                        val isVideo = mime.startsWith("video/") || ext in videoExts
                        if (!isImage && !isVideo) continue
                        val effectiveMime = when {
                            isImage && mime.isEmpty() ->
                                if (ext.isNotEmpty()) "image/$ext" else "image/jpeg"
                            isVideo && mime.isEmpty() ->
                                if (ext.isNotEmpty()) "video/$ext" else "video/mp4"
                            else -> mime.ifEmpty { "application/octet-stream" }
                        }
                        results.add(
                            mapOf(
                                "uri" to child.uri.toString(),
                                "name" to name,
                                "mime" to effectiveMime,
                            ),
                        )
                    }
                }
            }
        }
        walk(tree)
        results.sortBy { (it["name"] as String).lowercase(Locale.ROOT) }
        return results
    }

    private fun extensionForMime(mime: String, uri: Uri): String {
        return when {
            mime.contains("jpeg") -> "jpg"
            mime.contains("jpg") -> "jpg"
            mime.contains("png") -> "png"
            mime.contains("gif") -> "gif"
            mime.contains("webp") -> "webp"
            mime.contains("bmp") -> "bmp"
            mime.contains("mp4") -> "mp4"
            mime.contains("webm") -> "webm"
            mime.contains("3gp") -> "3gp"
            mime.contains("msvideo") || mime.contains("avi") -> "avi"
            mime.contains("quicktime") || mime.contains("mov") -> "mov"
            mime.contains("matroska") || mime.contains("mkv") -> "mkv"
            else -> {
                val name = uri.lastPathSegment ?: "bin"
                val ext = name.substringAfterLast('.', "bin")
                if (ext.length <= 5) ext else "bin"
            }
        }
    }

    companion object {
        private const val CHANNEL = "com.cms.cms_display_app/usb"
        private const val REQUEST_OPEN_TREE = 91001
    }
}
