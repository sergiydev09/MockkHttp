# MockkHttp

<div align="center">

![MockkHttp Logo](src/main/resources/META-INF/pluginIcon.svg)

**Network Interceptor Plugin for Android Studio & IntelliJ IDEA**

Intercepts and modifies HTTP/HTTPS traffic from Android applications in real-time

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-IntelliJ-orange.svg)](https://www.jetbrains.com/idea/)
[![Version](https://img.shields.io/badge/version-1.0.0-green.svg)]()

</div>

---

## 🎯 Features

- **🔴 Recording Mode**: Captures all HTTP/HTTPS calls from your app without pausing
- **🐛 Debug Mode**: Intercepts requests in real-time, pauses execution, and allows editing responses before delivering them to the app
- **📋 Mockk Mode**: Applies mock rules automatically without manual intervention
- **⚡ Automatic Setup**: One-click certificate installation (Proxyman method)
- **🔥 App Firewall**: Filters traffic using iptables in the emulator (only intercepts the selected app)
- **💾 Export/Import**: Exports flows as JSON and creates mock rules from real responses
- **🍎 Optimized for Mac M1/M2/M3**: Native ARM64 support

---

## 📋 Requirements

### Operating System
- **macOS** (Apple Silicon or Intel)
- **Python 3.8+** (included with macOS)
- **mitmproxy** installed via Homebrew

### Development Tools
- **IntelliJ IDEA 2025.1+** or **Android Studio Ladybug (2024.2+)**
- **Android SDK** with `platform-tools` (ADB)
- **ANDROID_HOME** configured (or SDK in default location: `~/Library/Android/sdk`)

### Android Emulator
- **API Level 34+** (Android 14 or higher)
- **ARM64 Architecture** (`arm64-v8a`) required for Mac M1/M2/M3
- **Google APIs System Image** (can include Google Play, no need to remove it)
- **Writable system** (for certificate installation in `/system`)

> ⚠️ **Note about Google Play**: Previously it was recommended to use "Google APIs" without Play Store. In reality, you can use emulators with Play Store without issues - the certificate is installed in the system, Magisk is not required.

### App Configuration to Debug

Your Android app **MUST** trust user certificates for the proxy to work:

**1. Create `res/xml/network_security_config.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

**2. Reference in `AndroidManifest.xml`:**

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
</application>
```

> 🔒 **Why is this mandatory?** Android 7+ doesn't trust user certificates by default. This configuration allows your app to accept mitmproxy's CA certificate.

---

## 📦 Installation

### Step 1: Install Dependencies

```bash
# Install mitmproxy (if not installed)
brew install mitmproxy

# Verify installation
mitmdump --version
# Expected output: Mitmproxy: 10.x.x
```

### Step 2: Build the Plugin

```bash
# Clone the repository
git clone https://github.com/sergiydev09/MockkHttp.git
cd MockkHttp

# Build the plugin
./gradlew buildPlugin

# The .zip file will be in: build/distributions/MockkHttp-1.0.0.zip
```

### Step 3: Install in IntelliJ IDEA / Android Studio

**Option A: From local file**
1. Open IDE
2. `File` > `Settings` > `Plugins`
3. ⚙️ > `Install Plugin from Disk...`
4. Select `build/distributions/MockkHttp-1.0.0.zip`
5. Restart IDE

**Option B: Run in development mode**
```bash
./gradlew runIde
```

### Step 4: Verify Environment

Open terminal and verify:

```bash
# Verify ADB
adb version
# Output: Android Debug Bridge version 1.0.x

# Verify ANDROID_HOME
echo $ANDROID_HOME
# Output: /Users/sergiydev09/Library/Android/sdk

# List available emulators
adb devices
# Output: List of devices attached
#         emulator-5554   device
```

---

## 🎮 How to Use

### First Time: Certificate Setup

1. **Open the Tool Window**
   - `View` > `Tool Windows` > `MockkHttp`
   - Or click on the MockkHttp icon in the bottom toolbar

2. **Install Certificate Automatically**
   - Tab `Inspector`
   - Select emulator from dropdown
   - Click on installation button (certificate icon) 🔧
   - Wait for confirmation message

**What does this step do?**
- Generates mitmproxy CA certificate (~/.mitmproxy/mitmproxy-ca-cert.pem)
- Copies it to `/system/etc/security/cacerts/` in the emulator with name `{hash}.0`
- Sets permissions to `644` (rw-r--r--)
- **Doesn't require restarting the emulator** (Proxyman method)

> ✅ This step is only necessary **once per emulator**

---

### Mode 1: Recording Mode (Passive Capture)

**Purpose**: Record all HTTP/HTTPS traffic without interfering with the app.

**Steps**:
1. Select emulator and app
2. Click on Recording button (red circle) 🔴
3. Use your app normally
4. View requests/responses in the list

**Internally**:
- mitmproxy intercepts all traffic
- Python addon sends flows to the plugin via `POST http://localhost:8765/intercept`
- Plugin displays flows in the list
- **Flows are NOT paused**, they pass directly to the app

**App Filtering**:
The plugin configures iptables in the emulator to redirect only the traffic from the selected app:

```bash
# iptables rules (automatic, don't execute them manually)
iptables -t nat -A OUTPUT -p tcp -m owner --uid-owner {APP_UID} --dport 80 -j REDIRECT --to-port 8080
iptables -t nat -A OUTPUT -p tcp -m owner --uid-owner {APP_UID} --dport 443 -j REDIRECT --to-port 8080
```

---

### Mode 2: Debug Mode (Interactive Interception)

**Purpose**: Pause each request and allow modifying the response before delivering it to the app.

**Steps**:
1. Select emulator and app
2. Click on Debug button (bug icon) 🐛
3. Each request will open an editing dialog:

```
┌─────────────────────────────────────────────────────────┐
│ 🌐 Request Intercepted                                  │
├─────────────────────────────────────────────────────────┤
│ REQUEST                         RESPONSE                │
│ POST /api/login                 Status: 200             │
│ Host: api.example.com           [Dropdown: 200, 400...] │
│                                                          │
│ Headers:                        Headers:                │
│ Authorization: Bearer ...       Content-Type: app/json  │
│ Content-Type: app/json          [Tabla editable]        │
│                                                          │
│ Body:                           Body:                   │
│ {                               {                       │
│   "user": "john",               "token": "abc123",      │
│   "pass": "***"                 "role": "admin"         │
│ }                               }                       │
│                                 [Text Area editable]    │
│                                                          │
│ [Save as Mock Rule]             [Cancel] [Continue →]   │
└─────────────────────────────────────────────────────────┘
```

4. Edit response (status, headers, body)
5. Optionally: check "Save as Mock Rule" to reuse
6. Click **Continue** → app receives the modified response

**How does the pause work?**

The Python addon uses the mitmproxy API:

```python
# In the addon (src/main/python/mitmproxy_addon/debug_interceptor.py)
async def response(self, flow: http.HTTPFlow):
    flow.intercept()  # Marks the flow as intercepted
    await self.send_to_plugin(flow, pause=True)  # Sends to the plugin

    # ⭐ THIS LINE PAUSES THE FLOW ⭐
    await flow.wait_for_resume()  # Waits until the plugin calls /resume

    # Here the flow continues after the user clicks "Continue"
```

**Resume the flow**:
The plugin sends modifications via HTTP:

```bash
# POST http://localhost:9999/resume
{
  "flow_id": "uuid-del-flow",
  "modified_response": {
    "status_code": 200,
    "headers": {"Content-Type": "application/json"},
    "content": "{\"role\": \"admin\"}"
  }
}
```

---

### Mode 3: Mockk Mode (Automatic Rules)

**Purpose**: Apply mocked responses automatically without manual intervention.

**Steps**:
1. Create mock rules in `Mockk` tab:
   - Click "New Mock Rule"
   - Configure: method, host, path, query params
   - Define response: status, headers, body
   - Save rule

2. Activate Mockk Mode (button with schema icon) 📋

3. Use the app normally

**How does it work?**

When a request arrives, the Python addon queries the plugin:

```python
# Addon asks: Is there a mock for this request?
GET http://localhost:8765/mock-match?method=POST&host=api.example.com&path=/login&query_foo=bar

# Plugin responds with mock (if there's a match):
{
  "rule_name": "Login Success",
  "rule_id": "uuid",
  "status_code": 200,
  "headers": {"Content-Type": "application/json"},
  "content": "{\"token\": \"fake-jwt\", \"role\": \"admin\"}"
}

# Addon applies the mock automatically and continues the flow
```

**Create mocks from real responses**:
- Recording/Debug Mode: capture flow
- Right-click on the flow > "Create Mock Rule from Response"
- Adjust if necessary > Save
- Activate Mockk Mode

---

### Stop Interception

- Click on the active button (Recording/Debug/Mockk) again
- Or click on **Stop** button 🛑
- The mitmproxy process will stop automatically
- The iptables rules will be cleaned up

---

## 🛠️ Use Cases

### 1. Error Handling Testing

```json
// Simulate 500 server error
Status: 500
Body: {"error": "Internal Server Error", "code": "SERVER_DOWN"}
```

**Usage**: Verify that your app shows an appropriate error message instead of crashing.

---

### 2. Authentication Bypass

```json
// Force successful login without valid credentials
Status: 200
Body: {
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": 123,
    "email": "test@example.com",
    "role": "admin"
  }
}
```

**Usage**: Testing functionalities that require authentication without depending on the backend.

---

### 3. Empty States Testing

```json
// Simulate empty list
Status: 200
Body: {
  "results": [],
  "total": 0,
  "page": 1
}
```

**Usage**: Verify that your app correctly displays the "no results" state.

---

### 4. Extreme Data Testing

```json
// List with 1000 elements
Status: 200
Body: {
  "results": [{...}, {...}, ...],  // 1000 items
  "total": 1000
}
```

**Usage**: Verify pagination, scroll performance, memory leaks.

---

### 5. Slow Response Testing

In Debug Mode, simply wait 10 seconds before clicking "Continue".

**Usage**: Testing timeouts, loading indicators, UX with latency.

---

### 6. Corrupted Data Testing

```json
// Malformed JSON
Status: 200
Body: "invalid json {["
```

**Usage**: Verify that your app handles parsing errors without crashing.

---

## 🏗️ Technical Architecture

### Component Diagram

```
┌────────────────────────────────────────────────────────┐
│           MockkHttp Plugin (Kotlin/Swing)              │
│           Port 8765 (HTTP Server)                      │
├────────────────────────────────────────────────────────┤
│ • InspectorPanel (main UI)                             │
│ • MockkRulesPanel (mock management)                    │
│ • LogPanel (unified logs)                              │
│                                                         │
│ Services:                                               │
│ • MitmproxyController: mitmproxy lifecycle             │
│ • PluginHttpServer: receives flows from addon          │
│ • ProxyFirewallManager: configures iptables            │
│ • CertificateManager: installs certificates            │
│ • EmulatorManager: detects emulators (ADB bridge)      │
│ • AppManager: lists installed apps                     │
│ • FlowStore: stores captured flows                     │
│ • MockkRulesStore: manages mock rules                  │
└─────────────────┬──────────────────────────────────────┘
                  │ Bidirectional HTTP
                  │ • Plugin → Addon: POST /resume
                  │ • Addon → Plugin: POST /intercept
                  │ • Addon → Plugin: GET /mock-match
                  ↓
┌────────────────────────────────────────────────────────┐
│        mitmproxy + Python Addon (3 processes)          │
│   Port 8080 (Proxy) + Port 9999 (Control API)         │
├────────────────────────────────────────────────────────┤
│ Addon: debug_interceptor.py                            │
│ • Recording Mode: captures and sends flows             │
│ • Debug Mode: pauses flows with flow.intercept()      │
│ • Mockk Mode: queries and applies mocks                │
│                                                         │
│ Internal HTTP Server (port 9999):                      │
│ • GET /status - addon status                           │
│ • POST /resume - resume paused flow                    │
└─────────────────┬──────────────────────────────────────┘
                  │ MITM Proxy (mitmproxy)
                  │ Intercepts all HTTP/HTTPS
                  ↓
┌────────────────────────────────────────────────────────┐
│           Android Emulator (API 34+)                   │
├────────────────────────────────────────────────────────┤
│ • System: arm64-v8a (ARM64)                            │
│ • Certificate: /system/etc/security/cacerts/{hash}.0   │
│ • iptables: redirects app traffic to port 8080         │
│ • Proxy: 10.0.2.2:8080 (configured automatically)      │
│                                                         │
│ App under test:                                         │
│ • network_security_config: trust user certs            │
│ • Redirected traffic: everything goes through mitmproxy│
└────────────────────────────────────────────────────────┘
```

### Technical Flow (Debug Mode)

**1. Initialization**:
```kotlin
// Plugin starts mitmproxy with configuration
val config = MitmproxyConfig(
    mode = Mode.DEBUG,
    proxyPort = 8080,
    controlPort = 9999,
    pluginServerPort = 8765,
    addonScriptPath = "/tmp/debug_interceptor.py"
)
mitmproxyController.start(config)

// Plugin starts its own HTTP server
pluginHttpServer.start() // Port 8765

// Configures iptables in emulator (filters by app UID)
proxyFirewallManager.setupAppFirewall(emulator, appUid)
```

**2. App makes request**:
```
App → iptables (redirects by UID) → mitmproxy:8080 → Real server
```

**3. Server responds**:
```python
# Addon intercepts response
async def response(self, flow: http.HTTPFlow):
    flow.intercept()  # Pauses the flow
    await self.send_to_plugin(flow, pause=True)  # POST http://localhost:8765/intercept
    await flow.wait_for_resume()  # ⏸️ WAITS HERE
```

**4. Plugin receives flow**:
```kotlin
// PluginHttpServer.kt receives POST /intercept
flowCallback.invoke(flowData)

// InspectorPanel shows dialog
showDebugDialog(flow)
```

**5. User edits and continues**:
```kotlin
// User modifies response and clicks "Continue"
val modifiedResponse = dialog.getModifiedResponse()

// Plugin sends modifications to addon
mitmproxyClient.resumeFlow(flowId, modifiedResponse)
// POST http://localhost:9999/resume
```

**6. Addon applies changes and continues**:
```python
# Addon receives POST /resume
def handle_resume(self):
    flow.response.status_code = modified_status
    flow.response.content = modified_content.encode('utf-8')
    flow.resume()  # ▶️ CONTINUES THE FLOW
```

**7. App receives modified response**:
```
mitmproxy → iptables → App (receives edited response)
```

---

## 📖 API Reference

### Python Addon (Endpoints)

#### `GET /status`
Returns addon status.

**Response**:
```json
{
  "status": "running",
  "mode": "debug",
  "intercepted_count": 3,
  "intercepted_flows": ["uuid1", "uuid2", "uuid3"],
  "plugin_port": 9999,
  "plugin_client_port": 8765
}
```

#### `POST /resume`
Resumes a paused flow with optional modifications.

**Request**:
```json
{
  "flow_id": "flow-uuid",
  "modified_response": {
    "status_code": 200,
    "headers": {"Content-Type": "application/json"},
    "content": "{\"role\": \"admin\"}"
  }
}
```

**Response**:
```json
{
  "status": "resumed",
  "flow_id": "flow-uuid"
}
```

---

### Plugin HTTP Server (Endpoints)

#### `POST /intercept`
Receives flows from the mitmproxy addon.

**Request**:
```json
{
  "flow_id": "uuid",
  "paused": true,
  "request": {
    "method": "POST",
    "url": "https://api.example.com/login",
    "host": "api.example.com",
    "path": "/login",
    "headers": {"Content-Type": "application/json"},
    "content": "{\"user\":\"john\"}"
  },
  "response": {
    "status_code": 200,
    "reason": "OK",
    "headers": {"Content-Type": "application/json"},
    "content": "{\"token\":\"abc\"}"
  },
  "timestamp": 1234567890.123,
  "duration": 0.456,
  "mock_applied": false,
  "mock_rule_name": null
}
```

#### `GET /mock-match`
Queries if there's a mock rule that matches the request.

**Query Params**:
- `method`: GET, POST, etc.
- `host`: api.example.com
- `path`: /login
- `query_*`: query parameters (e.g.: `query_foo=bar`)

**Response** (if there's a match):
```json
{
  "rule_name": "Login Success",
  "rule_id": "uuid",
  "status_code": 200,
  "headers": {"Content-Type": "application/json"},
  "content": "{\"token\": \"fake-jwt\"}"
}
```

**Response** (no match):
```json
{}
```

---

## ⚠️ Known Limitations

### 1. Certificate Pinning
**Problem**: Apps with certificate pinning will reject mitmproxy's certificate.

**Partial Solution**:
- Use Frida to disable pinning at runtime
- Use `apk-mitm` to patch the APK
- Decompile app and remove pinning (requires rebuild)

**Detection**: The app crashes with `SSLHandshakeException` or `CertificateException` error.

---

### 2. Emulators Only
**Problem**: Doesn't work with physical devices.

**Reason**:
- iptables requires root
- Per-app proxy configuration requires shell access

**Roadmap**: Support for rooted devices in v2.0

---

### 3. WebSockets Not Supported
**Problem**: WebSockets pass through without being intercepted.

**Reason**: mitmproxy supports WebSockets, but the current plugin doesn't implement the UI to display them.

**Roadmap**: WebSocket support in v1.1

---

### 4. HTTP/3 (QUIC) Not Supported
**Problem**: Traffic over QUIC is not intercepted.

**Reason**: mitmproxy doesn't support QUIC yet.

**Workaround**: Force HTTP/2 in the emulator (requires Android 12+):
```bash
adb shell settings put global http.quic_user_agent_id ""
```

---

## 🐛 Troubleshooting

### Problem: "Certificate not trusted" in the app

**Symptom**: App shows SSL/TLS error or doesn't load data.

**Diagnosis**:
```bash
# Verify that the certificate is installed
adb shell "ls -la /system/etc/security/cacerts/ | grep $(openssl x509 -inform PEM -subject_hash_old -in ~/.mitmproxy/mitmproxy-ca-cert.pem | head -1)"
```

**Solution**:
1. Verify that the app has `network_security_config.xml` (see [Requirements](#app-configuration-to-debug))
2. Reinstall certificate: Click on the plugin's installation button
3. Verify certificate permissions:
```bash
adb shell "ls -la /system/etc/security/cacerts/*.0 | head -5"
# Should show: -rw-r--r-- 1 root root ... {hash}.0
```

---

### Problem: mitmproxy cannot find the certificate

**Symptom**: Error "Certificate file not found" in plugin logs.

**Diagnosis**:
```bash
# Verify mitmproxy directory
ls -la ~/.mitmproxy/
```

**Solution**:
```bash
# Generate certificate manually
mitmdump --version
# This will create ~/.mitmproxy/mitmproxy-ca-cert.pem automatically

# Verify it was created
ls -la ~/.mitmproxy/mitmproxy-ca-cert.pem
```

---

### Problem: "Address already in use" (port 8080)

**Symptom**: mitmproxy fails to start with port already in use error.

**Diagnosis**:
```bash
# See which process is using port 8080
lsof -i :8080
```

**Solution**:
```bash
# Kill previous process
lsof -ti :8080 | xargs kill

# Or restart the plugin (it does this automatically)
```

---

### Problem: Flows from other apps are shown in the list

**Symptom**: You see traffic from apps you didn't select.

**Diagnosis**: iptables rules were not applied correctly.

**Solution**:
1. Stop interception
2. Verify app UID:
```bash
adb shell "pm list packages -U | grep your.package"
# Output: package:com.your.app uid:10123
```
3. Restart interception (the plugin will reconfigure iptables)

---

### Problem: Emulator very slow on Mac M1

**Symptom**: Emulator lags, apps take long to open.

**Cause**: Using x86_64 emulator instead of ARM64 (emulation instead of virtualization).

**Diagnosis**:
```bash
# Verify AVD architecture
cat ~/.android/avd/YOUR_AVD.avd/config.ini | grep abi
# Should say: abi.type=arm64-v8a
```

**Solution**: Create new AVD with ARM64 System Image:
1. Android Studio > Device Manager
2. Create Device > Select Hardware (e.g.: Pixel 6)
3. System Image: **arm64-v8a** (API 34+)
4. Finish

---

### Problem: Debug dialog doesn't appear

**Symptom**: In Debug Mode, flows pass through without pausing.

**Cause**: The addon is not sending flows to the plugin.

**Diagnosis**:
```bash
# View addon logs (in the window where mitmproxy is running)
# Look for: "⏸️ Waiting for user input on flow"
```

**Solution**:
1. Verify that the plugin HTTP server is running:
```bash
# Should respond
curl http://localhost:8765/status
```
2. Review plugin logs (Logs tab)
3. Restart interception

---

## 🔧 Development and Contributing

### Project Structure

```
MockkHttp/
├── src/main/
│   ├── kotlin/com/sergiy/dev/mockkhttp/
│   │   ├── adb/
│   │   │   ├── EmulatorManager.kt      # ADB bridge, emulator detection
│   │   │   ├── EmulatorInfo.kt         # Data class for emulators
│   │   │   ├── AppManager.kt           # Lists installed apps, gets UID
│   │   │   └── AppInfo.kt              # Data class for apps
│   │   ├── cert/
│   │   │   └── CertificateManager.kt   # Generates and installs certificates
│   │   ├── logging/
│   │   │   └── MockkHttpLogger.kt      # Centralized logger with timestamps
│   │   ├── model/
│   │   │   ├── HttpFlowData.kt         # Data class for HTTP flows
│   │   │   └── MockRuleModels.kt       # Data classes for mock rules
│   │   ├── proxy/
│   │   │   ├── MitmproxyController.kt  # mitmproxy lifecycle
│   │   │   ├── MitmproxyClient.kt      # HTTP client for /resume
│   │   │   ├── PluginHttpServer.kt     # HTTP server (port 8765)
│   │   │   └── ProxyFirewallManager.kt # Configures iptables in emulator
│   │   ├── store/
│   │   │   ├── FlowStore.kt            # Stores captured flows
│   │   │   └── MockkRulesStore.kt      # Stores mock rules
│   │   └── ui/
│   │       ├── MockkHttpToolWindow.kt       # Main Tool Window
│   │       ├── InspectorPanel.kt            # Main tab (controls + flows)
│   │       ├── MockkRulesPanel.kt           # Mock rules tab
│   │       ├── LogPanel.kt                  # Logs tab
│   │       ├── DebugInterceptDialog.kt      # Dialog to edit responses
│   │       ├── FlowDetailsDialog.kt         # Dialog to view flow details
│   │       └── CreateMockDialog.kt          # Dialog to create mock rules
│   ├── python/mitmproxy_addon/
│   │   └── debug_interceptor.py        # mitmproxy addon (3 modes)
│   └── resources/META-INF/
│       ├── plugin.xml                  # Plugin configuration
│       └── pluginIcon.svg              # Plugin icon
├── build.gradle.kts                    # Build script (Kotlin DSL)
├── gradle.properties
└── settings.gradle.kts
```

### Technologies and Dependencies

**Kotlin/JVM**:
- IntelliJ Platform SDK 2025.1
- Kotlin 2.1.0
- JVM Target: 21

**Libraries**:
- `ddmlib 31.7.2` - ADB communication (without depending on Android plugin)
- `okhttp3 4.12.0` - HTTP client for mitmproxy communication
- `gson 2.10.1` - JSON serialization
- `kotlinx-coroutines 1.9.0` - Coroutines (used in background tasks)

**Python**:
- Python 3.8+ (system)
- mitmproxy 10.x (Homebrew)
- **No pip required** - the addon uses only the standard library

### Development Commands

```bash
# Build plugin
./gradlew build

# Run in IDE sandbox (testing)
./gradlew runIde

# Run tests
./gradlew test

# Clean build
./gradlew clean

# View available tasks
./gradlew tasks

# Verify code (lint)
./gradlew check
```

### Manual Testing of Python Addon

**Start mitmproxy manually**:
```bash
# Debug Mode
mitmdump \
  -s src/main/python/mitmproxy_addon/debug_interceptor.py \
  --set intercept_mode=debug \
  --set plugin_port=9999 \
  --set plugin_client_port=8765 \
  --listen-host 0.0.0.0 \
  --listen-port 8080

# Recording Mode
mitmdump \
  -s src/main/python/mitmproxy_addon/debug_interceptor.py \
  --set intercept_mode=recording \
  --listen-host 0.0.0.0 \
  --listen-port 8080
```

**Verify addon status**:
```bash
curl http://localhost:9999/status
```

**Resume a flow manually** (for testing):
```bash
curl -X POST http://localhost:9999/resume \
  -H "Content-Type: application/json" \
  -d '{
    "flow_id": "flow-uuid",
    "modified_response": {
      "status_code": 200,
      "headers": {"X-Test": "true"},
      "content": "{\"hacked\": true}"
    }
  }'
```

### Logging and Debugging

The plugin uses a centralized logger with emojis for easy reading:

```kotlin
logger.info("✅ Operation successful")
logger.error("❌ Operation failed", exception)
logger.warn("⚠️ Warning message")
logger.debug("🔍 Debug info")
```

**View logs in real-time**:
1. `Logs` tab in the Tool Window
2. Or in the IntelliJ console (if running with `./gradlew runIde`)

**mitmproxy addon logs**:
Addon logs are displayed in the plugin's Logs tab with `[mitmproxy]` prefix.

### Contributing

Contributions are welcome!

**Process**:
1. Fork the repository
2. Create branch: `git checkout -b feature/new-feature`
3. Make changes and commit: `git commit -m 'Add new feature'`
4. Push: `git push origin feature/new-feature`
5. Open Pull Request on GitHub

**Guidelines**:
- Maintain existing code style (Kotlin conventions)
- Add logs with emojis for important operations
- Update README if you change visible functionality
- Don't remove technical comments from code

---

## 📄 License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgments

- **[mitmproxy](https://mitmproxy.org/)** - Amazing and flexible MITM proxy
- **[IntelliJ Platform](https://plugins.jetbrains.com/docs/intellij/)** - Robust plugin SDK
- **[Proxyman](https://proxyman.io/)** - Inspiration for certificate installation method
- **[Charles Proxy](https://www.charlesproxy.com/)** - UX reference for HTTP interceptors

---

## 📧 Contact and Support

- **GitHub Issues**: [Report bugs or suggest features](https://github.com/sergiydev09/MockkHttp/issues)
- **Discussions**: [Questions and discussions](https://github.com/sergiydev09/MockkHttp/discussions)

---

<div align="center">

**⭐ If you like this project, give it a star on GitHub ⭐**

Made with ❤️ for Android developers

</div>
