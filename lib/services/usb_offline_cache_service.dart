import 'dart:convert';
import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'package:cms_display_app/models/media_item.dart';
import 'package:cms_display_app/usb_android_channel.dart';

/// Copies pendrive/USB playlist into app support storage and saves a manifest
/// so playback continues after the drive is unplugged.
class UsbOfflineCacheService {
  UsbOfflineCacheService._();
  static final UsbOfflineCacheService instance = UsbOfflineCacheService._();

  static const _manifestName = 'manifest.json';

  /// Deletes cached USB files and manifest (e.g. when switching to CMS mode).
  Future<void> clearAll() async {
    try {
      final base = await getApplicationSupportDirectory();
      for (final name in ['usb_offline', 'usb_offline_staging']) {
        final d = Directory(p.join(base.path, name));
        if (await d.exists()) await d.delete(recursive: true);
      }
    } catch (_) {}
  }

  /// Returns a playable list if manifest matches [sourceKeyFromSettings] and all files exist.
  /// If [sourceKeyFromSettings] is empty but [hasLocalFlag] is true, accepts any valid manifest
  /// (recovery when prefs were partially cleared).
  Future<List<MediaItem>?> tryLoadCachedPlaylist({
    required String sourceKeyFromSettings,
    required bool hasLocalFlag,
  }) async {
    try {
      final base = await getApplicationSupportDirectory();
      final root = Directory(p.join(base.path, 'usb_offline'));
      if (!await root.exists()) return null;
      final manifestFile = File(p.join(root.path, _manifestName));
      if (!await manifestFile.exists()) return null;
      final map = jsonDecode(await manifestFile.readAsString()) as Map<String, dynamic>;
      final manifestKey = map['sourceKey'] as String? ?? '';
      final raw = map['items'];
      if (manifestKey.isEmpty || raw is! List) return null;

      final keyOk = sourceKeyFromSettings.isNotEmpty
          ? manifestKey == sourceKeyFromSettings
          : hasLocalFlag;
      if (!keyOk) return null;

      final out = <MediaItem>[];
      for (final e in raw) {
        if (e is! Map) return null;
        final item = MediaItem.fromMap(Map<String, dynamic>.from(e));
        final path = item.localPath;
        if (path == null ||
            path.isEmpty ||
            path.startsWith('content://')) {
          return null;
        }
        if (!await File(path).exists()) return null;
        out.add(item);
      }
      return out.isEmpty ? null : out;
    } catch (_) {
      return null;
    }
  }

  /// Copies [remote] into app storage under `usb_offline/media/` and writes [manifest.json].
  /// Uses a staging folder so a failed import does not delete the previous library.
  Future<List<MediaItem>> importAndPersist(
    List<MediaItem> remote,
    String sourceKeyForManifest,
  ) async {
    if (sourceKeyForManifest.isEmpty) {
      throw ArgumentError('sourceKeyForManifest must not be empty');
    }
    final base = await getApplicationSupportDirectory();
    final finalRoot = Directory(p.join(base.path, 'usb_offline'));
    await finalRoot.create(recursive: true);

    final stagingMedia = Directory(p.join(finalRoot.path, 'media_new'));
    if (await stagingMedia.exists()) {
      await stagingMedia.delete(recursive: true);
    }
    await stagingMedia.create(recursive: true);

    final out = <MediaItem>[];
    try {
      for (var i = 0; i < remote.length; i++) {
      final item = remote[i];
      final src = item.localPath;
      if (src == null || src.isEmpty) continue;

      final destName = _safeDestName(item.title, i);
      final destPath = p.join(stagingMedia.path, destName);

      if (Platform.isAndroid && src.startsWith('content://')) {
        await UsbAndroidChannel.copyUriToFile(src, destPath);
      } else {
        await _copyPathToPath(src, destPath);
      }

      final f = File(destPath);
      if (!await f.exists() || await f.length() == 0) {
        throw UsbOfflineCopyException(
          'Copy failed or empty file: ${item.title}',
        );
      }
      final len = await f.length();
      out.add(
        MediaItem(
          id: item.id,
          title: item.title,
          fileType: item.fileType,
          fileSize: len,
          previewUrl: '',
          playbackScheduleSeconds: item.playbackScheduleSeconds,
          localPath: destPath,
        ),
      );
    }

      if (out.isEmpty) {
        throw UsbOfflineCopyException('No playable files to import.');
      }

      final oldMedia = Directory(p.join(finalRoot.path, 'media'));
      if (await oldMedia.exists()) {
        await oldMedia.delete(recursive: true);
      }
      await stagingMedia.rename(p.join(finalRoot.path, 'media'));

      final mediaDir = p.join(finalRoot.path, 'media');
      final fixed = out
          .map(
            (m) => MediaItem(
              id: m.id,
              title: m.title,
              fileType: m.fileType,
              fileSize: m.fileSize,
              previewUrl: '',
              playbackScheduleSeconds: m.playbackScheduleSeconds,
              localPath: p.join(mediaDir, p.basename(m.localPath!)),
            ),
          )
          .toList();

      final manifestFile = File(p.join(finalRoot.path, _manifestName));
      await manifestFile.writeAsString(
        jsonEncode({
          'sourceKey': sourceKeyForManifest,
          'items': fixed.map((e) => e.toMap()).toList(),
        }),
      );
      return fixed;
    } catch (e) {
      if (await stagingMedia.exists()) {
        try {
          await stagingMedia.delete(recursive: true);
        } catch (_) {}
      }
      rethrow;
    }
  }

  Future<void> _copyPathToPath(String srcPath, String destPath) async {
    final src = File(srcPath);
    if (!await src.exists()) {
      throw UsbOfflineCopyException('Source missing: $srcPath');
    }
    final dest = File(destPath);
    await dest.parent.create(recursive: true);
    final sink = dest.openWrite();
    try {
      await src.openRead().pipe(sink);
    } finally {
      await sink.close();
    }
  }

  String _safeDestName(String title, int index) {
    var base = title
        .replaceAll(RegExp(r'[<>:"/\\|?*]'), '_')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    if (base.isEmpty) base = 'media';
    if (base.length > 80) base = base.substring(0, 80);
    return '${index.toString().padLeft(4, '0')}_$base';
  }
}

/// Copy/import failure for USB offline cache.
class UsbOfflineCopyException implements Exception {
  UsbOfflineCopyException(this.message);
  final String message;
  @override
  String toString() => message;
}
