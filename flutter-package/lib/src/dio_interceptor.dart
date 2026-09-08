import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'mockk_http_client.dart';
import 'http_overrides.dart';
import 'mockk_http_core.dart';
import 'models.dart';

/// Dio interceptor that captures HTTP traffic and sends it to the
/// MockkHttp IntelliJ plugin.
///
/// ```dart
/// final dio = Dio();
/// dio.interceptors.add(MockkHttpDioInterceptor());
/// ```
///
/// This is the recommended approach for apps using dio.
/// For apps using `package:http` or raw `HttpClient`, use [MockkHttp.init()].
class MockkHttpDioInterceptor extends Interceptor {
  final MockkHttpCore _core;

  /// Create a MockkHttp dio interceptor.
  ///
  /// Package/bundle id is auto-detected on Android and iOS — no need to pass it.
  ///
  /// [port] - Plugin port (default: 9876)
  /// [packageName] - Override auto-detected package/bundle id (rarely needed)
  /// [host] - Override the plugin host. Not needed on emulators/simulators;
  ///   for a physical iOS device pass your Mac's LAN IP (e.g. '192.168.1.50').
  /// [dio] is the instance this interceptor is added to. With it, the interceptor wraps the
  /// Dio's HTTP adapter — the one point where a request leaves dio for the network — and tells
  /// the global [MockkHttpOverrides] underneath, if there is one, to step aside for a request
  /// this layer already captured, without touching the request the app wrote. Whatever the
  /// adapter is (dart:io, a third-party wrapper around it, a native one) is not its business:
  /// a claim nobody underneath takes is simply withdrawn when the request ends. The wrapper is
  /// put back on every request, so replacing the adapter after this call — pinning, a proxy,
  /// timeouts — costs nothing. Without [dio], the interceptor stands back whenever the global
  /// override is active, so nothing is reported twice; pass it for the coordinated setup, and
  /// always with an adapter that bypasses dart:io, where this layer is the only one that sees
  /// the request.
  MockkHttpDioInterceptor({
    int port = 9876,
    String? packageName,
    String? projectId,
    String? host,
    MockkHttpPluginClient? client,
    Dio? dio,
  })  : _core = MockkHttpCore(
          client: (client ?? MockkHttpPluginClient(port: port, host: host))
            ..setPackageName(packageName ?? MockkHttp.autoDetectPackageName()),
          packageName: packageName ?? MockkHttp.autoDetectPackageName(),
          projectId: projectId,
        ),
        _dio = dio {
    _ensureAdapterWrapped();
  }

  /// The Dio this interceptor coordinates for, or null when it was not given one.
  final Dio? _dio;

  /// Puts the adapter wrapper in place, or back in place: an app configures its adapter
  /// (certificate pinning, a proxy, timeouts) whenever it likes, and that replaces whatever
  /// was there — including our wrapper. Checked on every request, so it cannot drift.
  void _ensureAdapterWrapped() {
    final dio = _dio;
    if (dio == null) return;
    final current = dio.httpClientAdapter;
    if (current is! _MockkHttpAdapter) {
      dio.httpClientAdapter = _MockkHttpAdapter(current);
      if (_wrappedOnce) {
        // Not the first time: the app replaced the adapter after this interceptor wrapped it. A
        // fact of configuration, counted here — for the report, and for THIS Dio alone, because
        // only a replacement on this Dio can send one of its requests down without the wrapper.
        // Whether any request lost its claim over it is known only when that request ends, and
        // is said there (audit round 10, AN; round 11, AP).
        MockkHttpCore.noteWrapperReplaced();
        _replacedHere++;
      }
    }
    // Wrapped state is established the first time a wrapper is SEEN here — ours or one this Dio
    // was handed from another Dio (a shared adapter). Marking it only when this interceptor did
    // the wrapping let the first real swap on such a Dio go uncounted, and a genuine duplicate
    // was then called "nobody saw it" (adversarial review of audit round 12).
    _wrappedOnce = true;
  }

  bool _wrappedOnce = false;

  /// Replacements of this Dio's adapter found by this interceptor; stamped on every claim.
  int _replacedHere = 0;

  static bool _stoodBackOnce = false;

