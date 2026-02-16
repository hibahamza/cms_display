/// Represents a single media item from the CMS API.
/// API: GET /api/devices/{mac}/media returns { "data": [ MediaItem ] }
/// Optional [localPath] when cached offline via Hive.
class MediaItem {
  final int id;
  final String title;
  final String fileType;
  final int fileSize;
  final String previewUrl;
  final String? updatedAt;
  /// Local file path when cached for offline playback.
  final String? localPath;

  const MediaItem({
    required this.id,
    required this.title,
    required this.fileType,
    required this.fileSize,
    required this.previewUrl,
    this.updatedAt,
    this.localPath,
  });

  factory MediaItem.fromJson(Map<String, dynamic> json) {
    return MediaItem(
      id: (json['id'] as num).toInt(),
      title: json['title'] as String? ?? '',
      fileType: json['file_type'] as String? ?? 'image/jpeg',
      fileSize: (json['file_size'] as num?)?.toInt() ?? 0,
      previewUrl: json['preview_url'] as String? ?? '',
      updatedAt: json['updated_at'] as String?,
      localPath: json['local_path'] as String?,
    );
  }

  /// For Hive / offline storage.
  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'title': title,
      'file_type': fileType,
      'file_size': fileSize,
      'preview_url': previewUrl,
      'updated_at': updatedAt,
      if (localPath != null) 'local_path': localPath,
    };
  }

  factory MediaItem.fromMap(Map<String, dynamic> map) {
    return MediaItem(
      id: (map['id'] as num).toInt(),
      title: map['title'] as String? ?? '',
      fileType: map['file_type'] as String? ?? 'image/jpeg',
      fileSize: (map['file_size'] as num?)?.toInt() ?? 0,
      previewUrl: map['preview_url'] as String? ?? '',
      updatedAt: map['updated_at'] as String?,
      localPath: map['local_path'] as String?,
    );
  }

  /// Use for playback: local file path if cached, else network URL.
  String get playbackUrl => localPath ?? previewUrl;
  bool get isCached => localPath != null && localPath!.isNotEmpty;

  bool get isImage => fileType.startsWith('image/');
  bool get isVideo => fileType.startsWith('video/');
  bool get isPlayable => isImage || isVideo;

  String get formattedFileSize {
    if (fileSize < 1024) return '$fileSize B';
    if (fileSize < 1024 * 1024) return '${(fileSize / 1024).toStringAsFixed(1)} KB';
    return '${(fileSize / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
