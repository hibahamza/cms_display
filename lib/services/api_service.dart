import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/media_item.dart';

/// Fetches media list for a device by MAC from the CMS API.
/// Uses path-style URL first (same as browser: /api/devices/{mac}/media) with MAC built into path
/// so colons are not misparsed by Uri on Android. Then falls back to query-style.
/// Response: { "data": [ { id, title, file_type, file_size, preview_url, updated_at } ] }
class ApiService {
  final String baseUrl;

  ApiService({required this.baseUrl});

  String _encodeMac(String mac) => Uri.encodeComponent(mac);

  String get _base {
    return baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
  }

  /// Build path-style URL from base components so MAC colons stay in path (avoids Uri.parse misparsing on Android).
  Uri _pathStyleUri(String mac) {
    final base = _base;
    final parsed = Uri.parse(base);
    final pathPrefix = parsed.path.replaceAll(RegExp(r'/$'), '');
    final path = '$pathPrefix/api/devices/$mac/media';
    return Uri(
      scheme: parsed.scheme,
      host: parsed.host,
      port: parsed.hasPort ? parsed.port : null,
      path: path,
    );
  }

  static const _timeout = Duration(seconds: 25);

  /// Try path-style first (GET /api/devices/{mac}/media), then query (GET /api/media?mac=...). Returns list or throws.
  Future<List<MediaItem>> getDeviceMedia(String mac) async {
    final base = _base;
    final pathStyle = _pathStyleUri(mac);
    final queryStyle = Uri.parse('$base/api/media').replace(queryParameters: {'mac': mac});
    final urlsToTry = [pathStyle, queryStyle];
    String? lastTriedUrl;
    ApiException? lastError;
    for (final url in urlsToTry) {
      lastTriedUrl = url.toString();
      try {
        final response = await http.get(url).timeout(_timeout);
        if (response.statusCode == 404) {
          lastError = ApiException('Not found (404). Try in browser: $lastTriedUrl', statusCode: 404);
          continue;
        }
        if (response.statusCode != 200) {
          lastError = ApiException('Server error: ${response.statusCode}', statusCode: response.statusCode);
          continue;
        }
        final body = jsonDecode(response.body) as Map<String, dynamic>;
        final data = body['data'];
        if (data == null || data is! List) return [];
        return _parseMediaList(data, base, mac);
      } catch (e) {
        lastError = ApiException(
          'Cannot reach server. Try in browser: $lastTriedUrl',
          statusCode: null,
        );
        continue;
      }
    }
    throw lastError ?? ApiException('Cannot reach server. URL: $lastTriedUrl');
  }

  List<MediaItem> _parseMediaList(List<dynamic> data, String base, String mac) {
    final list = <MediaItem>[];
    for (final item in data) {
      if (item is Map<String, dynamic>) {
        final media = MediaItem.fromJson(item);
        final serverPreview = item['preview_url'] as String?;
        final previewPath = '/media/devices/${_encodeMac(mac)}/${media.id}';
        final previewUrl = (serverPreview != null && serverPreview.isNotEmpty)
            ? serverPreview
            : '$base$previewPath';
        list.add(MediaItem(
          id: media.id,
          title: media.title,
          fileType: media.fileType,
          fileSize: media.fileSize,
          previewUrl: previewUrl,
          updatedAt: media.updatedAt,
        ));
      }
    }
    return list;
  }
}

class ApiException implements Exception {
  final String message;
  final int? statusCode;

  ApiException(this.message, {this.statusCode});

  @override
  String toString() => message;
}
