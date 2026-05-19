import 'dart:io';

import 'package:path/path.dart' as p;

import '../models/media_item.dart';

const _imageExts = {'jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'};
const _videoExts = {'mp4', 'webm', '3gp', 'avi', 'mov', 'mkv'};

String _mimeFromExt(String ext) {
  switch (ext) {
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg';
    case 'png':
      return 'image/png';
    case 'gif':
      return 'image/gif';
    case 'webp':
      return 'image/webp';
    case 'bmp':
      return 'image/bmp';
    case 'mp4':
      return 'video/mp4';
    case 'webm':
      return 'video/webm';
    case '3gp':
      return 'video/3gpp';
    case 'avi':
      return 'video/x-msvideo';
    case 'mov':
      return 'video/quicktime';
    case 'mkv':
      return 'video/x-matroska';
    default:
      return 'application/octet-stream';
  }
}

/// Top-level for [compute] — scans [rootPath] recursively for images/videos.
List<MediaItem> scanLocalMediaFolderSync(String rootPath) {
  final root = Directory(rootPath);
  if (!root.existsSync()) return [];

  final out = <MediaItem>[];

  void walk(Directory dir) {
    List<FileSystemEntity> children;
    try {
      children = dir.listSync(followLinks: false);
    } catch (_) {
      return;
    }
    for (final entity in children) {
      if (entity is Directory) {
        walk(entity);
      } else if (entity is File) {
        final name = p.basename(entity.path);
        final dot = name.lastIndexOf('.');
        final ext = dot >= 0
            ? name.substring(dot + 1).toLowerCase()
            : '';
        final isImage = _imageExts.contains(ext);
        final isVideo = _videoExts.contains(ext);
        if (!isImage && !isVideo) continue;

        int size = 0;
        try {
          size = entity.lengthSync();
        } catch (_) {}

        final rel = p.relative(entity.path, from: rootPath);
        final mime = _mimeFromExt(ext);
        out.add(
          MediaItem(
            id: entity.path.hashCode,
            title: rel,
            fileType: mime,
            fileSize: size,
            previewUrl: '',
            localPath: entity.path,
          ),
        );
      }
    }
  }

  walk(root);
  out.sort(
    (a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase()),
  );
  return out;
}
