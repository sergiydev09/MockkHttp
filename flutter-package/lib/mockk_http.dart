/// MockkHttp Flutter interceptor.
///
/// Captures HTTP traffic and sends it to the MockkHttp IntelliJ plugin
/// for recording, debugging, and mocking.
///
/// ## Quick Start
///
/// ### Option 1: Global HttpOverrides (intercepts ALL HTTP traffic)
/// ```dart
/// import 'package:mockk_http/mockk_http.dart';
///
/// void main() {
///   MockkHttp.init(); // Call before runApp()
///   runApp(MyApp());
/// }
/// ```
///
/// ### Option 2: Dio Interceptor (recommended for dio users)
/// ```dart
/// import 'package:mockk_http/mockk_http.dart';
///
/// final dio = Dio();
/// dio.interceptors.add(MockkHttpDioInterceptor());
/// ```
library;

export 'src/models.dart';
export 'src/mockk_http_client.dart';
export 'src/dio_interceptor.dart';
export 'src/http_overrides.dart';
export 'src/mockk_http_core.dart';
