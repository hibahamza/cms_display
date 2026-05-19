import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/settings_service.dart';
import '../services/usb_offline_cache_service.dart';
import '../usb_android_channel.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    required this.settings,
    required this.onSaved,
  });

  final SettingsService settings;
  final VoidCallback onSaved;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController _macController;
  late bool _usbMode;
  String _usbPick = '';

  @override
  void initState() {
    super.initState();
    _macController = TextEditingController(text: widget.settings.macAddress);
    _usbMode = widget.settings.isUsbSourceMode;
    _usbPick = widget.settings.usbDocumentTreeUri.trim().isNotEmpty
        ? widget.settings.usbDocumentTreeUri
        : widget.settings.usbFolderPath;
  }

  @override
  void dispose() {
    _macController.dispose();
    super.dispose();
  }

  void _save() {
    if (!_usbMode) {
      final mac = _macController.text.trim();
      if (mac.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Please enter a MAC address')),
        );
        return;
      }
      widget.settings.sourceMode = SettingsService.sourceCms;
      widget.settings.macAddress = mac;
      widget.settings.usbHasLocalPlaylist = false;
      unawaited(UsbOfflineCacheService.instance.clearAll());
      widget.onSaved();
      return;
    }
    if (_usbPick.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Choose a USB folder for Pendrive playback'),
        ),
      );
      return;
    }
    widget.settings.sourceMode = SettingsService.sourceUsb;
    if (Platform.isAndroid && _usbPick.trim().startsWith('content://')) {
      widget.settings.usbDocumentTreeUri = _usbPick.trim();
      widget.settings.usbFolderPath = '';
    } else {
      widget.settings.usbFolderPath = _usbPick.trim();
      widget.settings.usbDocumentTreeUri = '';
    }
    widget.onSaved();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  'CMS Display',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.bold,
                      ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Choose how to load media',
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: Colors.white70,
                      ),
                ),
                const SizedBox(height: 24),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    'Media source',
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          color: Colors.white70,
                        ),
                  ),
                ),
                RadioListTile<bool>(
                  title: const Text(
                    'CMS (network)',
                    style: TextStyle(color: Colors.white),
                  ),
                  value: false,
                  groupValue: _usbMode,
                  onChanged: (v) {
                    if (v != null) setState(() => _usbMode = v);
                  },
                ),
                RadioListTile<bool>(
                  title: const Text(
                    'Pendrive / USB folder',
                    style: TextStyle(color: Colors.white),
                  ),
                  value: true,
                  groupValue: _usbMode,
                  onChanged: (v) {
                    if (v != null) setState(() => _usbMode = v);
                  },
                ),
                const SizedBox(height: 16),
                if (!_usbMode) ...[
                  TextField(
                    controller: _macController,
                    style: const TextStyle(color: Colors.white),
                    decoration: InputDecoration(
                      labelText: 'MAC Address',
                      hintText: 'e.g. 58:c5:87:67:7e:39',
                      labelStyle: const TextStyle(color: Colors.white70),
                      hintStyle: TextStyle(
                        color: Colors.white.withValues(alpha: 0.5),
                      ),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                      enabledBorder: const OutlineInputBorder(
                        borderSide: BorderSide(color: Colors.white38),
                      ),
                      focusedBorder: const OutlineInputBorder(
                        borderSide: BorderSide(color: Colors.white, width: 2),
                      ),
                    ),
                    textCapitalization: TextCapitalization.characters,
                    inputFormatters: [
                      FilteringTextInputFormatter.allow(
                        RegExp(r'[0-9A-Fa-f:.-]'),
                      ),
                    ],
                  ),
                ] else ...[
                  Text(
                    _usbPick.isEmpty
                        ? (Platform.isAndroid
                            ? 'Tap the button and select your pendrive in the system dialog.'
                            : 'Tap the button and select the folder on your pendrive (or a folder that contains your photos and videos).')
                        : _usbPick,
                    style: TextStyle(
                      color: _usbPick.isEmpty
                          ? Colors.orange.shade200
                          : Colors.white70,
                      fontSize: 14,
                    ),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    onPressed: () async {
                      if (Platform.isAndroid) {
                        final u = await UsbAndroidChannel.openDocumentTree();
                        if (u != null && u.isNotEmpty) {
                          setState(() => _usbPick = u);
                        }
                      } else {
                        final picked =
                            await FilePicker.platform.getDirectoryPath();
                        if (picked != null && picked.isNotEmpty) {
                          setState(() => _usbPick = picked);
                        }
                      }
                    },
                    icon: const Icon(Icons.folder_open, color: Colors.white70),
                    label: Text(
                      Platform.isAndroid
                          ? 'Choose USB / pendrive'
                          : 'Choose folder',
                      style: const TextStyle(color: Colors.white),
                    ),
                  ),
                ],
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _save,
                  style: FilledButton.styleFrom(
                    backgroundColor: Colors.green.shade700,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 32,
                      vertical: 16,
                    ),
                  ),
                  child: const Text('Save & Play'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
