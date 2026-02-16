import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'screens/display_screen.dart';
import 'screens/settings_screen.dart';
import 'services/offline_media_service.dart';
import 'services/settings_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final settings = SettingsService(prefs);
  final offlineMedia = OfflineMediaService();
  await offlineMedia.init();

  runApp(CmsDisplayApp(settings: settings, offlineMedia: offlineMedia));
}

class CmsDisplayApp extends StatefulWidget {
  const CmsDisplayApp({super.key, required this.settings, required this.offlineMedia});

  final SettingsService settings;
  final OfflineMediaService offlineMedia;

  @override
  State<CmsDisplayApp> createState() => _CmsDisplayAppState();
}

class _CmsDisplayAppState extends State<CmsDisplayApp> {
  final _displayKey = GlobalKey<DisplayScreenState>();

  void _openSettings() {
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CMS Display',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.dark(
          primary: Colors.green.shade700,
          surface: Colors.black,
          onSurface: Colors.white,
        ),
        useMaterial3: true,
      ),
      home: widget.settings.hasMac
          ? DisplayScreen(
              key: _displayKey,
              settings: widget.settings,
              offlineMedia: widget.offlineMedia,
              onOpenSettings: () async {
                final refetch = await Navigator.of(context).push<bool>(
                  MaterialPageRoute(
                    builder: (context) => SettingsScreen(
                      settings: widget.settings,
                      onSaved: () => Navigator.of(context).pop(true),
                    ),
                  ),
                );
                if (refetch == true && context.mounted) {
                  _displayKey.currentState?.refetch();
                }
              },
            )
          : SettingsScreen(
              settings: widget.settings,
              onSaved: _openSettings,
            ),
    );
  }
}
