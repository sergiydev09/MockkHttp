import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// Reads the app's own bundle identifier on iOS WITHOUT plugins or env vars.
///
/// Why this exists: on recent iOS runtimes, `Platform.environment` is EMPTY
/// inside a Flutter app (no `__CFBundleIdentifier`, no `SIMULATOR_*`, no
/// `HOME`), so the only reliable pure-Dart source is the app's `Info.plist`,
/// which lives next to [Platform.resolvedExecutable] and is readable by the
/// app itself. Handles both XML and binary (bplist00) plists.
String? readIosBundleIdentifier() {
  try {
    final executable = File(Platform.resolvedExecutable);
    final plistFile = File('${executable.parent.path}/Info.plist');
    if (!plistFile.existsSync()) return null;
    return bundleIdFromPlistBytes(plistFile.readAsBytesSync());
  } catch (_) {
    return null;
  }
}

/// Extracts CFBundleIdentifier from raw plist bytes (XML or bplist00).
/// Exposed separately so it can be tested against real plist files.
String? bundleIdFromPlistBytes(Uint8List bytes) {
  if (bytes.length > 8 &&
      bytes[0] == 0x62 && // b
      bytes[1] == 0x70 && // p
      bytes[2] == 0x6C && // l
      bytes[3] == 0x69 && // i
      bytes[4] == 0x73 && // s
      bytes[5] == 0x74) { // t
    return _fromBinaryPlist(bytes);
  }
  return _fromXmlPlist(utf8.decode(bytes, allowMalformed: true));
}

String? _fromXmlPlist(String xml) {
  final match = RegExp(
    r'<key>\s*CFBundleIdentifier\s*</key>\s*<string>([^<]+)</string>',
  ).firstMatch(xml);
  return match?.group(1)?.trim();
}

/// Minimal bplist00 reader: walks the top-level dictionary looking for the
/// CFBundleIdentifier key and decodes its (ASCII or UTF-16BE) string value.
/// Format reference: Apple CoreFoundation CFBinaryPList.c.
String? _fromBinaryPlist(Uint8List bytes) {
  try {
    if (bytes.length < 40) return null; // header (8) + trailer (32)

    // Trailer: last 32 bytes
    final trailer = bytes.length - 32;
    final offsetIntSize = bytes[trailer + 6];
    final objectRefSize = bytes[trailer + 7];
    final numObjects = _readBE(bytes, trailer + 8, 8);
    final topObject = _readBE(bytes, trailer + 16, 8);
    final offsetTableStart = _readBE(bytes, trailer + 24, 8);

    if (offsetIntSize == 0 || objectRefSize == 0 || numObjects == 0) return null;

    int objectOffset(int ref) {
      if (ref >= numObjects) return -1;
      return _readBE(bytes, offsetTableStart + ref * offsetIntSize, offsetIntSize);
    }

    // Top object must be a dict (marker high nibble 0xD)
    final topOffset = objectOffset(topObject);
    if (topOffset < 0 || bytes[topOffset] >> 4 != 0xD) return null;

    final (count, refsStart) = _readLength(bytes, topOffset);
    for (var i = 0; i < count; i++) {
      final keyRef = _readBE(bytes, refsStart + i * objectRefSize, objectRefSize);
      final key = _readStringObject(bytes, objectOffset(keyRef));
      if (key == 'CFBundleIdentifier') {
        final valueRef = _readBE(
          bytes,
          refsStart + (count + i) * objectRefSize,
          objectRefSize,
        );
        return _readStringObject(bytes, objectOffset(valueRef));
      }
    }
    return null;
  } catch (_) {
    return null;
  }
}

/// Big-endian unsigned int of [size] bytes at [offset].
int _readBE(Uint8List bytes, int offset, int size) {
  var value = 0;
  for (var i = 0; i < size; i++) {
    value = (value << 8) | bytes[offset + i];
  }
  return value;
}

/// Reads an object's length from its marker byte at [offset]. The low nibble
/// holds the length, unless it's 0xF — then an int object (marker 0x1N,
/// 2^N bytes big-endian) with the real length follows.
/// Returns (length, offset of the object's payload).
(int, int) _readLength(Uint8List bytes, int offset) {
  final info = bytes[offset] & 0x0F;
  if (info != 0x0F) return (info, offset + 1);
  final intMarker = bytes[offset + 1];
  final intSize = 1 << (intMarker & 0x0F);
  return (_readBE(bytes, offset + 2, intSize), offset + 2 + intSize);
}

/// Decodes an ASCII (0x5) or UTF-16BE (0x6) string object at [offset].
String? _readStringObject(Uint8List bytes, int offset) {
  if (offset < 0) return null;
  final type = bytes[offset] >> 4;
  final (length, payload) = _readLength(bytes, offset);
  if (type == 0x5) {
    return String.fromCharCodes(bytes.sublist(payload, payload + length));
  }
  if (type == 0x6) {
    final codeUnits = List<int>.generate(
      length,
      (i) => _readBE(bytes, payload + i * 2, 2),
    );
    return String.fromCharCodes(codeUnits);
  }
  return null;
}
