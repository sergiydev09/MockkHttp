import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:test/test.dart';

import 'package:mockk_http/src/mockk_http_client.dart';

/// A stand-in for the plugin: accepts on 127.0.0.1 and answers PONG.
Future<ServerSocket> _pongServer({bool replyPong = true}) async {
  final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((socket) async {
    if (!replyPong) {
      // Accept and close — exactly what a stale `adb reverse` tunnel does.
      await socket.close();
      return;
    }
    socket.add(utf8.encode('PONG\n'));
    await socket.flush();
    await socket.close();
  });
  return server;
}

void main() {
  group('host discovery', () {
    test('pins 127.0.0.1 when the plugin answers there', () async {
      final server = await _pongServer();
      addTearDown(server.close);

      final client = MockkHttpPluginClient(port: server.port);
      expect(client.hostConfirmed, isFalse,
          reason: 'nothing has been proven before the first ping');

      expect(await client.isPluginConnected(), isTrue);
      expect(client.host, '127.0.0.1');
      expect(client.hostConfirmed, isTrue);
    });

    test('an accept-without-PONG is NOT treated as connected', () async {
      // A stale adb reverse tunnel accepts the connection and closes it. Requiring a
      // literal PONG is what stops the client from believing a dead tunnel is the plugin.
      final server = await _pongServer(replyPong: false);
      addTearDown(server.close);

      final client = MockkHttpPluginClient(port: server.port);
      expect(await client.isPluginConnected(), isFalse);
      expect(client.hostConfirmed, isFalse);
    });

    test('reports not-connected when nothing is listening', () async {
      // Bind then release, so the port is almost certainly free.
      final probe = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
      final deadPort = probe.port;
      await probe.close();

      final client = MockkHttpPluginClient(port: deadPort);
      expect(await client.isPluginConnected(), isFalse);
      expect(client.hostConfirmed, isFalse);
    });

    test('an explicit host is taken as final and never replaced', () async {
      final server = await _pongServer();
      addTearDown(server.close);

      final client =
          MockkHttpPluginClient(port: server.port, host: '127.0.0.1');
      expect(client.host, '127.0.0.1');
      expect(await client.isPluginConnected(), isTrue);
      expect(client.host, '127.0.0.1');
    });

    test('the default candidate list is loopback-first on every platform', () {
      // The regression that broke physical Android: 10.0.2.2 is emulator-only, so it must
      // never be the only candidate, and must never be tried before loopback.
      expect(MockkHttpPluginClient.resolveDefaultHost(), '127.0.0.1');
    });
  });
}
