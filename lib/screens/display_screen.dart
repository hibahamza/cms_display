import 'dart:async';
import 'dart:io';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:video_player/video_player.dart';

import '../models/media_item.dart';
import '../services/api_service.dart';
import '../services/offline_media_service.dart';
import '../services/settings_service.dart';

/// Full-screen display: fetches media by MAC and plays images (10s) and videos (to end), then loops.
/// When online: fetches from API, saves list and caches files via Hive. When offline, plays from local cache.
class DisplayScreen extends StatefulWidget {
  const DisplayScreen({
    super.key,
    required this.settings,
    required this.offlineMedia,
    required this.onOpenSettings,
  });

  final SettingsService settings;
  final OfflineMediaService offlineMedia;
  final VoidCallback onOpenSettings;

  @override
  State<DisplayScreen> createState() => DisplayScreenState();
}

class DisplayScreenState extends State<DisplayScreen> {
  static const _imageDuration = Duration(seconds: 10);

  List<MediaItem> _mediaList = [];
  int _currentIndex = 0;
  bool _loading = true;
  String? _error;
  Timer? _imageTimer;
  VideoPlayerController? _videoController;
  bool _videoEndHandled = false;

  /// Temp file path when playing a downloaded stream (e.g. Android TV fallback); deleted on next/skip.
  String? _tempVideoPath;
  bool _videoDownloading = false;

  /// Brief message when video download/play fails (cleared on next media).
  String? _videoError;

  ApiService get _api => ApiService(baseUrl: widget.settings.baseUrl);
  String get _mac => widget.settings.macAddress;

  @override
  void initState() {
    super.initState();
    _fetchMedia();
  }

  @override
  void dispose() {
    _imageTimer?.cancel();
    _videoController?.dispose();
    super.dispose();
  }

  void refetch() => _fetchMedia();

  String _userFriendlyError(Object e) {
    if (e is ApiException) return e.message;
    final s = e.toString().toLowerCase();
    if (s.contains('failed to fetch') ||
        s.contains('clientexception') ||
        s.contains('connection')) {
      return 'Cannot reach server. Check internet or tap ⚙ and set Server URL.';
    }
    if (s.contains('404')) {
      return 'Device not found. Register this MAC on the server or check the address.';
    }
    if (s.contains('403') || s.contains('401')) {
      return 'Access denied. Check server settings.';
    }
    return 'Failed to load media. Using cached media if available.';
  }