  @override
  void onRequest(
      RequestOptions options, RequestInterceptorHandler handler) async {
    if (!MockkHttpCore.isEnabled) {
      handler.next(options);
      return;
    }

    final uri = options.uri;
    final method = options.method;

    // Another MockkHttpDioInterceptor earlier in this same chain already owns this request
    // (the interceptor added twice — a cloned Dio, or init code that ran two times). The marker
    // it left is the request's identity; no clock is involved.
    if (options.extra.containsKey('_mockk_request_data')) {
      handler.next(options);
      return;
    }

    _ensureAdapterWrapped();
    final overrideActive = HttpOverrides.current is MockkHttpOverrides;
    if (overrideActive && _dio == null && MockkHttpCore.enableDeduplication) {
      // The global override underneath captures everything that reaches dart:io — with dio's
      // default adapter, this request — and nobody told this interceptor which adapter its Dio
      // uses, so nothing below can be asked to step aside. Standing back is what keeps one
      // request from being reported twice; the override serves mocks and Debug edits just the
      // same. Pass dio: to MockkHttpDioInterceptor for the coordinated setup.
      MockkHttpCore.noteStoodBack();
      _noteStandingBack();
      handler.next(options);
      return;
    }

    // Check plugin connectivity
    final connected = await _core.client.isPluginConnected();
    if (!connected) {
      _letThrough(options, handler, overrideActive);
      return;
    }

    // Build request data
    final requestHeaders = <String, String>{};
    options.headers.forEach((key, value) {
      requestHeaders[key] = value.toString();
    });

    // Ask the plugin what it wants BEFORE serialising the request body: the mock
    // check is matched on method + URL only, so encoding the payload first is pure
    // waste whenever the answer turns out to be IDLE.
    final mockCheck = await _core.client.checkForMock(
      _core.buildRequestData(method, uri, requestHeaders),
      packageName: _core.packageName,
      projectId: _core.projectId,
    );
    final pluginMode = MockkHttpCore.modeOf(mockCheck);

    if (pluginMode == MockkHttpCore.modeIdle) {
      // Nobody is capturing. Leave options.extra untouched so onResponse skips too:
      // no response serialisation, no flow, no second socket.
      _letThrough(options, handler, overrideActive);
      return;
    }

    final requestData = _core.buildRequestData(
      method,
      uri,
      requestHeaders,
      body: _serializeRequestBody(options.data),
    );

    // Store request data and timing in options.extra for onResponse. The owner marker is
    // what keeps a second copy of this interceptor on the same Dio from reporting the same
    // response again: only the instance that captured the request reports its outcome.
    options.extra['_mockk_request_data'] = requestData;
    options.extra['_mockk_owner'] = identityHashCode(this);
    options.extra['_mockk_start_time'] = DateTime.now().millisecondsSinceEpoch;
    options.extra['_mockk_mode'] = pluginMode;

    // In MOCKK mode with a mock available, return mock directly
    if (pluginMode == MockkHttpCore.modeMockk && mockCheck?.hasMock == true) {
      final mock = mockCheck!;
      // Send flow async so the mocked call appears in Inspector
      final duration = DateTime.now().millisecondsSinceEpoch -
          (options.extra['_mockk_start_time'] as int);
      final flow = _core.buildFlowData(
        source: 'dio',
        request: requestData,
        statusCode: mock.statusCode ?? 200,
        responseHeaders: mock.headers ?? {},
        responseBody: mock.body ?? '',
        durationMs: duration,
      );
      _core.client.sendFlowAsync(flow);

      // Reported. resolve(..., true) still runs the response interceptors — including this
      // one's onResponse, which used to report the very same request a second time.
      options.extra.remove('_mockk_request_data');
      handler.resolve(
        _buildMockResponse(options, mock),
        true,
      );
      return;
    }

    // In MOCKK_DEBUG with mock available, we still need the dialog
    // Store the mock data for use in onResponse
    if ((pluginMode == MockkHttpCore.modeMockkDebug ||
            pluginMode == MockkHttpCore.modeDebug) &&
        mockCheck?.hasMock == true) {
      options.extra['_mockk_mock_data'] = mockCheck;
    }

    _letThrough(options, handler, overrideActive);
  }

  /// The request goes down. If the global [MockkHttpOverrides] is active, the same request may
  /// go past it on its way to dart:io and must be let through: the claim, carried down by the
  /// adapter wrapper in a zone, is what tells it which request that is. Nothing about the
  /// request itself changes. The claim is made HERE, when the request is known to go down — a
  /// request the mock answers never does, and a claim for it counted as a pass nobody could take
  /// (audit round 10, AM). Whether the request really reaches dart:io (a native adapter never
  /// does) is nobody's guess to make: a claim nothing underneath takes is withdrawn when the
  /// request ends, and cost nothing meanwhile.
  void _letThrough(RequestOptions options, RequestInterceptorHandler handler,
      bool overrideActive) {
    if (overrideActive && _dio != null) {
      final claim = _core.claimInnerPass(options.method, options.uri);
      if (claim != null) {
        claim.adapterReplacementsWhenMade = _replacedHere;
        options.extra['_mockk_claim'] = claim;
      }
    }
    handler.next(options);
  }

