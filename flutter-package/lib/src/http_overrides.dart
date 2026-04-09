import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'mockk_http_core.dart';
import 'models.dart';

/// Global [HttpOverrides] that intercepts all `dart:io` HTTP traffic.
///
/// This captures requests from `HttpClient`, `package:http`, and any
/// other library that uses `dart:io` internally.
///
/// Install via [MockkHttp.init()] or manually:
/// ```dart
/// HttpOverrides.global = MockkHttpOverrides(core);
/// ```
class MockkHttpOverrides extends HttpOverrides {
  final MockkHttpCore core;
  final HttpOverrides? _previous;

  MockkHttpOverrides(this.core) : _previous = HttpOverrides.current;

  /// Install these overrides globally, preserving any existing overrides.
  static void install(MockkHttpCore core) {
    HttpOverrides.global = MockkHttpOverrides(core);
  }

  @override
  HttpClient createHttpClient(SecurityContext? context) {
    final previous = _previous;
    final innerClient = previous != null
        ? previous.createHttpClient(context)
        : super.createHttpClient(context);

    return _MockkHttpClient(innerClient, core);
  }
}

/// Wrapper around [HttpClient] that intercepts requests.
class _MockkHttpClient implements HttpClient {
  final HttpClient _inner;
  final MockkHttpCore _core;

  _MockkHttpClient(this._inner, this._core);

  @override
  Future<HttpClientRequest> openUrl(String method, Uri url) async {
    final request = await _inner.openUrl(method, url);
    return _MockkHttpClientRequest(request, _core);
  }

  @override
  Future<HttpClientRequest> open(
      String method, String host, int port, String path) {
    return openUrl(method, Uri(scheme: 'http', host: host, port: port, path: path));
  }

  @override
  Future<HttpClientRequest> getUrl(Uri url) => openUrl('GET', url);

  @override
  Future<HttpClientRequest> get(String host, int port, String path) =>
      open('GET', host, port, path);

  @override
  Future<HttpClientRequest> postUrl(Uri url) => openUrl('POST', url);

  @override
  Future<HttpClientRequest> post(String host, int port, String path) =>
      open('POST', host, port, path);

  @override
  Future<HttpClientRequest> putUrl(Uri url) => openUrl('PUT', url);

  @override
  Future<HttpClientRequest> put(String host, int port, String path) =>
      open('PUT', host, port, path);

  @override
  Future<HttpClientRequest> deleteUrl(Uri url) => openUrl('DELETE', url);

  @override
  Future<HttpClientRequest> delete(String host, int port, String path) =>
      open('DELETE', host, port, path);

  @override
  Future<HttpClientRequest> patchUrl(Uri url) => openUrl('PATCH', url);

  @override
  Future<HttpClientRequest> patch(String host, int port, String path) =>
      open('PATCH', host, port, path);

  @override
  Future<HttpClientRequest> headUrl(Uri url) => openUrl('HEAD', url);

  @override
  Future<HttpClientRequest> head(String host, int port, String path) =>
      open('HEAD', host, port, path);

  // --- Delegate all other properties/methods to _inner ---

  @override
  bool get autoUncompress => _inner.autoUncompress;
  @override
  set autoUncompress(bool value) => _inner.autoUncompress = value;

  @override
  Duration? get connectionTimeout => _inner.connectionTimeout;
  @override
  set connectionTimeout(Duration? value) => _inner.connectionTimeout = value;

  @override
  Duration get idleTimeout => _inner.idleTimeout;
  @override
  set idleTimeout(Duration value) => _inner.idleTimeout = value;

  @override
  int? get maxConnectionsPerHost => _inner.maxConnectionsPerHost;
  @override
  set maxConnectionsPerHost(int? value) =>
      _inner.maxConnectionsPerHost = value;

  @override
  String? get userAgent => _inner.userAgent;
  @override
  set userAgent(String? value) => _inner.userAgent = value;

  @override
  void addCredentials(
          Uri url, String realm, HttpClientCredentials credentials) =>
      _inner.addCredentials(url, realm, credentials);

  @override
  void addProxyCredentials(String host, int port, String realm,
          HttpClientCredentials credentials) =>
      _inner.addProxyCredentials(host, port, realm, credentials);

  @override
  set authenticate(
          Future<bool> Function(Uri url, String scheme, String? realm)? f) =>
      _inner.authenticate = f;

