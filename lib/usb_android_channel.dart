import 'dart:io';

import 'package:flutter/services.dart';

import 'package:cms_display_app/models/media_item.dart';

/// Android pendrive via Storage Access Framework ([ACTION_OPEN_DOCUMENT_TREE]).
class UsbAndroidChannel {
  UsbAndroidChannel._();

  static const MethodChannel _ch = MethodChannel('com.cms.cms_display_app/usb');

  static bool get isSupported => Platform.isAndroid;

  /// Opens system folder picker; returns persisted tree `content://` URI or null.
  static Future<String?> openDocumentTree() async {
    if (!isSupported) return null;
    final r = await _ch.invokeMethod<String?>('openDocumentTree');
    final t = r?.trim();
    if (t == null || t.isEmpty) return null;
    return t;
  }

  static Future<List<MediaItem>> listTreeMedia(String treeUri) async {
    if (!isSupported) return [];
    final raw = await _ch.invokeMethod<List<dynamic>>('listTreeMedia', treeUri);
    if (raw == null) return [];
    final out = <MediaItem>[];
    for (final e in raw) {
      if (e is! Map) continue;
      final m = Map<String, dynamic>.from(e);
      final uri = m['uri'] as String?;
      final name = m['name'] as String? ?? '';
      final mime = m['mime'] as String? ?? 'application/octet-stream';
      if (uri == null || uri.isEmpty) continue;
      final item = MediaItem(
        id: uri.hashCode,
        title: name,
        fileType: mime,
        fileSize: 0,
        previewUrl: '',
        localPath: uri,
      );
      if (item.isPlayable) out.add(item);
    }
    return out;
  }

  static Future<void> copyUriToFile(String contentUri, String destPath) async {
    if (!isSupported) {
      throw UnsupportedError('copyUriToFile is Android-only');
    }
    try {
      await _ch.invokeMethod<void>(
        'copyUriToFile',
        <String, String>{'uri': contentUri, 'destPath': destPath},
      );
    } on PlatformException catch (e) {
      throw Exception(e.message ?? 'copyUriToFile failed');
    }
  }

  /// Copies a document [contentUri] to app cache for widgets that need a real file path.
  static Future<String?> copyUriToCacheFile(String contentUri) async {
    if (!isSupported) return null;
    try {
      final path = await _ch.invokeMethod<String?>('copyUriToCache', contentUri);
      if (path == null || path.isEmpty) return null;
      return path;
    } on PlatformException {
      return null;
    }
  }
}