  @override
  void onResponse(Response response, ResponseInterceptorHandler handler) async {
    _requestOver(response.requestOptions);
    final requestData =
        response.requestOptions.extra['_mockk_request_data'] as RequestData?;
    final startTime =
        response.requestOptions.extra['_mockk_start_time'] as int?;
    final pluginMode = response.requestOptions.extra['_mockk_mode'] as String?;

    // No marker means onRequest decided there was nothing to capture (disabled,
    // plugin unreachable, or IDLE): nothing to serialise here either. A marker left by
    // ANOTHER instance of this interceptor is that instance's to report, not ours.
    if (requestData == null ||
        startTime == null ||
        pluginMode == null ||
        response.requestOptions.extra['_mockk_owner'] !=
            identityHashCode(this)) {
      handler.next(response);
      return;
    }

    if (pluginMode == MockkHttpCore.modeIdle) {
      // Belt and braces: onRequest never stores IDLE, but if it ever did, capturing
      // here would be exactly the cost this mode exists to avoid.
      handler.next(response);
      return;
    }

    final duration = DateTime.now().millisecondsSinceEpoch - startTime;

    // Extract response data
    final responseHeaders = <String, String>{};
    response.headers.forEach((name, values) {
      responseHeaders[name] = values.join(', ');
    });
    final responseBody = _serializeResponseBody(response.data);

    switch (pluginMode) {
      case MockkHttpCore.modeDebug:
      case MockkHttpCore.modeMockkDebug:
        // Use mock data if available (for MOCKK_DEBUG with mock)
        final mockData = response.requestOptions.extra['_mockk_mock_data']
            as MockCheckResponse?;

        final effectiveStatusCode =
            mockData?.statusCode ?? response.statusCode ?? 200;
        final effectiveBody = mockData?.body ?? responseBody;
        final effectiveHeaders = mockData?.headers ?? responseHeaders;

        final flow = _core.buildFlowData(
          source: 'dio',
          request: requestData,
          statusCode: effectiveStatusCode,
          responseHeaders: effectiveHeaders,
          responseBody: effectiveBody,
          durationMs: duration,
        );

        // BLOCKING — wait for user in plugin dialog
        final modified = await _core.client.sendFlowAndWait(flow);

        if (modified != null && modified.hasModifications) {
          handler.resolve(_applyModifications(response, modified));
          return;
        }

        // If mock data was used but no dialog modifications
        if (mockData != null) {
          handler.resolve(_buildMockResponse(
            response.requestOptions,
            mockData,
          ));
          return;
        }

        handler.next(response);

      case MockkHttpCore.modeRecording:
      default:
        // An unknown mode lands here too: the plugin has told us it is NOT idle, so
        // capturing is the safe reading of a mode this version does not know.
        final flow = _core.buildFlowData(
          source: 'dio',
          request: requestData,
          statusCode: response.statusCode ?? 200,
          responseHeaders: responseHeaders,
          responseBody: responseBody,
          durationMs: duration,
        );

        // Async — don't block
        _core.client.sendFlowAsync(flow);
        handler.next(response);
    }
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    _requestOver(err.requestOptions);
    handler.next(err);
  }

  /// The request is over: a claim the inner layer never took is withdrawn, and the withdrawal
  /// is counted by what the claim itself recorded — carried down by the wrapper or not — rather
  /// than by the adapter's state now, which another request may have already put right. The
  /// wrapper is checked here too, so a replacement is noticed even with no further traffic; and
  /// only here, where the fact exists, is a lost claim said out loud.
  void _requestOver(RequestOptions options) {
    final claim = options.extra.remove('_mockk_claim') as InnerPassClaim?;
    _ensureAdapterWrapped();
    final replacedMeanwhile =
        claim != null && _replacedHere > claim.adapterReplacementsWhenMade;
    switch (_core.releaseInnerPass(claim,
        adapterReplacedMeanwhile: replacedMeanwhile)) {
      case ClaimEnd.notCarried:
        _noteNotCarried();
      case ClaimEnd.unresolved:
        _noteUnresolved();
      default:
        break;
    }
  }

  bool _unresolvedOnce = false;