  @override
  set authenticateProxy(
          Future<bool> Function(
                  String host, int port, String scheme, String? realm)?
              f) =>
      _inner.authenticateProxy = f;

  @override
  set badCertificateCallback(
          bool Function(X509Certificate cert, String host, int port)?
              callback) =>
      _inner.badCertificateCallback = callback;

  @override
  set connectionFactory(
          Future<ConnectionTask<Socket>> Function(
                  Uri url, String? proxyHost, int? proxyPort)?
              f) =>
      _inner.connectionFactory = f;

  @override
  set findProxy(String Function(Uri url)? f) => _inner.findProxy = f;

  @override
  set keyLog(Function(String line)? callback) => _inner.keyLog = callback;

  @override
  void close({bool force = false}) => _inner.close(force: force);
}

/// Wrapper around [HttpClientRequest] that captures the response.
class _MockkHttpClientRequest implements HttpClientRequest {
  final HttpClientRequest _inner;
  final MockkHttpCore _core;

  _MockkHttpClientRequest(this._inner, this._core);

  @override
  Future<HttpClientResponse> close() async {
    final method = _inner.method;
    final uri = _inner.uri;

    // Skip if disabled or duplicate
    if (!MockkHttpCore.isEnabled ||
        _core.isDuplicateRequest(method, uri)) {
      return _inner.close();
    }

    // Check plugin connectivity — if not connected, pass through cleanly
    final connected = await _core.client.isPluginConnected();
    if (!connected) {
      _core.markRequestCompleted(method, uri);
      return _inner.close();
    }

    // Build request data for plugin
    final requestHeaders = <String, String>{};
    _inner.headers.forEach((name, values) {
      requestHeaders[name] = values.join(', ');
    });
    final requestData = _core.buildRequestData(
      method,
      uri,
      requestHeaders,
    );

    // Check for mock
    final mockCheck = await _core.client.checkForMock(
      requestData,
      packageName: _core.packageName,
      projectId: _core.projectId,
    );
    final pluginMode = mockCheck?.mode ?? 'RECORDING';

    final startTime = DateTime.now().millisecondsSinceEpoch;

    switch (pluginMode) {
      case 'MOCKK':
        if (mockCheck?.hasMock == true) {
          _core.markRequestCompleted(method, uri);
          return _MockkHttpClientResponse.fromMock(mockCheck!);
        }
        // No mock — proceed with real request, no body capture needed
        final response = await _inner.close();
        _core.markRequestCompleted(method, uri);
        return response;

      case 'DEBUG':
      case 'MOCKK_DEBUG':
        _BufferedResponse buffered;
        if (mockCheck?.hasMock == true) {
          buffered = _BufferedResponse.fromMock(mockCheck!);
        } else {
          buffered = await _bufferResponse(await _inner.close());
        }
        final duration = DateTime.now().millisecondsSinceEpoch - startTime;

        final flow = _core.buildFlowData(
          request: requestData,
          statusCode: buffered.statusCode,
          responseHeaders: buffered.headers,
          responseBody: buffered.bodyString,
          durationMs: duration,
        );

        // Send and WAIT for user modification
        final modified = await _core.client.sendFlowAndWait(flow);
        _core.markRequestCompleted(method, uri);

        if (modified != null && modified.hasModifications) {
          return _MockkHttpClientResponse(
            statusCode: modified.statusCode ?? buffered.statusCode,
            bodyBytes: modified.body != null
                ? Uint8List.fromList(utf8.encode(modified.body!))
                : buffered.bodyBytes,
            headers: modified.headers ?? buffered.headers,
            originalResponse: buffered.original,
          );
        }
        return _MockkHttpClientResponse(
          statusCode: buffered.statusCode,
          bodyBytes: buffered.bodyBytes,
          headers: buffered.headers,
          originalResponse: buffered.original,
        );

      case 'RECORDING':
      default:
        // Buffer the response so we can read it AND return it
        final buffered = await _bufferResponse(await _inner.close());
        final duration = DateTime.now().millisecondsSinceEpoch - startTime;

        final flow = _core.buildFlowData(
          request: requestData,
          statusCode: buffered.statusCode,
          responseHeaders: buffered.headers,
          responseBody: buffered.bodyString,
          durationMs: duration,
        );

        // Send async — don't block the request
        _core.client.sendFlowAsync(flow);
        _core.markRequestCompleted(method, uri);

        // Return a NEW response with the buffered bytes
        return _MockkHttpClientResponse(
          statusCode: buffered.statusCode,
          bodyBytes: buffered.bodyBytes,
          headers: buffered.headers,
          originalResponse: buffered.original,
        );
    }
  }

