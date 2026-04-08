// ignore_for_file: avoid_print, unused_local_variable
import 'package:dio/dio.dart';
import 'package:mockk_http/mockk_http.dart';

/// Example 1: Global HttpOverrides (intercepts ALL dart:io HTTP traffic)
///
/// Best for: apps using `package:http`, raw `HttpClient`, or mixed HTTP libraries.
/// Package name is auto-detected — zero config needed.
void exampleGlobalOverrides() {
  MockkHttp.init(); // That's it!
  // runApp(MyApp());
}

/// Example 2: Dio Interceptor (recommended for dio users)
///
/// Best for: apps using dio as their primary HTTP library.
void exampleDioInterceptor() {
  final dio = Dio();
  dio.interceptors.add(MockkHttpDioInterceptor()); // Zero config
}

/// Example 3: Conditional initialization (debug only)
///
/// Ensures MockkHttp is never active in release builds.
void exampleDebugOnly() {
  assert(() {
    MockkHttp.init();
    return true;
  }());
  // runApp(MyApp());
}

/// Example 4: Disable deduplication
///
/// Useful when debugging duplicate requests in your app.
void exampleDisableDedup() {
  MockkHttp.enableDeduplication = false;
  MockkHttp.init();
}
