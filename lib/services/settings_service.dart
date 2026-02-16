import 'package:shared_preferences/shared_preferences.dart';

/// Persists MAC address and API base URL for the display app.
class SettingsService {
  static const _keyMac = 'media_player_mac_address';
  static const _keyBaseUrl = 'media_player_base_url';
  static const _defaultBaseUrl = 'https://abettech.com/cms/public';

  final SharedPreferences _prefs;

  SettingsService(this._prefs);

  String get macAddress => _prefs.getString(_keyMac) ?? '';
  set macAddress(String value) => _prefs.setString(_keyMac, value);

  String get baseUrl {
    final url = _prefs.getString(_keyBaseUrl) ?? _defaultBaseUrl;
    return url.endsWith('/') ? url.substring(0, url.length - 1) : url;
  }
  set baseUrl(String value) {
    final trimmed = value.trim();
    final url = trimmed.endsWith('/') ? trimmed.substring(0, trimmed.length - 1) : trimmed;
    _prefs.setString(_keyBaseUrl, url);
  }

  bool get hasMac => macAddress.trim().isNotEmpty;
}
