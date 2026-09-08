# MockkHttp

<div align="center">

![MockkHttp Logo](src/main/resources/META-INF/pluginIcon.svg)

**Network Interceptor Plugin for Android Studio & IntelliJ IDEA**

Intercept, debug and mock HTTP/HTTPS traffic from Android and Flutter apps (Android & iOS) in real-time — from the IDE, or from an AI agent through the built-in MCP server

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-IntelliJ-orange.svg)](https://www.jetbrains.com/idea/)
[![Version](https://img.shields.io/badge/version-1.8.0-green.svg)]()
[![Gradle Plugin](https://img.shields.io/badge/Gradle%20Plugin-io.github.sergiydev09.mockkhttp-blue)](https://plugins.gradle.org/plugin/io.github.sergiydev09.mockkhttp)
[![pub.dev](https://img.shields.io/pub/v/mockk_http.svg?label=mockk_http)](https://pub.dev/packages/mockk_http)

</div>

---

## 🎯 Features

- **🔴 Recording Mode**: Captures all HTTP/HTTPS requests (including request bodies) without pausing execution
- **🐛 Debug Mode**: Pauses requests in real-time and allows editing responses before delivering them to the app
- **📋 Mockk Mode**: Applies mock rules automatically based on request patterns
- **🤖 Android**: Any OkHttp/Retrofit app — interceptor auto-injected via Gradle plugin (emulators & physical devices)
- **🐦 Flutter**: The [`mockk_http`](https://pub.dev/packages/mockk_http) package intercepts `HttpClient`, `package:http` and `dio` — on Android **and iOS**
- **🍎 iOS Simulators**: Flutter apps on booted Simulators connect with zero config (physical iPhones via `host:` parameter)
- **📦 Mock Collections**: Organize rules in collections, enable/disable with one click, export/import as JSON with smart merge
- **⚡ Zero Configuration**: No proxy setup, certificates, or iptables required — works even with certificate pinning
- **🔒 Debug-Only**: Automatically excluded from release builds (multiple security layers)
- **💉 Automatic Injection**: Gradle plugin injects interceptor via bytecode transformation
- **🖥️ Cross-Platform IDE**: Works on macOS (Intel & Apple Silicon), Windows, and Linux
- **🤖 AI Agent Access (MCP)**: Claude Code (or any MCP client) can read captured traffic, create and explain mock rules, and drive the capture session through a bundled, loopback-only bridge — see [AI Agent Access](#-ai-agent-access-claude-code--mcp)

---

## 📋 Requirements

- **IntelliJ IDEA 2024.3+** or an **Android Studio release based on 2024.3+**
- **Android:** Android SDK with platform-tools (ADB); emulator or physical device (API Level 21+); app using **OkHttp** (Retrofit uses OkHttp internally)
- **Flutter:** the [`mockk_http`](https://pub.dev/packages/mockk_http) package (works with `HttpClient`, `package:http` and `dio`)
- **iOS (Flutter):** macOS with **Xcode 15+** — iOS Simulators work with zero config; physical iPhones are best-effort via `MockkHttp.init(host: ...)`

---

## 📦 Installation

### Step 1: Install the IntelliJ Plugin

**Option A: From JetBrains Marketplace**
1. Open IDE
2. `File` > `Settings` > `Plugins`
3. Search for "MockkHttp"
4. Click `Install`
5. Restart IDE

**Option B: Build from source**
```bash
git clone https://github.com/sergiydev09/MockkHttp.git
cd MockkHttp
./gradlew buildPlugin
# Install build/distributions/MockkHttp-1.8.0.zip via Settings > Plugins > Install from Disk
```

### Step 2 (Android): Add Gradle Plugin to Your App

In your app's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
    id("io.github.sergiydev09.mockkhttp") version "1.8.0"  // Add this
}

// That's it! No need to add dependencies manually.
// The plugin automatically adds the interceptor dependency and injects it.
```

The plugin will automatically:
- Add the `mockk-http-interceptor` dependency to your debug builds
- Inject the interceptor into all `OkHttpClient` instances via bytecode transformation

### Step 2 (Flutter): Add the mockk_http Package

In your app's `pubspec.yaml`:

```yaml
dependencies:
  mockk_http: ^1.8.0
```

Then initialize it before running the app:

```dart
import 'package:mockk_http/mockk_http.dart';

void main() {
  MockkHttp.init(); // Intercepts HttpClient, package:http and any dart:io-based library
  runApp(MyApp());
}
```

Using [dio](https://pub.dev/packages/dio)? Add the interceptor as well: `dio.interceptors.add(MockkHttpDioInterceptor(dio: dio))`.
See the [mockk_http README](flutter-package/README.md) for iOS Simulator (zero config), physical devices and debug-only setup.

---

## 🚀 Quick Start

### 1. Open MockkHttp Tool Window

- `View` > `Tool Windows` > `MockkHttp`
- Or click the MockkHttp icon in the bottom toolbar

### 2. Select Your Setup

1. **Select Device** from the dropdown — Android emulators, physical Android devices and booted iOS Simulators all appear in the same list
2. **Select App** from the dropdown (only shows apps with MockkHttp enabled)
3. Choose a mode and click **Start**

### 3. Use Your App

Make network requests in your app and see them appear in the MockkHttp window!

---

## 🤖 AI Agent Access (Claude Code / MCP)

Since 1.8.0 an AI coding agent can drive MockkHttp the way you do from the tool window: read what the app called, create a mock rule from a captured flow, switch the session to Mockk mode, wait for the next request that matches a pattern, and ask why a rule did or did not fire.

### Setup (once per project)

1. Open **MockkHttp → Settings → AI Agent Access** and leave access on **Full** (or pick **Read-only**).
2. Click **Write .mcp.json into the project root**. The entry is merged into an existing `.mcp.json` (other servers are kept, invalid JSON is refused rather than overwritten, `.gitignore` is never touched). It contains no port, token or project id, so it is safe to commit.
3. Start `claude` in the project directory. The bridge discovers the running IDE, its port and its token from `~/.mockkhttp/instances/` on every call, so an IDE restart needs no configuration change.

There are no prerequisites: the bridge is a jar shipped inside the plugin, copied to `~/.mockkhttp/bin` on every IDE start and launched with the IDE's own JBR. No Node, no npm, no `JAVA_HOME`.

### What the agent gets

| Tool | What it does |
|------|--------------|
| `mockkhttp_status` | Which project, session, mode and device the agent is looking at, plus warnings that name the next call |
| `mockkhttp_flows` | List, filter and read captured flows (headers and bodies); clear the journal |
| `mockkhttp_await_flow` | Block until a request matching a pattern arrives — no polling, no sleeping |
| `mockkhttp_mocks` | Create, update, enable, delete, export and import mock rules and collections; build a rule from a captured flow with `from_flow_id` |
| `mockkhttp_match_explain` | Explain, rule by rule, why a request would or would not be mocked |
| `mockkhttp_session` | Start, stop and restart capture, choose the app, list devices, change the mode |
| `mockkhttp_docs` | Ten topics written for a model: quickstart, modes, mocking, matching, flows, automated_test, … |

The same API is available over plain HTTP for scripts: `GET http://127.0.0.1:<port>/v1/docs` — the port and bearer token are in `~/.mockkhttp/instances/<id>.json`, and every request must carry an `X-MockkHttp-Client` header.

### Security model

- The control plane listens on `127.0.0.1` only, on a port the OS picks, behind a bearer token that rotates on every IDE start (and on **Revoke**). Nothing on your network can reach it, and no setting opens it up.
- **Off** closes the port and deletes the discovery file; **Read-only** serves reads and refuses every change with 403.
- Captured credentials — `Authorization`, `Cookie`, API keys, whether in a header or in the query string (`appid`, `api_key`, `token`, …) — are returned as `<redacted:Nb>` with their true length, and a mock rule built from a captured flow never keeps them. **Allow an agent to read redacted header values** is off by default, resets on every IDE restart, and every answer that reveals a credential says so in a warning.
- Every call an agent makes is listed in the Inspector next to the traffic your app produced.

Not yet available to agents (reported as `not_implemented_yet` by `mockkhttp_status`): answering Debug-mode pauses, assertion "arms", test runs and their verification, launching the app under test.

---

## 🎮 Operating Modes

### 🔴 Recording Mode

**Purpose**: Capture all HTTP/HTTPS traffic without interfering with the app.

**How it works**:
- App makes request → OkHttpInterceptor captures it → Sends to plugin → App receives normal response
- Requests are NOT paused, they flow normally
- Perfect for analyzing API calls and debugging network issues

**Use cases**:
- Monitor API calls
- Debug network errors
- Export flows for documentation
- Create mock rules from real responses

---

### 🐛 Debug Mode

**Purpose**: Pause each request and modify the response before delivering it to the app.

**How it works**:
1. App makes request
2. Interceptor pauses the thread and sends request to plugin
3. Plugin shows dialog with request/response editor
4. You edit the response (status, headers, body)
5. Click "Continue" → App receives your modified response

**Dialog features**:
- Edit response status code (200, 404, 500, etc.)
- Add/modify/remove headers
- Edit response body (JSON, XML, text, binary)
- Save as mock rule for future use
- Syntax highlighting for JSON

**Use cases**:
- Test error handling (simulate 500 errors)
- Test edge cases (empty responses, malformed data)
- Test slow networks (delay before clicking Continue)
- Bypass authentication (modify login responses)
- Test app behavior with different data

---

### 📋 Mockk Mode

**Purpose**: Automatically replace responses based on predefined rules.

**How it works**:
1. Create mock rules in the Mockk tab:
   - Define URL pattern (method, host, path)
   - Specify response (status, headers, body)
   - Save rule
2. Activate Mockk mode
3. When a matching request arrives, the interceptor automatically returns your mock response

**Rule matching**:
- Exact URL match
- Regex patterns
- Query parameter matching
- Header matching

**Use cases**:
- Offline development (mock backend responses)
- Demo mode (consistent data for presentations)
- Integration tests (predictable responses)
- Feature flags simulation

---

## 🔒 Security Features

MockkHttp includes **4 layers of security** to prevent accidental inclusion in release builds:

### Layer 1: Build-Time Check
The Gradle plugin scans your dependencies and **fails the build** if MockkHttp is in `implementation` or `releaseImplementation`:

```kotlin
// ❌ DON'T add the dependency manually - the plugin does it automatically!
dependencies {
    // Don't do this - it's redundant and may cause conflicts
    debugImplementation("com.github.sergiydev09.MockkHttp:mockk-http-interceptor:1.8.0")
}

// ✅ Just apply the plugin - it handles everything
plugins {
    id("io.github.sergiydev09.mockkhttp") version "1.8.0"
}
```

### Layer 2: Bytecode Injection Skip
The Gradle plugin only injects the interceptor in **debug variants**, skipping all release variants.

### Layer 3: Runtime Check
The interceptor verifies `BuildConfig.DEBUG` at runtime and disables itself if `DEBUG == false`.

### Layer 4: ProGuard/R8 Strip Rules
Consumer ProGuard rules ensure complete removal of all MockkHttp code in release builds.

**Result**: It's **impossible** to ship MockkHttp in production, even if you try!

---

## 🛠️ Use Cases

### 1. Error Handling Testing

Simulate server errors to verify your app shows proper error messages:

```json
Status: 500
Body: {"error": "Internal Server Error", "code": "SERVER_DOWN"}
```

### 2. Authentication Bypass

Test authenticated features without backend dependency:

```json
Status: 200
Body: {
  "token": "fake-jwt-token",
  "user": {
    "id": 123,
    "email": "test@example.com",
    "role": "admin"
  }
}
```

### 3. Empty States Testing

Verify your app handles empty lists gracefully:

```json
Status: 200
Body: {
  "results": [],
  "total": 0,
  "page": 1
}
```

### 4. Slow Network Simulation

In Debug mode, wait 10 seconds before clicking "Continue" to test:
- Loading indicators
- Timeout handling
- User experience with latency

### 5. Malformed Data Testing

Verify your app handles corrupted responses:

```json
Status: 200
Body: "invalid json {["
```

---

## 🏗️ How It Works

### Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│         MockkHttp IntelliJ Plugin (Kotlin)          │
│              Port 9876 (TCP Server)                 │
├─────────────────────────────────────────────────────┤
│ • InspectorPanel: Main UI with flow list           │
│ • MockkRulesPanel: Mock rule management            │
│ • HelpPanel: Setup instructions                    │
│ • FlowStore: Stores captured flows                 │
│ • OkHttpInterceptorServer: Receives flows from app │
└────────────────┬────────────────────────────────────┘
                 │ TCP Socket (JSON messages)
                 │ App → Plugin: FlowData + waits
                 │ Plugin → App: ModifiedResponse
                 ↓
┌─────────────────────────────────────────────────────┐
│          Android App (Debug Build)                  │
├─────────────────────────────────────────────────────┤
│ OkHttpClient.Builder()                              │
│   .addInterceptor(MockkHttpInterceptor(context)) ← Injected
│   .build()                                          │
│                                                     │
│ MockkHttpInterceptor:                              │
│ • Captures every request/response                  │
│ • Serializes to JSON                               │
│ • Sends to plugin via socket (10.0.2.2:9876)      │
│ • Waits for modified response (Debug mode)        │
│ • Returns response to app                          │
└─────────────────────────────────────────────────────┘
```

### Debug Mode Flow (Step by Step)

1. **App makes HTTP request**
   ```kotlin
   val response = apiService.login(username, password)
   ```

2. **OkHttpInterceptor captures request**
   ```kotlin
   // Inside MockkHttpInterceptor
   val request = chain.request()
   val response = chain.proceed(request) // Get real response
   ```

3. **Interceptor connects to plugin**
   ```kotlin
   val socket = Socket("10.0.2.2", 9876) // Host machine from emulator
   socket.getOutputStream().write(flowJson.toByteArray())
   ```

4. **Thread pauses waiting for response**
   ```kotlin
   val modifiedJson = socket.getInputStream().bufferedReader().readLine()
   // ⏸️ THREAD BLOCKS HERE until user clicks "Continue"
   ```

5. **Plugin shows dialog to user**
   - User edits response
   - Clicks "Continue"

6. **Plugin sends modified response back**
   ```kotlin
   socket.getOutputStream().write(modifiedResponseJson.toByteArray())
   ```

7. **Interceptor builds modified response**
   ```kotlin
   val modifiedResponse = originalResponse.newBuilder()
       .code(modifiedStatusCode)
       .body(modifiedBody.toResponseBody())
       .build()
   return modifiedResponse
   ```

8. **App receives modified response**
   ```kotlin
   // App code continues with modified data
   val loginResult = response.body()
   ```

---

## 🐛 Troubleshooting

### No flows appearing?

**Check these**:
1. Is the Gradle plugin applied in your `build.gradle.kts`?
2. Did you rebuild your app after adding the plugin? (`./gradlew clean assembleDebug`)
3. Is your app using OkHttp? (Retrofit uses OkHttp internally)
4. Is the plugin server running? (green indicator in the UI)
5. Check Logs tab for connection messages

**Verify bytecode injection worked**:
```bash
./gradlew assembleDebug
# Look for: "🔌 MockkHttp: Injecting interceptor into OkHttpClient"
```

### App crashes with "Connection refused"

**Cause**: Plugin server is not running or port is blocked.

**Solution**:
1. Check plugin server status (should show "Running")
2. Verify port 9876 is not blocked by firewall
3. Try restarting the interceptor from the UI

### Modified responses not working

**Symptoms**: Changes in Debug mode are ignored by the app.

**Check**:
1. Response body is valid (JSON syntax, correct format)
2. Content-Type header matches body format
3. Check Logs tab for error messages

### Interceptor not injected in debug build

**Symptoms**: Build log shows "Skipped for release" even for debug builds.

**Cause**: Build variant configuration issue.

**Solution**:
```bash
# Clean build
./gradlew clean

# Verify you're building debug variant
./gradlew assembleDebug

# NOT assembleRelease
```

---

## 📚 API Reference

### Flow Data Format (App → Plugin)

```json
{
  "flowId": "uuid-string",
  "paused": true,
  "request": {
    "method": "POST",
    "url": "https://api.example.com/login",
    "headers": {
      "Content-Type": "application/json",
      "Authorization": "Bearer token"
    },
    "body": "{\"username\":\"john\"}"
  },
  "response": {
    "statusCode": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "body": "{\"token\":\"abc123\"}"
  },
  "timestamp": 1234567890.123,
  "duration": 0.456
}
```

### Modified Response Format (Plugin → App)

```json
{
  "statusCode": 404,
  "headers": {
    "Content-Type": "application/json",
    "X-Custom-Header": "value"
  },
  "body": "{\"error\":\"Not Found\"}"
}
```

If you don't want to modify something, set it to `null`:

```json
{
  "statusCode": null,    // Keep original
  "headers": null,       // Keep original
  "body": "{\"edited\":true}"  // Only modify body
}
```

---

## 🔧 Development

### Project Structure

```
MockkHttp/
├── src/main/kotlin/com/sergiy/dev/mockkhttp/
│   ├── adb/                    # ADB integration
│   │   ├── EmulatorManager.kt
│   │   └── AppManager.kt
│   ├── logging/
│   │   └── MockkHttpLogger.kt  # Centralized logging
│   ├── model/
│   │   └── HttpFlowData.kt     # Data classes
│   ├── proxy/
│   │   └── OkHttpInterceptorServer.kt  # TCP server
│   ├── store/
│   │   ├── FlowStore.kt
│   │   └── MockkRulesStore.kt
│   └── ui/
│       ├── MockkHttpToolWindow.kt
│       ├── InspectorPanel.kt
│       ├── MockkRulesPanel.kt
│       └── HelpPanel.kt
├── android-library/            # Android interceptor library
│   └── src/main/kotlin/com/sergiy/dev/mockkhttp/interceptor/
│       └── MockkHttpInterceptor.kt
├── gradle-plugin/              # Gradle plugin
│   └── src/main/kotlin/com/sergiy/dev/mockkhttp/gradle/
│       ├── MockkHttpGradlePlugin.kt
│       └── OkHttpInterceptorTransform.kt  # ASM bytecode injection
└── build.gradle.kts
```

### Build Commands

```bash
# Build plugin
./gradlew build

# Run in IDE sandbox
./gradlew runIde

# Publish to Maven Local (for testing)
./gradlew publishToMavenLocal

# Build plugin distribution
./gradlew buildPlugin
```

### Testing Locally

**Test the Gradle plugin**:
```bash
# Publish to Maven Local
./gradlew publishToMavenLocal

# In your test app's settings.gradle.kts:
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

# In your test app's build.gradle.kts:
plugins {
    id("io.github.sergiydev09.mockkhttp") version "1.8.0"
}
```

---

## 🤝 Contributing

Contributions are welcome!

**Process**:
1. Fork the repository
2. Create feature branch: `git checkout -b feature/new-feature`
3. Make changes and commit: `git commit -m 'Add new feature'`
4. Push: `git push origin feature/new-feature`
5. Open Pull Request

**Guidelines**:
- Follow Kotlin coding conventions
- Add logging with emojis for important operations
- Update documentation if needed
- Test with a real Android app before submitting

---

## 📄 License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgments

- **[OkHttp](https://square.github.io/okhttp/)** - Excellent HTTP client library
- **[IntelliJ Platform](https://plugins.jetbrains.com/docs/intellij/)** - Powerful plugin SDK
- **[ASM](https://asm.ow2.io/)** - Bytecode manipulation framework
- **[Charles Proxy](https://www.charlesproxy.com/)** - UX inspiration

---

## 📧 Support

- **GitHub Issues**: [Report bugs or request features](https://github.com/sergiydev09/MockkHttp/issues)
- **Discussions**: [Ask questions](https://github.com/sergiydev09/MockkHttp/discussions)
- **Wiki**: [Documentation](https://github.com/sergiydev09/MockkHttp/wiki)

---

<div align="center">

**⭐ If you find this project useful, give it a star on GitHub! ⭐**

Made with ❤️ for mobile developers

</div>