  void _noteUnresolved() {
    if (_unresolvedOnce) return;
    _unresolvedOnce = true;
    assert(() {
      print(
          'MockkHttp: dio.httpClientAdapter was replaced while a request was in flight and the layer '
          'underneath saw nothing that matched it: the request was answered above the adapter, sent through '
          'an adapter that bypasses dart:io, or fetched under a URL the new adapter rewrote and reported twice '
          '— this package cannot tell which (client.stats: claims_unresolved, wrapper_replaced). Configure the '
          'adapter before sending traffic.');
      return true;
    }());
  }

  bool _notCarriedOnce = false;

  void _noteNotCarried() {
    if (_notCarriedOnce) return;
    _notCarriedOnce = true;
    assert(() {
      print(
          'MockkHttp: a request went down without its claim and the layer underneath reported it too: '
          'dio.httpClientAdapter was replaced while that request was in flight, so it could not be told to '
          'step aside (client.stats: claims_not_carried, wrapper_replaced). Configure the adapter before '
          'sending traffic.');
      return true;
    }());
  }

  void _noteStandingBack() {
    if (_stoodBackOnce) return;
    _stoodBackOnce = true;
    assert(() {
      print(
          'MockkHttp: the global HttpOverrides is active, so this MockkHttpDioInterceptor stands '
          'back and dart:io captures. Pass dio: to MockkHttpDioInterceptor(dio: dio) to coordinate '
          'the two layers — required with an adapter that bypasses dart:io.');
      return true;
    }());
  }

  /// Build a dio [Response] from mock data.
  Response _buildMockResponse(
    RequestOptions options,
    MockCheckResponse mock,
  ) {
    final body = mock.body ?? '';
    dynamic data;

    // Try to parse as JSON if content-type suggests it
    final contentType = mock.headers?['Content-Type'] ?? 'application/json';
    if (contentType.contains('json')) {
      try {
        data = jsonDecode(body);
      } catch (_) {
        data = body;
      }
    } else {
      data = body;
    }

    return Response(
      requestOptions: options,
      statusCode: mock.statusCode ?? 200,
      statusMessage: mock.mockRuleName ?? 'MockkHttp Mock',
      data: data,
      headers: Headers.fromMap(
        mock.headers?.map((k, v) => MapEntry(k, [v])) ?? {},
      ),
    );
  }

  /// Apply modifications from the plugin dialog to the response.
  Response _applyModifications(
    Response original,
    ModifiedResponseData modified,
  ) {
    dynamic data = original.data;
    if (modified.body != null) {
      try {
        data = jsonDecode(modified.body!);
      } catch (_) {
        data = modified.body;
      }
    }

    return Response(
      requestOptions: original.requestOptions,
      statusCode: modified.statusCode ?? original.statusCode ?? 200,
      statusMessage: original.statusMessage,
      data: data,
      headers: modified.headers != null
          ? Headers.fromMap(
              modified.headers!.map((k, v) => MapEntry(k, [v])),
            )
          : original.headers,
    );
  }

  String _serializeRequestBody(dynamic data) {
    if (data == null) return '';
    if (data is String) return data;
    if (data is Map || data is List) {
      try {
        return jsonEncode(data);
      } catch (_) {
        return data.toString();
      }
    }
    return data.toString();
  }

  String _serializeResponseBody(dynamic data) {
    if (data == null) return '';
    if (data is String) return data;
    if (data is Map || data is List) {
      try {
        return jsonEncode(data);
      } catch (_) {
        return data.toString();
      }
    }
    return data.toString();
  }
}

/// Wraps a Dio's adapter so a claim made by [MockkHttpDioInterceptor] reaches the global
/// [MockkHttpOverrides] underneath as a zone value: the real adapter runs inside a zone carrying
/// the claim, and the override's `openUrl` — reached from that zone, however many awaits later —
/// reads it back. The request goes down exactly as the app wrote it.
class _MockkHttpAdapter implements HttpClientAdapter {
  _MockkHttpAdapter(this._inner);

  final HttpClientAdapter _inner;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    final claim = options.extra['_mockk_claim'];
    if (claim is! InnerPassClaim) {
      return _inner.fetch(options, requestStream, cancelFuture);
    }
    claim.carried = true;
    return runZoned(
      () => _inner.fetch(options, requestStream, cancelFuture),
      zoneValues: {MockkHttpCore.zoneClaimKey: claim},
    );
  }

  @override
  void close({bool force = false}) => _inner.close(force: force);
}
