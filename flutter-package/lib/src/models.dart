/// Data models matching the MockkHttp IntelliJ plugin protocol.
///
/// These models are wire-compatible with the Android library's Models.kt.
/// Field names and JSON keys must match exactly.
library;

/// HTTP request data sent to the plugin.
class RequestData {
  final String method;
  final String url;
  final Map<String, String> headers;
  final String body;

  const RequestData({
    required this.method,
    required this.url,
    required this.headers,
    this.body = '',
  });

  Map<String, dynamic> toJson() => {
        'method': method,
        'url': url,
        'headers': headers,
        'body': body,
      };

  factory RequestData.fromJson(Map<String, dynamic> json) => RequestData(
        method: json['method'] as String? ?? '',
        url: json['url'] as String? ?? '',
        headers: Map<String, String>.from(json['headers'] as Map? ?? {}),
        body: json['body'] as String? ?? '',
      );
}

/// HTTP response data sent to the plugin.
class ResponseData {
  final int statusCode;
  final Map<String, String> headers;
  final String body;

  const ResponseData({
    required this.statusCode,
    required this.headers,
    this.body = '',
  });

  Map<String, dynamic> toJson() => {
        'statusCode': statusCode,
        'headers': headers,
        'body': body,
      };

  factory ResponseData.fromJson(Map<String, dynamic> json) => ResponseData(
        statusCode: json['statusCode'] as int? ?? 0,
        headers: Map<String, String>.from(json['headers'] as Map? ?? {}),
        body: json['body'] as String? ?? '',
      );
}

/// Complete flow data (request + response) sent to the plugin.
class FlowData {
  final String type;
  final String flowId;
  final RequestData request;
  final ResponseData response;
  final int timestamp;
  final int duration;
  final String? projectId;
  final String? packageName;

  const FlowData({
    this.type = 'FLOW',
    required this.flowId,
    required this.request,
    required this.response,
    required this.timestamp,
    required this.duration,
    this.projectId,
    this.packageName,
  });

  Map<String, dynamic> toJson() => {
        'type': type,
        'flowId': flowId,
        'request': request.toJson(),
        'response': response.toJson(),
        'timestamp': timestamp,
        'duration': duration,
        'projectId': projectId,
        'packageName': packageName,
      };
}

/// Mock check request - sent BEFORE the real HTTP call to see if a mock exists.
class MockCheckRequest {
  final String type;
  final RequestData request;
  final String? projectId;
  final String? packageName;

  const MockCheckRequest({
    this.type = 'CHECK_MOCK',
    required this.request,
    this.projectId,
    this.packageName,
  });

  Map<String, dynamic> toJson() => {
        'type': type,
        'request': request.toJson(),
        'projectId': projectId,
        'packageName': packageName,
      };
}

/// Mock check response from the plugin.
class MockCheckResponse {
  final bool hasMock;
  final String? mode;
  final int? statusCode;
  final Map<String, String>? headers;
  final String? body;
  final String? mockRuleName;

  const MockCheckResponse({
    required this.hasMock,
    this.mode,
    this.statusCode,
    this.headers,
    this.body,
    this.mockRuleName,
  });

  factory MockCheckResponse.fromJson(Map<String, dynamic> json) =>
      MockCheckResponse(
        hasMock: json['hasMock'] as bool? ?? false,
        mode: json['mode'] as String?,
        statusCode: json['statusCode'] as int?,
        headers: json['headers'] != null
            ? Map<String, String>.from(json['headers'] as Map)
            : null,
        body: json['body'] as String?,
        mockRuleName: json['mockRuleName'] as String?,
      );
}

/// Modified response data received from the plugin (in Debug mode).
class ModifiedResponseData {
  final int? statusCode;
  final Map<String, String>? headers;
  final String? body;

  const ModifiedResponseData({
    this.statusCode,
    this.headers,
    this.body,
  });

  /// No modifications — use original response.
  const ModifiedResponseData.original()
      : statusCode = null,
        headers = null,
        body = null;

  bool get hasModifications =>
      statusCode != null || headers != null || body != null;

  factory ModifiedResponseData.fromJson(Map<String, dynamic> json) =>
      ModifiedResponseData(
        statusCode: json['statusCode'] as int?,
        headers: json['headers'] != null
            ? Map<String, String>.from(json['headers'] as Map)
            : null,
        body: json['body'] as String?,
      );
}
