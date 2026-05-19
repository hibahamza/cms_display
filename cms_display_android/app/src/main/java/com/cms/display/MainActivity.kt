package com.cms.display

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
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
import com.cms.display.usb.UsbMediaItem
import com.cms.display.usb.UsbMediaService
import com.google.android.material.radiobutton.MaterialRadioButton
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
    private lateinit var usbMediaService: UsbMediaService
    private lateinit var settingsContent: View
    private lateinit var displayContent: View
    private lateinit var displayCenter: FrameLayout
    private lateinit var editMac: TextInputEditText
    private lateinit var btnSavePlay: View
    private var sectionCms: View? = null
    private var sectionUsb: View? = null
    private var radioCms: View? = null
    private var radioUsb: View? = null
    private var btnSelectUsbFolder: View? = null
    private var txtUsbFolder: TextView? = null

    private var mediaList: List<CmsMediaItem> = emptyList()
    private var usbMediaList: List<UsbMediaItem> = emptyList()
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
    private var videoScheduleRunnable: Runnable? = null
    private var pendingVideoScheduleSeconds: Long? = null
    private var cmsRefreshRunnable: Runnable? = null

    private val usbFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onUsbFolderPicked(uri)
    }

    private val usbVolumePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            onUsbFolderPicked(uri)
        }
    }

    private fun onUsbFolderPicked(uri: Uri?) {
        uri?.let { u ->
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {}
            settings.usbFolderUri = u.toString()
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, u)?.name ?: "USB"
            settings.usbFolderDisplayName = name
            updateUsbFolderLabel()
        }
    }

    private fun launchUsbFolderPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val volumes = storageManager?.storageVolumes ?: emptyList()
            val removable = volumes.filter { it.isRemovable }
            val volumeToUse = removable.firstOrNull() ?: volumes.firstOrNull { it != storageManager?.primaryStorageVolume }
            if (volumeToUse != null) {
                try {
                    val intent = volumeToUse.createOpenDocumentTreeIntent()
                    usbVolumePicker.launch(intent)
                    return
                } catch (_: Exception) {}
            }
        }
        usbFolderPicker.launch(null)
    }

    companion object {
        private const val IMAGE_DURATION_MS = 10_000L
        private const val STALL_CHECK_INTERVAL_MS = 2000L
        private const val STALL_THRESHOLD_MS = 4000L
        private const val STALL_MIN_ADVANCE_MS = 250L
        private const val CMS_REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsService(this)
        offlineMedia = OfflineMediaService(this)
        usbMediaService = UsbMediaService(this)

        settingsContent = findViewById(R.id.settings_content)
        displayContent = findViewById(R.id.display_content)
        displayCenter = findViewById(R.id.display_center)
        editMac = findViewById(R.id.edit_mac)
        btnSavePlay = findViewById(R.id.btn_save_play)
        sectionCms = findViewById(R.id.section_cms)
        sectionUsb = findViewById(R.id.section_usb)
        radioCms = findViewById(R.id.radio_cms)
        radioUsb = findViewById(R.id.radio_usb)
        btnSelectUsbFolder = findViewById(R.id.btn_select_usb_folder)
        txtUsbFolder = findViewById(R.id.txt_usb_folder)

        setupSettingsUi()
        btnSavePlay?.setOnClickListener { onSaveAndPlay() }

        if (settings.hasConfig) {
            showDisplay()
            if (settings.isUsbMode) loadMediaFromUsb() else fetchMedia()
        } else {
            showSettings()
        }
    }

    override fun onDestroy() {
        imageTimer?.cancel()
        cancelStallWatchdog()
        stopCmsRefresh()
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
    }

    private fun setupSettingsUi() {
        editMac?.setText(settings.macAddress)
        updateUsbFolderLabel()
        radioCms?.setOnClickListener { settings.sourceMode = SettingsService.SOURCE_CMS; updateSourceVisibility() }
        radioUsb?.setOnClickListener { settings.sourceMode = SettingsService.SOURCE_USB; updateSourceVisibility() }
        (radioCms as? android.widget.CompoundButton)?.isChecked = settings.isCmsMode
        (radioUsb as? android.widget.CompoundButton)?.isChecked = settings.isUsbMode
        btnSelectUsbFolder?.setOnClickListener { launchUsbFolderPicker() }
        updateSourceVisibility()
    }

    private fun updateSourceVisibility() {
        val isCms = settings.isCmsMode
        sectionCms?.visibility = if (isCms) View.VISIBLE else View.GONE
        sectionUsb?.visibility = if (isCms) View.GONE else View.VISIBLE
    }

    private fun updateUsbFolderLabel() {
        val name = settings.usbFolderDisplayName
        txtUsbFolder?.text = if (name.isNullOrEmpty()) getString(R.string.usb_no_folder)
            else getString(R.string.usb_folder_selected, name)
    }

    private fun onSaveAndPlay() {
        if (settings.isCmsMode) {
            val mac = editMac?.text?.toString()?.trim() ?: ""
            if (mac.isEmpty()) {
                Toast.makeText(this, R.string.please_enter_mac, Toast.LENGTH_SHORT).show()
                return
            }
            settings.macAddress = mac
        } else {
            if (!settings.hasUsbFolder) {
                Toast.makeText(this, R.string.please_select_usb_folder, Toast.LENGTH_SHORT).show()
                return
            }
        }
        showDisplay()
        if (settings.isUsbMode) loadMediaFromUsb() else fetchMedia()
    }

    private fun showSettingsDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        val radioCmsDlg = MaterialRadioButton(this).apply { text = getString(R.string.source_cms); setTextColor(resources.getColor(android.R.color.white, null)) }
        val radioUsbDlg = MaterialRadioButton(this).apply { text = getString(R.string.source_usb); setTextColor(resources.getColor(android.R.color.white, null)) }
        val radioGroup = android.widget.RadioGroup(this).apply {
            addView(radioCmsDlg)
            addView(radioUsbDlg)
        }
        container.addView(radioGroup)
        val macLayout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; visibility = if (settings.isCmsMode) View.VISIBLE else View.GONE }
        val macEdit = TextInputEditText(this).apply { setText(settings.macAddress); hint = getString(R.string.mac_hint); setTextColor(resources.getColor(android.R.color.white, null)) }
        macLayout.addView(macEdit)
        val usbLayout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; visibility = if (settings.isUsbMode) View.VISIBLE else View.GONE }
        val txtUsb = TextView(this).apply { text = settings.usbFolderDisplayName ?: getString(R.string.usb_no_folder); setTextColor(resources.getColor(R.color.white_70, null)) }
        val btnChange = android.widget.Button(this).apply { text = getString(R.string.select_usb_folder); setOnClickListener { launchUsbFolderPicker() } }
        usbLayout.addView(txtUsb)
        usbLayout.addView(btnChange)
        container.addView(macLayout)
        container.addView(usbLayout)
        radioCmsDlg.isChecked = settings.isCmsMode
        radioUsbDlg.isChecked = settings.isUsbMode
        radioCmsDlg.setOnClickListener { macLayout.visibility = View.VISIBLE; usbLayout.visibility = View.GONE }
        radioUsbDlg.setOnClickListener { macLayout.visibility = View.GONE; usbLayout.visibility = View.VISIBLE }
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle(R.string.settings)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val useCms = radioCmsDlg.isChecked
                settings.sourceMode = if (useCms) SettingsService.SOURCE_CMS else SettingsService.SOURCE_USB
                if (useCms) {
                    val mac = macEdit.text?.toString()?.trim() ?: ""
                    if (mac.isEmpty()) { Toast.makeText(this, R.string.please_enter_mac, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    settings.macAddress = mac
                    fetchMedia()
                } else {
                    if (!settings.hasUsbFolder) { Toast.makeText(this, R.string.please_select_usb_folder, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    loadMediaFromUsb()
                }
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
                usbMediaList = emptyList()
                currentIndex = 0
                errorMessage = null
                playCurrent()
                startCmsRefresh()
            } else {
                stopCmsRefresh()
                mediaList = emptyList()
                errorMessage = "No media found. Connect to internet to load media."
                showCenterContent(createErrorView(errorMessage!!, false))
            }
        }
    }

    private fun startCmsRefresh() {
        stopCmsRefresh()
        if (!settings.isCmsMode) return
        cmsRefreshRunnable = object : Runnable {
            override fun run() {
                refreshCmsMediaInBackground()
                if (settings.isCmsMode && displayContent.visibility == View.VISIBLE) {
                    handler.postDelayed(this, CMS_REFRESH_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(cmsRefreshRunnable!!, CMS_REFRESH_INTERVAL_MS)
    }

    private fun stopCmsRefresh() {
        cmsRefreshRunnable?.let { handler.removeCallbacks(it) }
        cmsRefreshRunnable = null
    }

    private fun refreshCmsMediaInBackground() {
        if (!settings.isCmsMode || !isOnline()) return
        val mac = settings.macAddress.trim()
        if (mac.isEmpty()) return

        scope.launch {
            val newList = withContext(Dispatchers.IO) {
                try {
                    val api = ApiService(settings.baseUrl)
                    var playable = api.getDeviceMedia(mac).filter { it.isPlayable }
                    playable = offlineMedia.mergeWithLocalPaths(mac, playable)
                    offlineMedia.saveMediaList(mac, playable)
                    offlineMedia.cacheAllInBackground(mac, playable)
                    playable
                } catch (_: Exception) {
                    null
                }
            }
            if (newList == null || newList.isEmpty()) return@launch
            val currentIds = mediaList.map { it.id }.toSet()
            val newIds = newList.map { it.id }.toSet()
            val changed = currentIds != newIds || mediaList.size != newList.size
            if (changed) {
                mediaList = newList
                currentIndex = 0
                errorMessage = null
                playCurrent()
            }
        }
    }

    private fun loadMediaFromUsb() {
        val uriStr = settings.usbFolderUri ?: return
        val uri = Uri.parse(uriStr)
        loading = true
        errorMessage = null
        showCenterContent(createLoadingView())

        scope.launch {
            var list = withContext(Dispatchers.IO) {
                try {
                    usbMediaService.loadMediaFromFolder(uri).filter { it.isPlayable }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (list.isEmpty()) {
                list = withContext(Dispatchers.IO) {
                    usbMediaService.loadCachedMediaList(uriStr).filter { it.isPlayable }
                }
            } else {
                withContext(Dispatchers.IO) {
                    usbMediaService.saveCachedMediaList(uriStr, list)
                }
            }
            loading = false
            if (list.isNotEmpty()) {
                stopCmsRefresh()
                usbMediaList = list
                mediaList = emptyList()
                currentIndex = 0
                errorMessage = null
                playCurrent()
            } else {
                usbMediaList = emptyList()
                errorMessage = getString(R.string.usb_no_media)
                showCenterContent(createErrorView(errorMessage!!, true))
            }
        }
    }

    private fun showCenterContent(view: View) {
        displayCenter.removeAllViews()
        displayCenter.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun createLoadingView(): View {
        val wrap = FrameLayout(this)
        wrap.addView(ProgressBar(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        val tv = TextView(this).apply {
            text = getString(R.string.loading_media)
            setTextColor(resources.getColor(android.R.color.white, null))
        }
        wrap.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER; topMargin = 80 })
        return wrap
    }

    private fun createErrorView(message: String, isUsb: Boolean = false): View {
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
            setOnClickListener { if (isUsb) loadMediaFromUsb() else fetchMedia() }
        }
        layout.addView(retryBtn)
        return layout
    }

    private fun playCurrent() {
        imageTimer?.cancel()
        cancelVideoSchedule()
        cancelStallWatchdog()
        if (settings.isUsbMode) {
            if (usbMediaList.isEmpty()) return
            val item = usbMediaList[currentIndex]
            when {
                item.isImage -> {
                    releasePlayer()
                    showImageFromUri(item.playbackUri(), item.displayName)
                    imageTimer = Timer().apply {
                        schedule(object : TimerTask() { override fun run() { runOnUiThread { nextMedia() } } }, IMAGE_DURATION_MS)
                    }
                }
                item.isVideo -> showVideoFromUri(item.playbackUri(), item.displayName)
                else -> nextMedia()
            }
        } else {
            if (mediaList.isEmpty()) return
            val media = mediaList[currentIndex]
            when {
                media.isImage -> {
                    releasePlayer()
                    showImage(media)
                    imageTimer = Timer().apply {
                        val durationMs = ((media.playbackScheduleSeconds ?: (IMAGE_DURATION_MS / 1000L)) * 1000L).coerceAtLeast(1000L)
                        schedule(object : TimerTask() { override fun run() { runOnUiThread { nextMedia() } } }, durationMs)
                    }
                }
                media.isVideo -> showVideo(media)
                else -> nextMedia()
            }
        }
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

    private fun showImageFromUri(uri: Uri, displayName: String) {
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
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade())
            .override(Target.SIZE_ORIGINAL)
            .fitCenter()
            .error(android.R.drawable.ic_dialog_alert)
            .into(iv)
    }

    private fun showVideoFromUri(uri: Uri, displayName: String) {
        if (exoPlayer == null) {
            showCenterContent(createLoadingVideoView())
        }
        runOnUiThread {
            val loadControl = DefaultLoadControl.Builder()
                // Balanced buffering: starts faster, still avoids frequent stalls.
                // minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs
                .setBufferDurationsMs(4000, 30000, 1000, 2000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            if (exoPlayer == null) {
                playerView = PlayerView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    setKeepContentOnPlayerReset(true)
                }
                exoPlayer = ExoPlayer.Builder(this)
                    .setRenderersFactory(
                        DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                    )
                    .setLoadControl(loadControl)
                    .build()
                    .apply {
                        repeatMode = Player.REPEAT_MODE_OFF
                        playWhenReady = true
                        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    cancelVideoSchedule()
                                    nextMedia()
                                }
                                if (playbackState == Player.STATE_READY) {
                                    // USB/pendrive reads can buffer for a long time; stall watchdog would skip to next clip.
                                    if (!settings.isUsbMode) startStallWatchdog()
                                    // Only arm once: READY can fire again after buffering; second time pending is null
                                    if (pendingVideoScheduleSeconds != null) {
                                        armVideoScheduleIfNeeded(pendingVideoScheduleSeconds)
                                        pendingVideoScheduleSeconds = null
                                    }
                                }
                            }
                        })
                    }
                playerView?.player = exoPlayer
                displayCenter.removeAllViews()
                displayCenter.addView(playerView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            videoStallHandled = false
            pendingVideoScheduleSeconds = null
            exoPlayer?.apply {
                setMediaItem(ExoMediaItem.fromUri(uri))
                prepare()
                play()
            }
        }
    }

    /**
     * CMS video: play from a local file when possible (avoids stream decoder/audio-only issues on TV).
     * Otherwise download to cache on first play, then play from file; if download fails, fall back to stream.
     */
    private fun showVideo(media: CmsMediaItem) {
        val existing = media.localPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }
        if (existing != null) {
            playCmsVideoWithUri(Uri.fromFile(existing), media.playbackScheduleSeconds)
            return
        }
        val mac = settings.macAddress.trim()
        if (mac.isNotEmpty()) {
            showCenterContent(createLoadingVideoView())
            scope.launch {
                val path = withContext(Dispatchers.IO) { offlineMedia.cacheMediaFile(mac, media) }
                withContext(Dispatchers.Main) {
                    if (isFinishing) return@withContext
                    val file = path?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }
                    if (file != null) {
                        val idx = currentIndex
                        if (idx < mediaList.size && mediaList[idx].id == media.id) {
                            val updated = media.withLocalPath(file.absolutePath)
                            mediaList = mediaList.toMutableList().apply { this[idx] = updated }
                            offlineMedia.saveMediaList(mac, mediaList)
                        }
                        playCmsVideoWithUri(Uri.fromFile(file), media.playbackScheduleSeconds)
                    } else {
                        playCmsVideoWithUri(cmsVideoStreamUri(media), media.playbackScheduleSeconds)
                    }
                }
            }
            return
        }
        playCmsVideoWithUri(cmsVideoStreamUri(media), media.playbackScheduleSeconds)
    }

    private fun cmsVideoStreamUri(media: CmsMediaItem): Uri {
        val url = if (media.safePreviewUrl.isNotEmpty()) media.safePreviewUrl else streamUri(media.id)
        return Uri.parse(url)
    }

    private fun playCmsVideoWithUri(uri: Uri, scheduleSeconds: Long?) {
        if (exoPlayer == null) {
            showCenterContent(createLoadingVideoView())
        }
        runOnUiThread {
            val loadControl = DefaultLoadControl.Builder()
                // Balanced buffering: starts faster, still avoids frequent stalls.
                // minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs
                .setBufferDurationsMs(4000, 30000, 1000, 2000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            if (exoPlayer == null) {
                playerView = PlayerView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    setKeepContentOnPlayerReset(true)
                }
                exoPlayer = ExoPlayer.Builder(this)
                    .setRenderersFactory(
                        DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                    )
                    .setLoadControl(loadControl)
                    .build()
                    .apply {
                        repeatMode = Player.REPEAT_MODE_OFF
                        playWhenReady = true
                        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    cancelVideoSchedule()
                                    nextMedia()
                                }
                                if (playbackState == Player.STATE_READY) {
                                    if (!settings.isUsbMode) startStallWatchdog()
                                    // Only arm once: READY can fire again after buffering; do not cancel schedule on 2nd READY
                                    if (pendingVideoScheduleSeconds != null) {
                                        armVideoScheduleIfNeeded(pendingVideoScheduleSeconds)
                                        pendingVideoScheduleSeconds = null
                                    }
                                }
                            }
                        })
                    }
                playerView?.player = exoPlayer
                displayCenter.removeAllViews()
                displayCenter.addView(playerView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            videoStallHandled = false
            pendingVideoScheduleSeconds = scheduleSeconds
            exoPlayer?.apply {
                setMediaItem(ExoMediaItem.fromUri(uri))
                prepare()
                play()
            }
        }
    }

    private fun armVideoScheduleIfNeeded(scheduleSeconds: Long?) {
        val secs = scheduleSeconds?.takeIf { it > 0L } ?: return
        cancelVideoSchedule()
        videoScheduleRunnable = Runnable { nextMedia() }
        handler.postDelayed(videoScheduleRunnable!!, secs * 1000L)
    }

    private fun cancelVideoSchedule() {
        videoScheduleRunnable?.let { handler.removeCallbacks(it) }
        videoScheduleRunnable = null
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
        cancelVideoSchedule()
        cancelStallWatchdog()
        playerView?.player = null
        exoPlayer?.release()
        exoPlayer = null
        playerView = null
    }

    private fun nextMedia() {
        imageTimer?.cancel()
        cancelVideoSchedule()
        cancelStallWatchdog()
        if (settings.isUsbMode) {
            if (usbMediaList.isEmpty()) return
            val nextIndex = (currentIndex + 1) % usbMediaList.size
            val nextIsVideo = usbMediaList.getOrNull(nextIndex)?.isVideo == true
            if (!nextIsVideo) releasePlayer()
            currentIndex = nextIndex
        } else {
            if (mediaList.isEmpty()) return
            val nextIndex = (currentIndex + 1) % mediaList.size
            val nextIsVideo = mediaList.getOrNull(nextIndex)?.isVideo == true
            if (!nextIsVideo) releasePlayer()
            currentIndex = nextIndex
        }
        playCurrent()
    }
}
