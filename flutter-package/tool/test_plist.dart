// Standalone check: extract CFBundleIdentifier from a plist file.
// Usage: dart tool/test_plist.dart <path-to-Info.plist>
import 'dart:io';

import '../lib/src/ios_bundle_info.dart';

void main(List<String> args) {
  final bytes = File(args.first).readAsBytesSync();
  final id = bundleIdFromPlistBytes(bytes);
  print('CFBundleIdentifier: ${id ?? "NOT FOUND"}');
  exitCode = id == null ? 1 : 0;
}
