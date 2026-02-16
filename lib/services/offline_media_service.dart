import 'dart:convert';
import 'dart:io';

import 'package:hive_flutter/hive_flutter.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;

import '../models/media_item.dart';

/// Stores media list and files locally via Hive + file system for offline playback.
/// After restart with no internet, media is loaded from Hive and played from cached files.
class OfflineMediaService {
  static const _boxName = 'display_cache';
  static const _listPrefix = 'list_';
  static const _pathsPrefix = 'paths_';
  static const _mediaDirName = 'cms_media';

  Box<dynamic>? _box;

  Future<void> init() async {
    if (_box != null) return;
    await Hive.initFlutter();
    _box = await Hive.openBox(_boxName);
  }

  String _listKey(String mac) => '$_listPrefix$mac';
  String _pathsKey(String mac) => '$_pathsPrefix$mac';

  /// Save media list for this MAC (e.g. after successful API fetch).
  Future<void> saveMediaList(String mac, List<MediaItem> list) async {
    await init();
    final maps = list.map((m) => _itemWithLocalPath(m, mac)).toList();
    await _box!.put(_listKey(mac), jsonEncode(maps));
  }

  Map<String, dynamic> _itemWithLocalPath(MediaItem m, String mac) {
    final map = m.toMap();
    final path = getLocalPath(mac, m.id);
    if (path != null) map['local_path'] = path;
    return map;
  }

  /// Load media list from local storage. Returns null if none saved.
  Future<List<MediaItem>?> getMediaList(String mac) async {
    await init();
    final data = _box!.get(_listKey(mac));
    if (data == null || data is! String) return null;
    List<dynamic> decoded;
    try {
      decoded = jsonDecode(data) as List<dynamic>;
    } catch (_) {
      return null;
    }
    final paths = _getPathsMap(mac);
    final list = <MediaItem>[];
    for (final e in decoded) {
      if (e is Map<String, dynamic>) {
        final map = Map<String, dynamic>.from(e);
        final id = (map['id'] as num?)?.toInt();
        if (id != null && paths[id.toString()] != null) {
          map['local_path'] = paths[id.toString()];
        }
        list.add(MediaItem.fromMap(map));
      }
    }
    return list.isEmpty ? null : list;
  }

  Map<String, String> _getPathsMap(String mac) {
    final data = _box!.get(_pathsKey(mac));
    if (data == null || data is! String) return {};
    try {
      final decoded = jsonDecode(data) as Map<String, dynamic>;
      return decoded.map((k, v) => MapEntry(k, v?.toString() ?? ''));
    } catch (_) {
      return {};
    }
  }

  /// Get local file path for a media id if cached.
  String? getLocalPath(String mac, int mediaId) {
    if (_box == null) return null;
    final paths = _getPathsMap(mac);
    return paths[mediaId.toString()];
  }

  /// Save local path for a media id (after downloading).
  Future<void> saveLocalPath(String mac, int mediaId, String filePath) async {
    await init();
    final paths = _getPathsMap(mac);
    paths[mediaId.toString()] = filePath;
    await _box!.put(_pathsKey(mac), jsonEncode(paths));
  }

  /// Directory for cached media files for this MAC.
  Future<Directory> _mediaDir(String mac) async {
    final root = await getApplicationDocumentsDirectory();
    final dir = Directory(path.join(root.path, _mediaDirName, _sanitizeMac(mac)));
    if (!await dir.exists()) await dir.create(recursive: true);
    return dir;
  }

  String _sanitizeMac(String mac) => mac.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '_');

  String _extensionFromMime(String fileType) {
    if (fileType.contains('jpeg') || fileType.contains('jpg')) return 'jpg';
    if (fileType.contains('png')) return 'png';
    if (fileType.contains('gif')) return 'gif';
    if (fileType.contains('webp')) return 'webp';
    if (fileType.contains('mp4')) return 'mp4';
    if (fileType.contains('webm')) return 'webm';
    if (fileType.contains('mov')) return 'mov';
    return 'bin';
  }

  /// Download media file and save locally; store path in Hive.
  Future<String?> cacheMediaFile(String mac, MediaItem media) async {
    try {
      final dir = await _mediaDir(mac);
      final ext = _extensionFromMime(media.fileType);
      final file = File(path.join(dir.path, '${media.id}.$ext'));
      final response = await http.get(Uri.parse(media.previewUrl));
      if (response.statusCode != 200) return null;
      await file.writeAsBytes(response.bodyBytes);
      await saveLocalPath(mac, media.id, file.path);
      return file.path;
    } catch (_) {
      return null;
    }
  }

  /// Cache all media files in background. Call after saving list when online.
  Future<void> cacheAllInBackground(String mac, List<MediaItem> list) async {
    for (final media in list) {
      if (!media.isPlayable) continue;
      if (getLocalPath(mac, media.id) != null) continue;
      await cacheMediaFile(mac, media);
    }
    await _refreshListWithPaths(mac);
  }

  /// After caching files, update stored list so each item has local_path.
  Future<void> _refreshListWithPaths(String mac) async {
    final list = await getMediaList(mac);
    if (list == null) return;
    final updated = list.map((m) {
      final p = getLocalPath(mac, m.id);
      return MediaItem(
        id: m.id,
        title: m.title,
        fileType: m.fileType,
        fileSize: m.fileSize,
        previewUrl: m.previewUrl,
        updatedAt: m.updatedAt,
        localPath: p,
      );
    }).toList();
    await saveMediaList(mac, updated);
  }

  /// Merge API list with any existing local paths so playback uses cache when available.
  List<MediaItem> mergeWithLocalPaths(String mac, List<MediaItem> fromApi) {
    return fromApi.map((m) {
      final local = getLocalPath(mac, m.id);
      if (local == null) return m;
      return MediaItem(
        id: m.id,
        title: m.title,
        fileType: m.fileType,
        fileSize: m.fileSize,
        previewUrl: m.previewUrl,
        updatedAt: m.updatedAt,
        localPath: local,
      );
    }).toList();
  }
}
