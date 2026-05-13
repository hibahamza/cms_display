package com.cms.display

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.Target
import com.cms.display.api.ApiException
import com.cms.display.api.ApiService
import com.cms.display.model.MediaItem as CmsMediaItem
import com.cms.display.offline.OfflineMediaService
import com.cms.display.settings.SettingsService
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsService
    private lateinit var offlineMedia: OfflineMediaService
    private lateinit var settingsContent: View
    private lateinit var displayContent: View
    private lateinit var displayCenter: FrameLayout
    private lateinit var logoBadge: ImageView
    private lateinit var btnSettings: View
    private lateinit var positionBadge: TextView
    private lateinit var editMac: TextInputEditText
    private lateinit var btnSavePlay: View

    private var mediaList: List<CmsMediaItem> = emptyList()
    private var currentIndex = 0
    private var loading = true
    private var errorMessage: String? = null
    private var imageTimer: Timer? = null
    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var stallCheckRunnable: Runnable? = null
    private var videoStallStartPositionMs: Long = 0L
    private var videoStallDetectedAtMs: Long = 0L
    private var videoStallHandled = false

    companion object {
        private const val IMAGE_DURATION_MS = 10_000L
        private const val STALL_CHECK_INTERVAL_MS = 2000L
        private const val STALL_THRESHOLD_MS = 4000L
        private const val STALL_MIN_ADVANCE_MS = 250L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsService(this)
        offlineMedia = OfflineMediaService(this)

        settingsContent = findViewById(R.id.settings_content)
        displayContent = findViewById(R.id.display_content)
        displayCenter = findViewById(R.id.display_center)
        logoBadge = findViewById(R.id.logo_badge)
        btnSettings = findViewById(R.id.btn_settings)
        positionBadge = findViewById(R.id.position_badge)
        editMac = findViewById(R.id.edit_mac)
        btnSavePlay = findViewById(R.id.btn_save_play)

        editMac?.setText(settings.macAddress)
        btnSavePlay?.setOnClickListener { onSaveMacAndPlay() }
        btnSettings?.setOnClickListener { showSettingsDialog() }

        if (settings.hasMac) {
            showDisplay()
            fetchMedia()
        } else {
            showSettings()
        }
    }

    override fun onDestroy() {
        imageTimer?.cancel()
        cancelStallWatchdog()
        releasePlayer()
        super.onDestroy()
    }

    private fun showSettings() {
        settingsContent.visibility = View.VISIBLE
        displayContent.visibility = View.GONE
    }

    private fun showDisplay() {
        settingsContent.visibility = View.GONE
        displayContent.visibility = View.VISIBLE
        logoBadge.visibility = View.VISIBLE
        btnSettings.visibility = View.VISIBLE
    }

    private fun onSaveMacAndPlay() {
        val mac = editMac?.text?.toString()?.trim() ?: ""
        if (mac.isEmpty()) {
            Toast.makeText(this, R.string.please_enter_mac, Toast.LENGTH_SHORT).show()
            return
        }
        settings.macAddress = mac
        showDisplay()
        fetchMedia()
    }

    private fun showSettingsDialog() {
        val macEdit = TextInputEditText(this).apply {
            setText(settings.macAddress)
            setPadding(48, 32, 48, 32)
            hint = getString(R.string.mac_hint)
        }
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle(R.string.settings)
            .setView(macEdit)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val mac = macEdit.text?.toString()?.trim() ?: ""
                if (mac.isEmpty()) {
                    Toast.makeText(this, R.string.please_enter_mac, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                settings.macAddress = mac
                fetchMedia()
            }
            .show()
    }

    private fun fetchMedia() {
        val mac = settings.macAddress.trim()
        if (mac.isEmpty()) return
        loading = true
        errorMessage = null
        showCenterContent(createLoadingView())

        scope.launch {
            val list = withContext(Dispatchers.IO) {
                if (isOnline()) {
                    try {
                        val api = ApiService(settings.baseUrl)
                        var playable = api.getDeviceMedia(mac).filter { it.isPlayable }
                        playable = offlineMedia.mergeWithLocalPaths(mac, playable)
                        offlineMedia.saveMediaList(mac, playable)
                        offlineMedia.cacheAllInBackground(mac, playable)
                        playable
                    } catch (e: Exception) {
                        val cached = offlineMedia.getMediaList(mac)
                        if (!cached.isNullOrEmpty()) cached.filter { it.isPlayable } else null
                    }
                } else {
                    offlineMedia.getMediaList(mac)?.filter { it.isPlayable }
                }
            }
            loading = false
            if (!list.isNullOrEmpty()) {
                mediaList = list
                currentIndex = 0
                errorMessage = null
                playCurrent()
            } else {
                mediaList = emptyList()
                errorMessage = "No media found. Connect to internet to load media."
                showCenterContent(createErrorView(errorMessage ?: getString(R.string.no_media_found)))
            }
        }
    }

    private fun userFriendlyError(e: Throwable): String {
        val s = e.toString().lowercase()
        return when {
            s.contains("failed to fetch") || s.contains("connection") ->
                "Cannot reach server. Check internet or tap ⚙ and set Server URL."
            s.contains("404") ->
                "Device not found. Register this MAC on the server or check the address."
            s.contains("403") || s.contains("401") ->
                "Access denied. Check server settings."
            else -> "Failed to load media. Using cached media if available."
        }
    }

    private fun showCenterContent(view: View) {
        displayCenter.removeAllViews()
        displayCenter.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        positionBadge.visibility = if (mediaList.isNotEmpty() && !loading && errorMessage == null) View.VISIBLE else View.GONE
        if (mediaList.isNotEmpty() && !loading && errorMessage == null) {
            val media = mediaList[currentIndex]
            positionBadge.text = "${currentIndex + 1}/${mediaList.size} - ${media.safeTitle}"
        }
    }

    private fun createLoadingView(): View {
        val wrap = FrameLayout(this)
        val progress = ProgressBar(this)
        wrap.addView(progress, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        val tv = TextView(this).apply {
            text = getString(R.string.loading_media)
            setTextColor(resources.getColor(android.R.color.white, null))
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER; topMargin = 80 }
        wrap.addView(tv, lp)
        return wrap
    }

    private fun createErrorView(message: String): View {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val text1 = TextView(this).apply {
            text = message
            setTextColor(resources.getColor(R.color.red, null))
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(text1)
        val retryBtn = android.widget.Button(this).apply {
            text = getString(R.string.retry)
            setOnClickListener { fetchMedia() }
        }
        layout.addView(retryBtn)
        return layout
    }

    private fun playCurrent() {
        imageTimer?.cancel()
        cancelStallWatchdog()
        if (mediaList.isEmpty()) return
        val media = mediaList[currentIndex]
        when {
            media.isImage -> {
                releasePlayer()
                showImage(media)
                imageTimer = Timer().apply {
                    schedule(object : TimerTask() { override fun run() { runOnUiThread { nextMedia() } } }, IMAGE_DURATION_MS)
                }
            }
            media.isVideo -> showVideo(media)
            else -> nextMedia()
        }
        positionBadge.visibility = View.VISIBLE
        positionBadge.text = "${currentIndex + 1}/${mediaList.size} - ${media.safeTitle}"
    }

    private fun showImage(media: CmsMediaItem) {
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        displayCenter.removeAllViews()
        displayCenter.addView(iv)
        Glide.with(this)
            .load(
                if (media.isCached && media.localPath != null)
                    File(media.localPath!!)
                else
                    media.safePreviewUrl
            )
            .transition(DrawableTransitionOptions.withCrossFade())
            .override(Target.SIZE_ORIGINAL)
            .fitCenter()
            .error(android.R.drawable.ic_dialog_alert)
            .into(iv)
    }

    private fun showVideo(media: CmsMediaItem) {
        val videoUrl = if (media.isCached && media.localPath != null)
            null
        else
            if (media.safePreviewUrl.isNotEmpty()) media.safePreviewUrl else streamUri(media.id)
        val uri = if (media.isCached && media.localPath != null)
            Uri.fromFile(File(media.localPath))
        else
            Uri.parse(videoUrl ?: streamUri(media.id))

        if (exoPlayer == null) {
            showCenterContent(createLoadingVideoView())
        }

        runOnUiThread {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 15000, 500, 1000)
                .build()
            if (exoPlayer == null) {
                playerView = PlayerView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                }
                exoPlayer = ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .build()
                    .apply {
                        repeatMode = Player.REPEAT_MODE_OFF
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) nextMedia()
                                if (playbackState == Player.STATE_READY) startStallWatchdog()
                            }
                        })
                    }
                playerView?.player = exoPlayer
                displayCenter.removeAllViews()
                displayCenter.addView(playerView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            videoStallHandled = false
            exoPlayer?.apply {
                setMediaItem(ExoMediaItem.fromUri(uri))
                prepare()
                play()
            }
        }
    }

    private fun startStallWatchdog() {
        cancelStallWatchdog()
        val p = exoPlayer ?: return
        videoStallStartPositionMs = p.currentPosition
        videoStallDetectedAtMs = 0L
        stallCheckRunnable = object : Runnable {
            override fun run() {
                val player = exoPlayer ?: return
                if (videoStallHandled) return
                if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) return
                val pos = player.currentPosition
                val advanced = pos >= videoStallStartPositionMs + STALL_MIN_ADVANCE_MS
                if (advanced) {
                    videoStallStartPositionMs = pos
                    videoStallDetectedAtMs = 0L
                } else {
                    if (videoStallDetectedAtMs == 0L) videoStallDetectedAtMs = System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - videoStallDetectedAtMs
                    if (elapsed >= STALL_THRESHOLD_MS) {
                        videoStallHandled = true
                        cancelStallWatchdog()
                        nextMedia()
                        return
                    }
                }
                stallCheckRunnable?.let { handler.postDelayed(it, STALL_CHECK_INTERVAL_MS) }
            }
        }
        handler.postDelayed(stallCheckRunnable!!, STALL_CHECK_INTERVAL_MS)
    }

    private fun cancelStallWatchdog() {
        stallCheckRunnable?.let { handler.removeCallbacks(it) }
        stallCheckRunnable = null
    }

    private fun createLoadingVideoView(): View {
        val frame = FrameLayout(this)
        frame.addView(ProgressBar(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        val tv = TextView(this).apply {
            text = getString(R.string.loading_video)
            setTextColor(resources.getColor(android.R.color.white, null))
        }
        frame.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER; topMargin = 80 })
        return frame
    }

    private fun streamUri(mediaId: Int): String {
        val base = settings.baseUrl.removeSuffix("/")
        val encodedMac = java.net.URLEncoder.encode(settings.macAddress, "UTF-8")
        return "$base/media/devices/$encodedMac/$mediaId"
    }

    private fun releasePlayer() {
        cancelStallWatchdog()
        playerView?.player = null
        exoPlayer?.release()
        exoPlayer = null
        playerView = null
    }

    private fun nextMedia() {
        imageTimer?.cancel()
        cancelStallWatchdog()
        if (mediaList.isEmpty()) return
        val nextIndex = (currentIndex + 1) % mediaList.size
        val nextIsVideo = mediaList.getOrNull(nextIndex)?.isVideo == true
        if (!nextIsVideo) releasePlayer()
        currentIndex = nextIndex
        playCurrent()
    }
}
