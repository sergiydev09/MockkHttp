import 'dart:convert';

import 'package:dio/dio.dart';

import 'mockk_http_client.dart';
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
  MockkHttpDioInterceptor({
    int port = 9876,
    String? packageName,
    String? projectId,
    String? host,
    MockkHttpPluginClient? client,
  }) : _core = MockkHttpCore(
          client: (client ?? MockkHttpPluginClient(port: port, host: host))
            ..setPackageName(packageName ?? MockkHttp.autoDetectPackageName()),
          packageName: packageName ?? MockkHttp.autoDetectPackageName(),
          projectId: projectId,
        );

  @override
  void onRequest(
      RequestOptions options, RequestInterceptorHandler handler) async {
    if (!MockkHttpCore.isEnabled) {
      handler.next(options);
      return;
    }

    final uri = options.uri;
    final method = options.method;

    // Deduplication check
    if (_core.isDuplicateRequest(method, uri)) {
      handler.next(options);
      return;
    }

    // Check plugin connectivity
    final connected = await _core.client.isPluginConnected();
    if (!connected) {
      _core.markRequestCompleted(method, uri);
      handler.next(options);
      return;
    }

    // Build request data
    final requestHeaders = <String, String>{};
    options.headers.forEach((key, value) {
      requestHeaders[key] = value.toString();
    });

    final requestBody = _serializeRequestBody(options.data);

    final requestData = _core.buildRequestData(
      method,
      uri,
      requestHeaders,
      body: requestBody,
    );

    // Check for mock
    final mockCheck = await _core.client.checkForMock(
      requestData,
      packageName: _core.packageName,
      projectId: _core.projectId,
    );
    final pluginMode = mockCheck?.mode ?? 'RECORDING';

    // Store request data and timing in options.extra for onResponse
    options.extra['_mockk_request_data'] = requestData;
    options.extra['_mockk_start_time'] =
        DateTime.now().millisecondsSinceEpoch;
    options.extra['_mockk_mode'] = pluginMode;

    // In MOCKK mode with a mock available, return mock directly
    if (pluginMode == 'MOCKK' && mockCheck?.hasMock == true) {
      final mock = mockCheck!;
      // Send flow async so the mocked call appears in Inspector
      final duration = DateTime.now().millisecondsSinceEpoch -
          (options.extra['_mockk_start_time'] as int);
      final flow = _core.buildFlowData(
        request: requestData,
        statusCode: mock.statusCode ?? 200,
        responseHeaders: mock.headers ?? {},
        responseBody: mock.body ?? '',
        durationMs: duration,
      );
      _core.client.sendFlowAsync(flow);

      _core.markRequestCompleted(method, uri);
      handler.resolve(
        _buildMockResponse(options, mock),
        true,
      );
      return;
    }

    // In MOCKK_DEBUG with mock available, we still need the dialog
    // Store the mock data for use in onResponse
    if ((pluginMode == 'MOCKK_DEBUG' || pluginMode == 'DEBUG') &&
        mockCheck?.hasMock == true) {
      options.extra['_mockk_mock_data'] = mockCheck;
    }

    handler.next(options);
  }

  @override
  void onResponse(Response response, ResponseInterceptorHandler handler) async {
    final requestData =
        response.requestOptions.extra['_mockk_request_data'] as RequestData?;
    final startTime =
        response.requestOptions.extra['_mockk_start_time'] as int?;
    final pluginMode =
        response.requestOptions.extra['_mockk_mode'] as String?;

    if (requestData == null || startTime == null || pluginMode == null) {
      handler.next(response);
      return;
    }

    final method = response.requestOptions.method;
    final uri = response.requestOptions.uri;
    final duration = DateTime.now().millisecondsSinceEpoch - startTime;

    // Extract response data
    final responseHeaders = <String, String>{};
    response.headers.forEach((name, values) {
      responseHeaders[name] = values.join(', ');
    });
    final responseBody = _serializeResponseBody(response.data);

    switch (pluginMode) {
      case 'DEBUG':
      case 'MOCKK_DEBUG':
        // Use mock data if available (for MOCKK_DEBUG with mock)
        final mockData = response.requestOptions.extra['_mockk_mock_data']
            as MockCheckResponse?;

        final effectiveStatusCode =
            mockData?.statusCode ?? response.statusCode ?? 200;
        final effectiveBody = mockData?.body ?? responseBody;
        final effectiveHeaders = mockData?.headers ?? responseHeaders;

        final flow = _core.buildFlowData(
          request: requestData,
          statusCode: effectiveStatusCode,
          responseHeaders: effectiveHeaders,
          responseBody: effectiveBody,
          durationMs: duration,
        );

        // BLOCKING — wait for user in plugin dialog
        final modified = await _core.client.sendFlowAndWait(flow);
        _core.markRequestCompleted(method, uri);

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

      case 'RECORDING':
      default:
        final flow = _core.buildFlowData(
          request: requestData,
          statusCode: response.statusCode ?? 200,
          responseHeaders: responseHeaders,
          responseBody: responseBody,
          durationMs: duration,
        );

        // Async — don't block
        _core.client.sendFlowAsync(flow);
        _core.markRequestCompleted(method, uri);
        handler.next(response);
    }
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    // Clean up deduplication tracking on error
    final method = err.requestOptions.method;
    final uri = err.requestOptions.uri;
    _core.markRequestCompleted(method, uri);
    handler.next(err);
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