  /// Buffer the response stream so it can be read multiple times.
  /// This is critical — HttpClientResponse is a single-listen stream.
  Future<_BufferedResponse> _bufferResponse(HttpClientResponse response) async {
    final chunks = await response.toList();
    final allBytes = Uint8List.fromList(chunks.expand((b) => b).toList());

    final headers = <String, String>{};
    response.headers.forEach((name, values) {
      headers[name] = values.join(', ');
    });

    return _BufferedResponse(
      statusCode: response.statusCode,
      bodyBytes: allBytes,
      headers: headers,
      original: response,
    );
  }

  // --- Delegate all other methods to _inner ---

  @override
  Encoding get encoding => _inner.encoding;
  @override
  set encoding(Encoding value) => _inner.encoding = value;

  @override
  void abort([Object? exception, StackTrace? stackTrace]) =>
      _inner.abort(exception, stackTrace);

  @override
  void add(List<int> data) => _inner.add(data);

  @override
  void addError(Object error, [StackTrace? stackTrace]) =>
      _inner.addError(error, stackTrace);

  @override
  Future addStream(Stream<List<int>> stream) => _inner.addStream(stream);

  @override
  HttpConnectionInfo? get connectionInfo => _inner.connectionInfo;

  @override
  List<Cookie> get cookies => _inner.cookies;

  @override
  Future<HttpClientResponse> get done => _inner.done;

  @override
  Future flush() => _inner.flush();

  @override
  HttpHeaders get headers => _inner.headers;

  @override
  String get method => _inner.method;

  @override
  Uri get uri => _inner.uri;

  @override
  void write(Object? object) => _inner.write(object);

  @override
  void writeAll(Iterable objects, [String separator = '']) =>
      _inner.writeAll(objects, separator);

  @override
  void writeCharCode(int charCode) => _inner.writeCharCode(charCode);

  @override
  void writeln([Object? object = '']) => _inner.writeln(object);

  @override
  bool get bufferOutput => _inner.bufferOutput;
  @override
  set bufferOutput(bool value) => _inner.bufferOutput = value;

  @override
  int get contentLength => _inner.contentLength;
  @override
  set contentLength(int value) => _inner.contentLength = value;

  @override
  bool get followRedirects => _inner.followRedirects;
  @override
  set followRedirects(bool value) => _inner.followRedirects = value;

  @override
  int get maxRedirects => _inner.maxRedirects;
  @override
  set maxRedirects(int value) => _inner.maxRedirects = value;

  @override
  bool get persistentConnection => _inner.persistentConnection;
  @override
  set persistentConnection(bool value) => _inner.persistentConnection = value;
}

/// Holds a buffered copy of the response bytes + metadata.
class _BufferedResponse {
  final int statusCode;
  final Uint8List bodyBytes;
  final Map<String, String> headers;
  final HttpClientResponse? original;

  _BufferedResponse({
    required this.statusCode,
    required this.bodyBytes,
    required this.headers,
    this.original,
  });

  String get bodyString {
    if (bodyBytes.length > 5 * 1024 * 1024) return '<body too large>';
    try {
      return utf8.decode(bodyBytes, allowMalformed: true);
    } catch (_) {
      return '<binary ${bodyBytes.length} bytes>';
    }
  }

  factory _BufferedResponse.fromMock(MockCheckResponse mock) {
    return _BufferedResponse(
      statusCode: mock.statusCode ?? 200,
      bodyBytes: Uint8List.fromList(utf8.encode(mock.body ?? '')),
      headers: mock.headers ?? {},
    );
  }
}