  Future<bool?> _showSettingsDialog() async {
    final macController = TextEditingController(
      text: widget.settings.macAddress,
    );
    final urlController = TextEditingController(text: widget.settings.baseUrl);
    try {
      return await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: const Color(0xFF222222),
          title: const Text('Settings', style: TextStyle(color: Colors.white)),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // const Text(
                //   'Server URL',
                //   style: TextStyle(color: Colors.white70, fontSize: 14),
                // ),
                // const SizedBox(height: 6),
                // TextField(
                //   controller: urlController,
                //   style: const TextStyle(color: Colors.white),
                //   decoration: InputDecoration(
                //     hintText: 'https://abettech.com/cms/public',
                //     hintStyle: TextStyle(color: Colors.white.withOpacity(0.5)),
                //     border: const OutlineInputBorder(),
                //     enabledBorder: const OutlineInputBorder(
                //       borderSide: BorderSide(color: Colors.white38),
                //     ),
                //     focusedBorder: const OutlineInputBorder(
                //       borderSide: BorderSide(color: Colors.white),
                //     ),
                //   ),
                //   keyboardType: TextInputType.url,
                //   autocorrect: false,
                // ),
                // const SizedBox(height: 16),
                const Text(
                  'MAC Address',
                  style: TextStyle(color: Colors.white70, fontSize: 14),
                ),
                const SizedBox(height: 6),
                TextField(
                  controller: macController,
                  style: const TextStyle(color: Colors.white),
                  decoration: InputDecoration(
                    hintText: 'e.g. 58:c5:87:67:7e:39',
                    hintStyle: TextStyle(color: Colors.white.withOpacity(0.5)),
                    border: const OutlineInputBorder(),
                    enabledBorder: const OutlineInputBorder(
                      borderSide: BorderSide(color: Colors.white38),
                    ),
                    focusedBorder: const OutlineInputBorder(
                      borderSide: BorderSide(color: Colors.white),
                    ),
                  ),
                  textCapitalization: TextCapitalization.characters,
                  inputFormatters: [
                    FilteringTextInputFormatter.allow(
                      RegExp(r'[0-9A-Fa-f:.-]'),
                    ),
                  ],
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text(
                'Cancel',
                style: TextStyle(color: Colors.white70),
              ),
            ),
            FilledButton(
              onPressed: () {
                final mac = macController.text.trim();
                final url = urlController.text.trim();
                if (mac.isEmpty) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Please enter a MAC address')),
                  );
                  return;
                }
                if (url.isNotEmpty) {
                  widget.settings.baseUrl = url.endsWith('/')
                      ? url.substring(0, url.length - 1)
                      : url;
                }
                widget.settings.macAddress = mac;
                Navigator.of(ctx).pop(true);
              },
              style: FilledButton.styleFrom(
                backgroundColor: Colors.green.shade700,
              ),
              child: const Text('Save'),
            ),
          ],
        ),
      );
    } finally {
      macController.dispose();
      urlController.dispose();
    }
  }

  Future<void> _fetchMedia() async {
    if (_mac.trim().isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final results = await Connectivity().checkConnectivity();
    final isOnline =
        results.isNotEmpty &&
        results.any(
          (c) =>
              c == ConnectivityResult.wifi ||
              c == ConnectivityResult.mobile ||
              c == ConnectivityResult.ethernet,
        );

    if (isOnline) {
      try {
        final list = await _api.getDeviceMedia(_mac);
        var playable = list.where((m) => m.isPlayable).toList();
        playable = widget.offlineMedia.mergeWithLocalPaths(_mac, playable);
        await widget.offlineMedia.saveMediaList(_mac, playable);
        unawaited(widget.offlineMedia.cacheAllInBackground(_mac, playable));
        if (!mounted) return;
        setState(() {
          _mediaList = playable;
          _loading = false;
          _currentIndex = 0;
          _error = playable.isEmpty ? 'No media found for this device' : null;
        });
        if (playable.isNotEmpty) _playCurrent();
      } catch (e) {
        final msg = _userFriendlyError(e);
        await _loadFromOffline(msg);
      }
    } else {
      await _loadFromOffline('No internet connection');
    }
  }

  Future<void> _loadFromOffline([String? fetchError]) async {
    final cached = await widget.offlineMedia.getMediaList(_mac);
    if (!mounted) return;
    if (cached != null && cached.isNotEmpty) {
      final playable = cached.where((m) => m.isPlayable).toList();
      setState(() {
        _mediaList = playable;
        _loading = false;
        _currentIndex = 0;
        _error = null;
      });
      if (playable.isNotEmpty) _playCurrent();
    } else {
      setState(() {
        _loading = false;
        _error =
            fetchError ?? 'No media found. Connect to internet to load media.';
      });
    }
  }

  void _playCurrent() {
    if (_mediaList.isEmpty) return;
    _imageTimer?.cancel();
    _videoController?.dispose();
    _videoController = null;

    final media = _mediaList[_currentIndex];
    if (media.isImage) {
      _imageTimer = Timer(_imageDuration, _nextMedia);
    } else if (media.isVideo) {
      _playVideo(media);
    } else {
      _nextMedia();
    }
  }

  /// Build stream URI from base + path so Android doesn't misparse URLs with encoded MAC.
  Uri _streamUri(int mediaId) {
    final base = widget.settings.baseUrl.endsWith('/')
        ? widget.settings.baseUrl.substring(
            0,
            widget.settings.baseUrl.length - 1,
          )
        : widget.settings.baseUrl;
    final parsed = Uri.parse(base);
    final pathPrefix = parsed.path.replaceAll(RegExp(r'/$'), '');
    final encodedMac = Uri.encodeComponent(_mac);
    final path = '$pathPrefix/media/devices/$encodedMac/$mediaId';
    return Uri(
      scheme: parsed.scheme,
      host: parsed.host,
      port: parsed.hasPort ? parsed.port : null,
      path: path,
    );
  }

  /// Headers for video stream. Browser-like UA often works better on Android TV than ExoPlayer UA.
  static const _videoHeaders = <String, String>{
    'User-Agent':
        'Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Safari/537.36',
    'Accept': '*/*',
  };

  void _playVideo(MediaItem media, {int retryCount = 0}) {
    print('🎥 DEBUG: Starting video playback for media ID: ${media.id}');
    print('🎥 DEBUG: Media title: ${media.title}');
    print('🎥 DEBUG: File type: ${media.fileType}');
    print('🎥 DEBUG: Preview URL: ${media.previewUrl}');
    print('🎥 DEBUG: Is cached: ${media.isCached}');
    print('🎥 DEBUG: Local path: ${media.localPath}');
    print('🎥 DEBUG: Retry count: $retryCount');

    _videoEndHandled = false;
    if (media.isCached && media.localPath != null) {
      print('🎥 DEBUG: Using cached file: ${media.localPath}');
      _videoController = VideoPlayerController.file(File(media.localPath!));
      _initAndPlayVideoController();
      return;
    }
    // Use exact previewUrl from API (same URL that works for images).
    final videoUri = media.previewUrl.isNotEmpty
        ? Uri.parse(media.previewUrl)
        : _streamUri(media.id);

    print('🎥 DEBUG: Final video URI: $videoUri');

    _videoController = VideoPlayerController.networkUrl(
      videoUri,
      httpHeaders: _videoHeaders,
    );
    _videoController!.setLooping(false);
    print('🎥 DEBUG: Starting video initialization...');
    _videoController!
        .initialize()
        .timeout(
          const Duration(seconds: 30),
          onTimeout: () => throw TimeoutException('Video load timeout'),
        )
        .then((_) {
          print('🎥 DEBUG: Video initialized successfully!');
          print(
            '🎥 DEBUG: Video duration: ${_videoController!.value.duration}',
          );
          print('🎥 DEBUG: Video size: ${_videoController!.value.size}');
          print(
            '🎥 DEBUG: Video aspect ratio: ${_videoController!.value.aspectRatio}',
          );
          if (!mounted) return;
          _videoController!.play();
          print('🎥 DEBUG: Video play() called');
          setState(() {});
          _videoController!.addListener(_videoListener);
        })
        .catchError((e) {
          print('🎥 DEBUG: Video initialization failed: $e');
          print('🎥 DEBUG: Error type: ${e.runtimeType}');
          if (!mounted) return;
          if (retryCount < 1) {
            print('🎥 DEBUG: Retrying video playback...');
            _videoController?.dispose();
            _videoController = null;
            _playVideo(media, retryCount: retryCount + 1);
          } else {
            print('🎥 DEBUG: Max retries reached, skipping to next media');
            _nextMedia();
          }
        });
  }

  /// Android: try streaming with previewUrl first; on failure, download then play.
  void _tryStreamThenDownload(MediaItem media, Uri videoUri) {
    _videoController = VideoPlayerController.networkUrl(
      videoUri,
      httpHeaders: _videoHeaders,
    );
    _videoController!.setLooping(false);
    _videoController!
        .initialize()
        .timeout(
          const Duration(seconds: 25),
          onTimeout: () => throw TimeoutException('Stream timeout'),
        )
        .then((_) {
          if (!mounted) return;
          _videoController!.play();
          setState(() {});
          _videoController!.addListener(_videoListener);
        })
        .catchError((e) {
          if (!mounted) return;
          _videoController?.dispose();
          _videoController = null;
          // Fall back to download (longer timeout for large files).
          _downloadAndPlayVideo(media);
        });
  }

  void _initAndPlayVideoController() {
    print('🎥 DEBUG: Initializing cached video controller...');
    _videoController!.setLooping(false);
    _videoController!
        .initialize()
        .timeout(
          const Duration(seconds: 20),
          onTimeout: () => throw TimeoutException('Video load timeout'),
        )
        .then((_) {
          print('🎥 DEBUG: Cached video initialized successfully!');
          print(
            '🎥 DEBUG: Video duration: ${_videoController!.value.duration}',
          );
          print('🎥 DEBUG: Video size: ${_videoController!.value.size}');
          print(
            '🎥 DEBUG: Video aspect ratio: ${_videoController!.value.aspectRatio}',
          );
          if (!mounted) return;
          _videoController!.play();
          print('🎥 DEBUG: Cached video play() called');
          setState(() {});
          _videoController!.addListener(_videoListener);
        })
        .catchError((e) {
          print('🎥 DEBUG: Cached video initialization failed: $e');
          print('🎥 DEBUG: Error type: ${e.runtimeType}');
          if (mounted) _nextMedia();
        });
  }

  /// Android: download video using previewUrl from API, then play from file. Long timeout for large files.
  Future<void> _downloadAndPlayVideo(MediaItem media) async {
    _videoEndHandled = false;
    _videoError = null;
    if (mounted) setState(() => _videoDownloading = true);
    final uri = media.previewUrl.isNotEmpty
        ? Uri.parse(media.previewUrl)
        : _streamUri(media.id);
    // 100MB+ needs ~5 min on slow links; minimum 2 min.
    final seconds = (120 + (media.fileSize / (2 * 1024 * 1024)).ceil()).clamp(
      120,
      600,
    );
    final overallTimeout = Duration(seconds: seconds);
    try {
      await _downloadAndPlayVideoInner(media, uri).timeout(
        overallTimeout,
        onTimeout: () =>
            throw TimeoutException('Timeout (${overallTimeout.inSeconds}s)'),
      );
    } catch (e) {
      if (mounted) _showVideoErrorAndSkip(e, media);
    } finally {
      if (mounted) setState(() => _videoDownloading = false);
    }
  }

  Future<void> _downloadAndPlayVideoInner(MediaItem media, Uri uri) async {
    final client = HttpClient();
    client.connectionTimeout = const Duration(seconds: 15);
    client.idleTimeout = const Duration(seconds: 60);
    try {
      final request = await client.getUrl(uri);
      for (final e in _videoHeaders.entries) {
        request.headers.set(e.key, e.value);
      }
      final response = await request.close();
      if (response.statusCode != 200 && response.statusCode != 206) {
        throw Exception('HTTP ${response.statusCode}');
      }
      final dir = await getTemporaryDirectory();
      final ext = media.fileType.contains('mp4')
          ? 'mp4'
          : (media.fileType.contains('webm') ? 'webm' : 'mp4');
      final file = File('${dir.path}/stream_${media.id}.$ext');
      final sink = file.openWrite();
      await response.pipe(sink);
      await sink.close();
      client.close();
      if (!file.existsSync() || file.lengthSync() == 0) {
        throw Exception('Downloaded file empty');
      }
      if (!mounted) return;
      _tempVideoPath = file.path;
      _videoController = VideoPlayerController.file(file);
      await _videoController!.initialize().timeout(
        const Duration(seconds: 20),
        onTimeout: () => throw TimeoutException('Video init timeout'),
      );
      if (!mounted) return;
      _videoController!.play();
      setState(() {});
      _videoController!.addListener(_videoListener);
    } finally {
      client.close(force: true);
    }
  }

  String? _videoErrorUrl;

  void _showVideoErrorAndSkip(Object e, MediaItem media) {
    final msg = e is TimeoutException
        ? (e.message ?? 'Timeout')
        : (e is Exception ? e.toString() : 'Video failed');
    setState(() {
      _videoDownloading = false;
      _videoError = msg;
      _videoErrorUrl = media.previewUrl.isNotEmpty ? media.previewUrl : null;
    });
    Future.delayed(const Duration(seconds: 3), () {
      if (!mounted) return;
      setState(() {
        _videoError = null;
        _videoErrorUrl = null;
      });
      _nextMedia();
    });
  }

  void _videoListener() {
    if (_videoController == null || !mounted || _videoEndHandled) return;
    final value = _videoController!.value;
    final pos = value.position;
    final dur = value.duration;
    final hasError = value.hasError;
    final errorDescription = value.errorDescription;

    if (hasError) {
      print('🎥 DEBUG: Video error detected!');
      print('🎥 DEBUG: Error description: $errorDescription');
      // On Windows (and other platforms), an unhandled error here can leave the
      // UI stuck on a loading spinner. Surface the error briefly, then skip.
      if (_mediaList.isNotEmpty) {
        final media = _mediaList[_currentIndex];
        _showVideoErrorAndSkip(
          errorDescription ?? 'Video playback error',
          media,
        );
      } else {
        _nextMedia();
      }
      return;
    }

    if (dur.inMilliseconds > 0 &&
        pos >= dur - const Duration(milliseconds: 500)) {
      print('🎥 DEBUG: Video ended, moving to next media');
      _videoEndHandled = true;
      _videoController!.removeListener(_videoListener);
      _nextMedia();
    }
  }

  void _nextMedia() {
    _imageTimer?.cancel();
    _videoController?.removeListener(_videoListener);
    _videoController?.dispose();
    _videoController = null;
    _videoDownloading = false;
    _videoError = null;
    _videoErrorUrl = null;
    if (_tempVideoPath != null) {
      try {
        File(_tempVideoPath!).deleteSync();
      } catch (_) {}
      _tempVideoPath = null;
    }
    if (_mediaList.isEmpty) return;
    setState(() {
      _currentIndex = (_currentIndex + 1) % _mediaList.length;
    });
    _playCurrent();
  }

  Widget _buildStackContent() {
    if (_loading) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(color: Colors.white),
            SizedBox(height: 16),
            Text('Loading media...', style: TextStyle(color: Colors.white)),
          ],
        ),
      );
    }
    if (_error != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.red, fontSize: 16),
              ),
              const SizedBox(height: 16),
              FilledButton(onPressed: _fetchMedia, child: const Text('Retry')),
            ],
          ),
        ),
      );
    }
    if (_mediaList.isNotEmpty) return _buildPlayer();
    return const Center(
      child: Text('No media found', style: TextStyle(color: Colors.white70)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          _buildStackContent(),
          Positioned(
            left: 10,
            top: 10,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                'MAC: $_mac',
                style: const TextStyle(color: Colors.white70, fontSize: 14),
              ),
            ),
          ),
          Positioned(
            right: 10,
            top: 10,
            child: IconButton(
              icon: const Icon(Icons.settings, color: Colors.white70),
              onPressed: () async {
                final saved = await _showSettingsDialog();
                if (saved == true) refetch();
              },
            ),
          ),
          if (_mediaList.isNotEmpty && !_loading && _error == null)
            Positioned(
              bottom: 10,
              right: 10,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  '${_currentIndex + 1}/${_mediaList.length} - ${_mediaList[_currentIndex].title}',
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildPlayer() {
    final media = _mediaList[_currentIndex];
    if (media.isImage) {
      final useFile = media.isCached && media.localPath != null;
      return InteractiveViewer(
        minScale: 0.5,
        maxScale: 4,
        child: Center(
          child: useFile
              ? Image.file(
                  File(media.localPath!),
                  fit: BoxFit.contain,
                  errorBuilder: (_, __, ___) {
                    WidgetsBinding.instance.addPostFrameCallback(
                      (_) => _nextMedia(),
                    );
                    return const Center(
                      child: Text(
                        'Failed to load image',
                        style: TextStyle(color: Colors.white70),
                      ),
                    );
                  },
                )
              : Image.network(
                  media.previewUrl,
                  fit: BoxFit.contain,
                  loadingBuilder: (_, child, progress) {
                    if (progress == null) return child;
                    return const Center(
                      child: CircularProgressIndicator(color: Colors.white),
                    );
                  },
                  errorBuilder: (_, __, ___) {
                    WidgetsBinding.instance.addPostFrameCallback(
                      (_) => _nextMedia(),
                    );
                    return const Center(
                      child: Text(
                        'Failed to load image',
                        style: TextStyle(color: Colors.white70),
                      ),
                    );
                  },
                ),
        ),
      );
    }
    if (media.isVideo &&
        _videoController != null &&
        _videoController!.value.isInitialized) {
      return Center(
        child: AspectRatio(
          aspectRatio: _videoController!.value.aspectRatio,
          child: VideoPlayer(_videoController!),
        ),
      );
    }
    if (media.isVideo && _videoError != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _videoError!,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.orange, fontSize: 16),
              ),
              const SizedBox(height: 8),
              const Text(
                'Skipping in 3s...',
                style: TextStyle(color: Colors.white54, fontSize: 14),
              ),
              if (_videoErrorUrl != null) ...[
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: () async {
                    final uri = Uri.parse(_videoErrorUrl!);
                    if (await canLaunchUrl(uri)) {
                      await launchUrl(
                        uri,
                        mode: LaunchMode.externalApplication,
                      );
                    }
                  },
                  icon: const Icon(Icons.open_in_new, size: 20),
                  label: const Text('Open in player'),
                ),
              ],
            ],
          ),
        ),
      );
    }
    if (media.isVideo && _videoDownloading) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(color: Colors.white),
            SizedBox(height: 16),
            Text('Loading video...', style: TextStyle(color: Colors.white70)),
          ],
        ),
      );
    }
    return const Center(child: CircularProgressIndicator(color: Colors.white));
  }
}
