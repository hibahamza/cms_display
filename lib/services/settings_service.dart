import 'package:shared_preferences/shared_preferences.dart';

/// Persists MAC address, API base URL, and optional pendrive / local-folder mode.
class SettingsService {
  static const _keyMac = 'media_player_mac_address';
  static const _keyBaseUrl = 'media_player_base_url';
  static const _keySource = 'display_source_mode';
  static const _keyUsbFolder = 'display_usb_folder_path';
  static const _keyUsbTree = 'display_usb_document_tree_uri';
  static const _keyUsbHasLocal = 'usb_has_local_playlist';
  static const _defaultBaseUrl = 'https://abettech.com/cms/public';

  /// Load media from CMS API using MAC.
  static const sourceCms = 'cms';

  /// Play images/videos from a user-chosen folder (e.g. pendrive mount path).
  static const sourceUsb = 'usb';

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

  String get sourceMode => _prefs.getString(_keySource) ?? sourceCms;
  set sourceMode(String value) => _prefs.setString(_keySource, value);

  bool get isUsbSourceMode => sourceMode == sourceUsb;
  bool get isCmsSourceMode => !isUsbSourceMode;

  String get usbFolderPath => _prefs.getString(_keyUsbFolder) ?? '';
  set usbFolderPath(String value) => _prefs.setString(_keyUsbFolder, value.trim());

  /// Android SAF tree URI from [ACTION_OPEN_DOCUMENT_TREE] (pendrive). Listing uses native DocumentFile.
  String get usbDocumentTreeUri => _prefs.getString(_keyUsbTree) ?? '';
  set usbDocumentTreeUri(String value) =>
      _prefs.setString(_keyUsbTree, value.trim());

  bool get usbHasLocalPlaylist => _prefs.getBool(_keyUsbHasLocal) ?? false;
  set usbHasLocalPlaylist(bool value) => _prefs.setBool(_keyUsbHasLocal, value);

  bool get hasMac => macAddress.trim().isNotEmpty;

  /// True when the app can open the full-screen player (CMS with MAC, or USB with folder).
  bool get canShowDisplay => isUsbSourceMode
      ? (usbFolderPath.trim().isNotEmpty ||
          usbDocumentTreeUri.trim().isNotEmpty ||
          usbHasLocalPlaylist)
      : hasMac;
}