/// [HttpClientResponse] backed by buffered bytes — can be listened to
/// by downstream consumers (package:http, etc.) without "already listened" errors.
class _MockkHttpClientResponse extends Stream<List<int>>
    implements HttpClientResponse {
  @override
  final int statusCode;
  final Uint8List _bodyBytes;
  final Map<String, String> _headers;
  final HttpClientResponse? _original;

  _MockkHttpClientResponse({
    required this.statusCode,
    required Uint8List bodyBytes,
    required Map<String, String> headers,
    HttpClientResponse? originalResponse,
  })  : _bodyBytes = bodyBytes,
        _headers = headers,
        _original = originalResponse;

  factory _MockkHttpClientResponse.fromMock(MockCheckResponse mock) {
    return _MockkHttpClientResponse(
      statusCode: mock.statusCode ?? 200,
      bodyBytes: Uint8List.fromList(utf8.encode(mock.body ?? '')),
      headers: mock.headers ?? {},
    );
  }

  @override
  StreamSubscription<List<int>> listen(
    void Function(List<int> event)? onData, {
    Function? onError,
    void Function()? onDone,
    bool? cancelOnError,
  }) {
    // Return a fresh stream from the buffered bytes — safe to listen multiple times
    return Stream.value(_bodyBytes)
        .listen(onData, onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }

  @override
  int get contentLength => _bodyBytes.length;

  @override
  HttpHeaders get headers => _SyntheticHttpHeaders(_headers);

  @override
  bool get isRedirect => statusCode >= 300 && statusCode < 400;

  @override
  bool get persistentConnection => _original?.persistentConnection ?? false;

  @override
  String get reasonPhrase => _httpReasonPhrase(statusCode);

  @override
  List<Cookie> get cookies => _original?.cookies ?? [];

  @override
  HttpClientResponseCompressionState get compressionState =>
      _original?.compressionState ??
      HttpClientResponseCompressionState.notCompressed;

  @override
  HttpConnectionInfo? get connectionInfo => _original?.connectionInfo;

  @override
  X509Certificate? get certificate => _original?.certificate;

  @override
  List<RedirectInfo> get redirects => _original?.redirects ?? [];

  @override
  Future<Socket> detachSocket() =>
      throw UnsupportedError('Cannot detach socket from buffered response');

  @override
  Future<HttpClientResponse> redirect(
          [String? method, Uri? url, bool? followLoops]) =>
      throw UnsupportedError('Cannot redirect buffered response');

  static String _httpReasonPhrase(int code) => switch (code) {
        200 => 'OK',
        201 => 'Created',
        204 => 'No Content',
        400 => 'Bad Request',
        401 => 'Unauthorized',
        403 => 'Forbidden',
        404 => 'Not Found',
        500 => 'Internal Server Error',
        502 => 'Bad Gateway',
        503 => 'Service Unavailable',
        _ => 'Unknown',
      };
}

/// Minimal [HttpHeaders] implementation for synthetic responses.
class _SyntheticHttpHeaders implements HttpHeaders {
  final Map<String, String> _headers;

  _SyntheticHttpHeaders(this._headers);

  @override
  List<String>? operator [](String name) {
    final v = _headers[name.toLowerCase()] ?? _headers[name];
    return v != null ? [v] : null;
  }

  @override
  String? value(String name) =>
      _headers[name.toLowerCase()] ?? _headers[name];

  @override
  void forEach(void Function(String name, List<String> values) action) {
    _headers.forEach((k, v) => action(k, [v]));
  }

  @override
  void add(String name, Object value, {bool preserveHeaderCase = false}) {}
  @override
  void set(String name, Object value, {bool preserveHeaderCase = false}) {}
  @override
  void remove(String name, Object value) {}
  @override
  void removeAll(String name) {}
  @override
  void clear() {}
  @override
  void noFolding(String name) {}

  @override
  bool get chunkedTransferEncoding => false;
  @override
  set chunkedTransferEncoding(bool value) {}

  @override
  int get contentLength => -1;
  @override
  set contentLength(int value) {}

  @override
  ContentType? get contentType {
    final ct = value('content-type');
    return ct != null ? ContentType.parse(ct) : null;
  }

  @override
  set contentType(ContentType? value) {}

  @override
  DateTime? get date => null;
  @override
  set date(DateTime? value) {}

  @override
  DateTime? get expires => null;
  @override
  set expires(DateTime? value) {}

  @override
  String? get host => null;
  @override
  set host(String? value) {}

  @override
  DateTime? get ifModifiedSince => null;
  @override
  set ifModifiedSince(DateTime? value) {}

  @override
  bool get persistentConnection => false;
  @override
  set persistentConnection(bool value) {}

  @override
  int? get port => null;
  @override
  set port(int? value) {}
}
