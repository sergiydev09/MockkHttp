plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("com.android.library") version "8.12.3" apply false
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.sergiy.dev"
version = "1.8.0"

repositories {
    mavenCentral()
    google()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2024.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    
    // ADB/ddmlib for emulator communication (standalone, no Android plugin needed)
    implementation("com.android.tools.ddms:ddmlib:32.0.1")

    // HTTP client for mitmproxy communication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.13.2")

    // NOTE: Kotlin Coroutines are intentionally NOT declared here.
    // They are bundled with the IntelliJ Platform and adding them explicitly causes
    // version conflicts / runtime errors flagged by verifyPluginProjectConfiguration.
    // See: https://jb.gg/intellij-platform-kotlin-coroutines

    // JUnit 4, and only JUnit 4: the platform test framework declared above is JUnit 3/4 based
    // (BasePlatformTestCase -> UsefulTestCase -> junit.framework.TestCase), so a JUnit 5 engine
    // would collect none of those classes and still report a green build.
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    publishing {
        token.set(providers.gradleProperty("intellijPublishToken"))
    }

    // Run `./gradlew verifyPlugin` to reproduce the JetBrains Marketplace compatibility checks
    // (deprecated/experimental/internal/removed API usage) before publishing.
    pluginVerification {
        ides {
            recommended()
        }
    }

    pluginConfiguration {
        ideaVersion {
            // Built against IntelliJ Platform 2024.3 with Java 21 bytecode (requires JBR 21),
            // so the honest minimum is 243. Must not be lower than the target platform major.
            sinceBuild = "243"
            // Leave the upper bound open so each new IDE release does not mark the plugin as
            // incompatible. Required for 2024.3+ by verifyPluginProjectConfiguration.
            untilBuild = provider { null }
        }

        changeNotes = """
            <h3>1.8.0 — Drive MockkHttp from an AI agent (Claude Code / MCP)</h3>
            <p>Everything the tool window can do is now available to an AI coding agent, through a local control plane
                and a bundled MCP bridge. The agent sees the same flows and rules you do, and every call it makes is
                listed in the Inspector next to your app's traffic.</p>
            <ul>
                <li><strong>🤖 Agent control plane.</strong> A REST API on <code>127.0.0.1</code> only (port picked by the OS,
                    bearer token, never reachable from the network) exposes status, captured flows (list, get, and a
                    long-poll <code>await</code>), mock rules and collections (full CRUD, <code>explain</code> why a request
                    did or did not match, export/import with dry-run), capture session start/stop/restart, the package
                    filter, device listing and mode changes.</li>
                <li><strong>🧩 MCP bridge with zero prerequisites.</strong> A stdio MCP server ships inside the plugin, is
                    copied to <code>~/.mockkhttp/bin</code> on every start and runs on the IDE's own JBR — no Node, no npm,
                    no JAVA_HOME. Seven tools: <code>mockkhttp_status</code>, <code>mockkhttp_flows</code>,
                    <code>mockkhttp_await_flow</code>, <code>mockkhttp_mocks</code>, <code>mockkhttp_match_explain</code>,
                    <code>mockkhttp_session</code>, <code>mockkhttp_docs</code>. Settings → <em>Write .mcp.json into the
                    project root</em> merges the entry into your project's <code>.mcp.json</code> (never overwrites, never
                    touches .gitignore); the entry carries no port, token or project id, so it is safe to commit.</li>
                <li><strong>🔒 Safe by default.</strong> Access is Full / Read-only / Off in Settings, and Off closes the port
                    outright. Captured credentials (Authorization, Cookie, API keys) come back as
                    <code>&lt;redacted:Nb&gt;</code> unless you arm <em>Allow an agent to read redacted header values</em> —
                    which resets on every IDE restart, and every answer that reveals one says so in a warning.</li>
                <li><strong>🧠 Errors that name the next call.</strong> Every refusal carries the exact call that fixes it, and
                    the bridge rewrites REST routes into MCP tool calls on errors and successes alike.
                    <code>mockkhttp_docs</code> serves ten topics (quickstart, modes, mocking, matching, flows,
                    automated_test, …) written for a model to act on.</li>
                <li><strong>💤 An idle IDE costs the app nothing.</strong> With no capture session owning the app's traffic,
                    any client that asks is told <code>IDLE</code>, so the app stops buffering response bodies and shipping
                    flows nobody reads; with the IDE fully idle, port 9876 closes as well. Older clients keep their
                    previous behaviour.</li>
                <li><strong>🐦 Flutter — <code>mockk_http</code> 1.8.0.</strong> Honours IDLE; request bodies are no longer
                    serialised before the plugin has asked for them; two requests started in the same millisecond no longer
                    share a flow id (which broke flow lookups and <code>from_flow_id</code>); and identical requests are no
                    longer dropped: the 500&nbsp;ms deduplication window silently discarded a genuine second request to the
                    same URL — two screens loading the same data, an immediate retry — so the log was missing calls the app
                    really made. The two capture layers now coordinate by request identity instead, and every request the
                    app makes is a flow. The native Android interceptor 1.8.0 drops its window the same way: a second
                    copy of the interceptor in one chain passes the request through by a tag, and the Gradle plugin's
                    <code>install(builder)</code> no longer stacks copies on a builder built twice.</li>
                <li><strong>📊 The client reports its own numbers.</strong> <code>mockk_http</code> 1.8.0 sends its
                    library, version, platform and counters (flows sent by layer, passes one layer yielded to the
                    other, claims made and withdrawn) with every message; <code>status</code> and the flow listing
                    expose the latest as <code>client</code>, so an agent can check from outside that one request is
                    one flow instead of trusting it.</li>
                <li><strong>🔐 Credentials in the URL.</strong> A query parameter named like a credential (<code>appid</code>,
                    <code>api_key</code>, <code>token</code>, …) is redacted like a header on every surface an agent reads, and a
                    rule built from a captured flow never keeps its value.</li>
                <li><strong>🧪 A real test suite.</strong> 229 tests across the plugin and the MCP bridge, 57 in the Flutter package
                    and 4 in the Android library, with a build guard that fails when the suite is empty or silently skipped.</li>
            </ul>
            <p>Not in this release, and reported as <code>not_implemented_yet</code> by <code>status</code>: arms/runs/verify,
                agent-driven Debug pauses, launching the app under test. IntelliJ plugin, Gradle plugin, Android library
                and the <code>mockk_http</code> Flutter package are all released as 1.8.0.</p>

            <h3>1.7.1 — Correctness release</h3>
            <p>No new features: fifteen bugs, several of which lost data silently.</p>
            <ul>
                <li><strong>Flutter: responses were truncated.</strong> Any reply from the plugin larger than a single
                    TCP read — a mocked JSON body of a few KB is enough — was cut short, failed to parse, and the app
                    silently fell back to the original response with no error anywhere. Requires <code>mockk_http</code> 1.7.1.</li>
                <li><strong>Non-ASCII bodies were corrupted.</strong> The socket used the JVM's platform charset while both
                    interceptors always write UTF-8. Now pinned to UTF-8 on both ends.</li>
                <li><strong>Debug edits were lost after 60 seconds.</strong> The plugin waited five minutes for your decision
                    while the app gave up after one, so a late edit was written to a dead socket and discarded without a word.
                    The dialog now works to the app's real budget, warns when a response could not be delivered, and closes
                    itself instead of leaving an orphaned modal on screen.</li>
                <li><strong>Native Android apps never appeared in the app list.</strong> Only Flutter apps announced their
                    package, so the interceptable-app detection was blind to OkHttp apps. Any message carrying a package
                    name now registers it.</li>
                <li><strong>Colliding rule and collection ids.</strong> Ids were derived from the millisecond clock, so two
                    items created in the same millisecond shared one — and for collections the duplicate silently replaced
                    the first, destroying it and orphaning its rules. Ids are UUIDs now, with a migration that recovers
                    orphaned rules into Default (left disabled, so nothing starts mocking traffic unexpectedly).</li>
                <li><strong>The Inspector could name the wrong mock rule.</strong> Two different matchers decided "was this
                    mocked" and "which mock applies"; the label now comes from the same matcher that answered the app.</li>
                <li><strong>Rules gained explicit match modes.</strong> Host and path can each be Exact, Wildcard or Regex
                    instead of being interpreted as a regex behind your back. Existing rules load as Exact — the behaviour
                    the applied path already had.</li>
                <li><strong>No more spurious "IDE internal error" balloons.</strong> Ordinary conditions — a device
                    unplugged, an app not installed — were reported to the IDE as plugin crashes.</li>
                <li><strong>Leaks and lifecycle.</strong> Closing a project left its registration, its Project reference and
                    a ddmlib listener alive for the rest of the session. The interceptor server now shuts down cleanly on
                    plugin unload, refuses to resurrect after disposal, bounds its connection threads, and times out a
                    request read so a vanished device cannot hold a slot forever.</li>
                <li><strong>Concurrency.</strong> The project registry and the rule store could throw
                    ConcurrentModificationException on the interceptor thread and drop a request under traffic.</li>
            </ul>

            <h3>Version 1.7.0 - Flutter on Physical Android Devices</h3>
            <ul>
                <li><strong>📲 Flutter apps are finally detected on real Android phones.</strong> Every one of the four detection methods was failing at once on physical hardware, so the app never appeared in the selector. All four are fixed.</li>
                <li><strong>🔍 APKs are now inspected ON the device:</strong> <code>unzip -p</code> + grep inflates only the entries that matter, so a 165&nbsp;MB app is checked in ~2 seconds with zero bytes over USB — instead of downloading the whole APK (and 1.6.2 skipped anything over 150&nbsp;MB entirely, which silently excluded most real Flutter debug builds).</li>
                <li><strong>🐛 Fixes native Android detection too:</strong> the old check grepped the raw APK for the interceptor class, but <code>classes.dex</code> is always DEFLATE-compressed, so that marker was never present as plain bytes. It now looks inside the decompressed dex.</li>
                <li><strong>🔌 The ADB reverse tunnel is opened before the scan</strong> (and on emulators too), so a running app can announce itself while the plugin looks for it. Previously it was only established after picking an app — which required detecting it first.</li>
                <li><strong>🎯 Smarter scan order:</strong> debuggable packages are identified with <code>run-as</code> (~50&nbsp;ms each) and checked first, so your app under development is never crowded out by Play Store apps.</li>
                <li><strong>☑️ New "Show all apps" checkbox:</strong> lists every third-party app with the detected ones flagged 🎭 and sorted first, so a detection miss is always recoverable — the same behaviour iOS already had.</li>
                <li><strong>⚠️ Honest warning about stale packages:</strong> if an app is detected but has never announced itself, the log explains that Flutter needs mockk_http &gt;= 1.7.0 on a physical device and how to test it in one line.</li>
                <li><strong>🔁 Stop no longer breaks the next scan:</strong> the reverse tunnel is left in place, so a running app keeps its route to the plugin.</li>
            </ul>
            <p><strong>Flutter users on a physical Android device must upgrade to <code>mockk_http: ^1.7.0</code></strong> — older versions dial <code>10.0.2.2</code>, an alias that only exists on an emulator, so they can be detected but never send traffic. 1.7.0 discovers the host instead of guessing it. Gradle plugin and Android library are unchanged at 1.6.1.</p>

            <h3>Version 1.6.2 - Physical Android Devices Fixed (Hotfix)</h3>
            <ul>
                <li><strong>📲 Physical Android devices work again:</strong> Scanning a USB-connected phone for MockkHttp apps used to saturate ADB — up to 10 concurrent shell connections plus a full APK download per package — which made the device drop offline, the scan return nothing and your app never show up in the app selector. Flutter apps on real hardware were effectively untestable.</li>
                <li><strong>🚦 USB-aware throttling:</strong> Physical devices now use 3 concurrent ADB connections instead of 10 (emulators keep the original parallelism over loopback).</li>
                <li><strong>📦 No more APK download storms:</strong> A cheap on-device probe rules out non-Flutter APKs before anything is transferred, oversized APKs are skipped, and each scan has a hard download budget — with slots reserved for the app you were last working with, so it is never crowded out.</li>
                <li><strong>🛑 The scan is cancellable:</strong> "Stop scan" now really aborts shell commands and APK transfers in flight, and the cancellation sticks instead of being relaunched by the next ADB event.</li>
                <li><strong>🔁 No more rescan loops:</strong> Device events are debounced and automatic rescans back off exponentially, so an unstable cable can no longer re-trigger the scan forever.</li>
                <li><strong>🎯 Your selection is remembered:</strong> Device and app selections survive disconnects and partial scans — a rescan can no longer silently repoint a running capture session at a different app, and a running session is always stoppable.</li>
                <li><strong>📊 Honest reporting:</strong> Timeouts and skipped checks are reported as inconclusive instead of silently reading as "this app has no MockkHttp", with progress shown per package.</li>
                <li><strong>🧠 Lower memory use:</strong> Flutter payloads are scanned as a stream instead of loading a whole 100&nbsp;MB kernel_blob.bin into the IDE heap.</li>
            </ul>
            <p><em>Gradle plugin, Android library and the mockk_http Flutter package are unchanged — keep using 1.6.1.</em></p>

            <h3>Version 1.6.1 - Error Response Presets & Redesigned Help</h3>
            <ul>
                <li><strong>🎯 Response Presets (Debug mode):</strong> One-click canned responses — 400, 401, 403, 404, 429, 500, 503, empty body and malformed JSON — fill the paused response instantly. Fully customizable: add, remove and edit presets from the new editor (gear button next to the presets row)</li>
                <li><strong>📚 Redesigned Help tab:</strong> Pick your platform (Android, or Flutter for Android &amp; iOS) and get a short 3-step setup guide instead of one long page</li>
                <li><strong>🛒 Accurate listing &amp; docs:</strong> Marketplace description, README and pub.dev README now cover Android + Flutter + iOS Simulators correctly (no more Android-only wording)</li>
                <li><strong>🔢 Version alignment:</strong> IntelliJ plugin, Gradle plugin, Android library and Flutter package all released as 1.6.1</li>
            </ul>

            <h3>Version 1.6.0 - iOS Support (Flutter), Cache Manager & Smarter Mocks</h3>
            <ul>
                <li><strong>🍎 iOS Simulators (Flutter):</strong> Booted simulators appear in the Device selector (apps listed via simctl) and Flutter apps using mockk_http 1.6.0 connect automatically — no proxy, no cert, no port forwarding. Recording/Debug/Mockk work exactly like on Android.</li>
                <li><strong>🍏 Physical iOS devices (best effort):</strong> Enumerated via devicectl (Xcode 15+); start the Flutter app with MockkHttp.init(host: 'your Mac LAN IP').</li>
                <li><strong>🧠 Cache & Memory manager:</strong> New Settings section with configurable limits (retained flows, stored body size), live usage display and a one-click clear. Fixes IDE slowdowns on long sessions (unbounded flow bodies and an O(n²) log buffer).</li>
                <li><strong>☑️ Easier mock management:</strong> Real checkboxes to enable/disable rules and collections with one click; tooltips name which other rules answer the same endpoint; a notification shows when enabling a rule auto-disables conflicting ones.</li>
                <li><strong>📥 Smart import:</strong> Importing a JSON whose collections already exist now offers a MERGE — missing rules are added, identical ones are never duplicated, and for changed rules you choose replace / keep both / skip.</li>
                <li><strong>🔄 Refresh Devices button:</strong> Manually refresh the combined Android + iOS device list.</li>
            </ul>

            <h3>Version 1.5.7 - API Modernization</h3>
            <ul>
                <li><strong>🧹 Zero deprecated APIs:</strong> Replaced the last deprecated Swing call (JTextComponent.modelToView → modelToView2D). The plugin now passes the JetBrains Plugin Verifier with no deprecated/legacy API usages across IntelliJ 2024.3 → 2026.x.</li>
            </ul>

            <h3>Version 1.5.6 - Mock Picker Fixes</h3>
            <ul>
                <li><strong>🎯 Endpoint-scoped:</strong> The Debug mock picker now lists only mocks that match the intercepted call — no mocks from other endpoints or collections</li>
                <li><strong>🧱 Layout fix:</strong> The Apply / Apply &amp; Send buttons are always visible (no horizontal scrolling); long mock names truncate instead</li>
                <li><strong>✅ Button feedback:</strong> The per-mock Apply / Apply &amp; Send buttons now flash a check when clicked</li>
                <li><strong>🗑️ Delete All Collections:</strong> New toolbar button to remove every collection and rule at once (handy after a duplicate import)</li>
            </ul>

            <h3>Version 1.5.5 - Copy Feedback & Quick Mock Apply</h3>
            <ul>
                <li><strong>📋 Copy Feedback:</strong> Every copy button now confirms the action with a green check and a brief "Copied!" label</li>
                <li><strong>🎭 Quick Mock Apply (Debug):</strong> When a call is paused in Debug mode, a list of saved mocks (grouped by collection) appears. Each row has two inline buttons — Apply (load into the response) and Apply &amp; Send (forward to the app instantly). Mocks matching the call are highlighted and listed first.</li>
            </ul>

            <h3>Version 1.5.4 - Request Bodies & Copy Buttons</h3>
            <ul>
                <li><strong>📦 Request Body Capture:</strong> POST/PUT request bodies are now captured and shown in the Inspector (read directly from the OkHttp application interceptor)</li>
                <li><strong>📋 Copy Buttons:</strong> Copy fragments (URL, headers, body) or the whole request/response/both, plus "Copy as cURL", from the flow details and Debug dialogs</li>
                <li><strong>🧩 Compatibility:</strong> Cleaned up plugin configuration flagged by JetBrains (open until-build, aligned since-build, platform-provided coroutines)</li>
            </ul>

            <h3>Version 1.5.3 - Mockk Mode Inspector Fix</h3>
            <ul>
                <li><strong>🐛 CRITICAL FIX:</strong> Calls in Mockk mode now appear in the Inspector list (Android library + Flutter package)</li>
                <li><strong>🔧 Root Cause:</strong> Interceptors were checking for mocks but never sending the resulting flow to the plugin</li>
                <li><strong>📲 Affects:</strong> Android (OkHttp), Flutter (HttpOverrides + dio interceptors)</li>
            </ul>

            <h3>Previous: Version 1.5.0 - Physical Device Support & AGP 9 Compatibility</h3>
            <ul>
                <li><strong>📲 Physical Devices:</strong> Full support for physical Android devices via automatic ADB reverse port forwarding</li>
                <li><strong>🔌 Auto Host Detection:</strong> Interceptor auto-detects emulator vs physical device and uses correct host address</li>
                <li><strong>🔧 AGP 9 Fix:</strong> Gradle plugin now compatible with AGP 9+ (changed bundled dependencies to compileOnly)</li>
                <li><strong>📱 Device Selector:</strong> UI now shows both emulators and physical devices with distinct icons</li>
            </ul>

            <h3>Previous: Version 1.4.36 - Complete Bytecode Clean</h3>
            <ul>
                <li><strong>🚀 PERFORMANCE FIX:</strong> Refresh buttons no longer block Android Studio UI</li>
                <li><strong>⚡ Background Operations:</strong> Emulator and app scanning now run in background threads</li>
            </ul>

            <h3>Previous: Version 1.4.33 - Binary Compatibility Fix</h3>
            <ul>
                <li><strong>🔧 CRITICAL FIX:</strong> Fixed binary incompatibility with IntelliJ IDEA 2024.1.x and 2024.2.x</li>
                <li><strong>📦 API Fix:</strong> Use reflection to call withExtensionFilter() only when available (2024.3+)</li>
                <li><strong>🎯 Full Compatibility:</strong> Works on IntelliJ 2024.1.x, 2024.2.x, 2024.3.x, and 2025.x</li>
            </ul>

            <h3>Previous: Version 1.4.32 - CI/CD Compatibility & API Fixes</h3>
            <ul>
                <li><strong>🔧 CI/CD Fix:</strong> Changed ADB-not-found messages from ERROR to WARN level</li>
                <li><strong>📦 API Update:</strong> Fixed deprecated FileSaverDescriptor constructor for IntelliJ 2024.3+ compatibility</li>
            </ul>

            <h3>Previous: Version 1.4.31 - Settings Tab & Improved ADB Detection</h3>
            <ul>
                <li><strong>⚙️ NEW: Settings Tab:</strong> Configure ADB path, Android SDK path, and plugin options</li>
                <li><strong>🔍 Improved ADB Detection:</strong> Enhanced auto-detection for macOS (Intel & Apple Silicon), Windows, and Linux</li>
                <li><strong>🍎 macOS Fixes:</strong> Reads ANDROID_HOME from shell profile, Android Studio preferences, Homebrew, and Spotlight</li>
                <li><strong>🚀 Startup Notification:</strong> Shows ADB status when project opens with "Open Settings" button if not found</li>
                <li><strong>💾 Persistent Settings:</strong> Manually configured paths are saved and used first before auto-detection</li>
                <li><strong>🔧 Path Validation:</strong> Real-time validation of configured paths with helpful error messages</li>
            </ul>

            <h3>Previous: Version 1.4.30 - IntelliJ Platform 2024.3 Compatibility</h3>
            <ul>
                <li><strong>🔧 CRITICAL FIX:</strong> Fixed FileSaverDescriptor API compatibility with IntelliJ IDEA 2024.3</li>
                <li><strong>✨ Platform Update:</strong> Now built against IntelliJ Platform 2024.3 for better compatibility</li>
                <li><strong>📦 Compatibility:</strong> Supports IntelliJ IDEA 2024.1+ (sinceBuild: 241)</li>
            </ul>

            <h3>Previous: Version 1.4.29 - BoxLayout LEFT_ALIGNMENT Fix</h3>
            <ul>
                <li><strong>🎯 CRITICAL FIX:</strong> Set alignmentX = LEFT_ALIGNMENT on ALL components in BoxLayout!</li>
                <li><strong>✨ True Left Alignment:</strong> Fixed BoxLayout default CENTER_ALIGNMENT causing indentation</li>
                <li><strong>📏 Swing Best Practices:</strong> Applied proper alignment to panels, labels, and scroll panes</li>
                <li><strong>🔧 Zero Indentation:</strong> All content now truly starts at left edge (no more centering)</li>
                <li><strong>💡 Technical:</strong> Every component in Y_AXIS BoxLayout now has LEFT_ALIGNMENT</li>
            </ul>

            <h3>Previous: Version 1.4.28 - Zero Padding - True Left Alignment</h3>
            <ul>
                <li><strong>🎯 PERFECT ALIGNMENT:</strong> All content now perfectly aligned to the left!</li>
                <li><strong>✨ Clean Design:</strong> Section titles and content start at the same position</li>
                <li><strong>📏 No Indentation:</strong> Removed left margin from all panels for perfect alignment</li>
                <li><strong>🔧 Visual Consistency:</strong> Everything lines up perfectly from left edge</li>
            </ul>

            <h3>Previous: Version 1.4.26 - Fixed Layout - Body Only in Textarea</h3>
            <ul>
                <li><strong>🔧 CRITICAL FIX:</strong> Only Body content is now in textarea - perfect alignment!</li>
                <li><strong>✨ Clean Layout:</strong> Flow info, headers, URLs are now normal text labels</li>
                <li><strong>📝 Body Textarea:</strong> Only request/response bodies use textarea (for JSON formatting)</li>
                <li><strong>🔍 Search Works:</strong> CMD+F searches in body textareas</li>
                <li><strong>🎨 Better Alignment:</strong> No more misaligned text boxes everywhere</li>
                <li><strong>💡 Headers as Labels:</strong> Headers displayed as clean list of labels</li>
            </ul>

            <h3>Previous: Version 1.4.25 - Simple & Clean Flow Details</h3>
            <ul>
                <li><strong>🎨 SIMPLIFIED UX:</strong> Removed collapsible sections - clean, flat layout</li>
                <li><strong>📝 JSON Pretty Print:</strong> Automatic JSON formatting for easy reading</li>
                <li><strong>🔍 CMD+F Search:</strong> Search across all content with keyboard shortcuts</li>
                <li><strong>✨ Clean Design:</strong> Section headers with separators for clarity</li>
                <li><strong>🎨 Color Coding:</strong> HTTP methods and status codes color-coded</li>
                <li><strong>📏 Simple Layout:</strong> No complex tree viewers - just clean text</li>
                <li><strong>⚡ Fast & Lightweight:</strong> Removed tree viewer for better performance</li>
            </ul>

            <h3>Previous: Version 1.4.23 - JSON Tree Viewer with Collapsible Nodes</h3>
            <ul>
                <li><strong>🌳 MAJOR FEATURE:</strong> JSON bodies now displayed as interactive tree with collapsible/expandable nodes!</li>
                <li><strong>📊 Tree Navigation:</strong> Each JSON object {} and array [] can be collapsed/expanded individually</li>
                <li><strong>🎨 Syntax Highlighting:</strong> Color-coded JSON types (strings=green, numbers=blue, booleans/null=orange)</li>
                <li><strong>🔍 Easy Exploration:</strong> Navigate complex nested JSON structures with ease</li>
                <li><strong>⚡ Auto-Detection:</strong> Automatically detects JSON content and switches to tree view</li>
                <li><strong>📝 Fallback:</strong> Non-JSON content still displays in plain text area</li>
                <li><strong>🎯 Icons:</strong> Visual icons for objects, arrays, and properties</li>
                <li><strong>💡 Smart UI:</strong> First level expanded by default for quick access</li>
            </ul>

            <h3>Previous: Version 1.4.22 - Modern Flow Details Dialog</h3>
            <ul>
                <li><strong>✨ MAJOR UX:</strong> Completely redesigned Flow Details dialog with collapsible sections</li>
                <li><strong>🎨 UI/UX:</strong> Color-coded HTTP methods (GET=green, POST=blue, PUT=orange, DELETE=red)</li>
                <li><strong>🎨 Status Codes:</strong> Color-coded status codes (2xx=green, 3xx=blue, 4xx=orange, 5xx=red)</li>
                <li><strong>📁 Collapsible Groups:</strong> Separate collapsible sections for headers and body (request & response)</li>
                <li><strong>📝 JSON Formatting:</strong> Automatic JSON pretty-printing with syntax highlighting</li>
                <li><strong>🔍 Search:</strong> Cmd+F/Ctrl+F to search across all sections with match navigation</li>
                <li><strong>🎯 Icons:</strong> Visual icons for each field (method, host, path, headers, body, etc.)</li>
                <li><strong>💻 Monospaced Fonts:</strong> Technical data displayed in monospaced font for readability</li>
                <li><strong>🏗️ Built with:</strong> Kotlin UI DSL v2 for modern, clean layout</li>
            </ul>

            <h3>Previous: Version 1.4.21 - Fixed Batch Create Duplicates</h3>
            <ul>
                <li><strong>🐛 CRITICAL FIX:</strong> Batch create from selection no longer creates duplicate mocks</li>
                <li><strong>✅ Fix:</strong> usedNames set now tracks names created in the same batch</li>
                <li><strong>📝 Example:</strong> Selecting 2 flows now creates 2 mocks (not 4)</li>
                <li><strong>🔧 Technical:</strong> Added usedNames.add(ruleName) after each successful creation</li>
            </ul>

            <h3>Previous: Version 1.4.20 - Debug Logging</h3>
            <ul>
                <li><strong>🔍 DEBUG:</strong> Added ultra-detailed logging to diagnose matching issues</li>
            </ul>

            <h3>Version 1.4.19 - Query Param Fix</h3>
            <ul>
                <li><strong>🐛 FIX:</strong> Query params now marked as required=true when creating mocks</li>
            </ul>

            <h3>Version 1.4.18 - Native Context Menus</h3>
            <ul>
                <li><strong>✨ UX:</strong> Context menus now use IntelliJ Platform native popups with hover effects</li>
                <li><strong>🎨 Theme Integration:</strong> Perfect dark/light theme support</li>
            </ul>

            <h3>Version 1.4.17 - Smart App Filtering</h3>
            <ul>
                <li><strong>🎭 MAJOR UX:</strong> Apps dropdown now shows ONLY apps with MockkHttp installed!</li>
                <li><strong>🔍 Auto-Detection:</strong> Plugin automatically scans APKs for MockkHttpInterceptor class</li>
            </ul>

            <h3>Version 1.4.16 - Perfect Mode Synchronization</h3>
            <ul>
                <li><strong>🔄 CRITICAL FIX:</strong> Interceptor now perfectly synced with IDE plugin mode changes</li>
                <li><strong>⚡ MOCKK_DEBUG Mode:</strong> Pre-checks for mock (no network), then opens dialog instantly</li>
            </ul>

            <h3>Setup (It's This Simple!)</h3>
            <ul>
                <li><strong>Step 1:</strong> Add <code>id("io.github.sergiydev09.mockkhttp") version "1.6.1"</code> to plugins block</li>
                <li><strong>Step 2:</strong> That's it! No repository configuration needed</li>
                <li><strong>⚠️ DO NOT:</strong> Add <code>debugImplementation</code> manually - the plugin does it for you!</li>
            </ul>

            <h3>Previous Version 1.4.3 - API Cleanup</h3>
            <ul>
                <li><strong>🔧 Fix:</strong> Replaced deprecated URL(String) constructor with URI.create().toURL()</li>
                <li><strong>🔧 Fix:</strong> Replaced deprecated Messages.showChooseDialog with Messages.showDialog</li>
            </ul>

            <h3>Version 1.4.2 - DI Support</h3>
            <ul>
                <li><strong>🔧 Fix:</strong> Interceptor works with Dependency Injection frameworks (Koin, Dagger, Hilt)</li>
                <li><strong>✅ Improved Detection:</strong> More robust bytecode transformation</li>
            </ul>

            <h3>Requirements:</h3>
            <ul>
                <li>Android SDK with platform-tools (ADB)</li>
                <li>Android emulator or physical device (API 21+)</li>
                <li>App must use OkHttp (Retrofit uses OkHttp internally)</li>
                <li><strong>Gradle plugin:</strong> <code>id("io.github.sergiydev09.mockkhttp") version "1.6.1"</code></li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    // The IntelliJ Platform Gradle Plugin already preconfigures `test` with the platform classpath,
    // the test sandbox and the system properties the framework needs. Everything below is only what
    // it leaves to the project.
    test {
        // Explicit, because getting it wrong is silent: the platform test classes are JUnit 3/4,
        // and useJUnitPlatform() here would run ZERO of them while still exiting successfully.
        useJUnit()

        // Loading the IntelliJ Platform into the test JVM does not fit Gradle's 512 MB default.
        maxHeapSize = "2g"

        // The fixtures build Swing components; a CI machine with no display needs this before the
        // AWT toolkit is touched, which happens long before any test body runs.
        systemProperty("java.awt.headless", "true")

        // The test IDE publishes an agent-discovery file with a live bearer token, and prunes its
        // peers. Neither may touch the developer's real ~/.mockkhttp (audit round 11, AR): every
        // path the agent channel writes is rooted here instead, for the whole JVM — including
        // refreshes that run after a test has already restored what it changed.
        systemProperty("mockkhttp.home", layout.buildDirectory.dir("mockkhttp-test-home").get().asFile.absolutePath)

        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }

        // Second half of the zero-test guard: the task ran, but executed nothing — a misconfigured
        // engine (useJUnitPlatform() against JUnit 4 tests) looks exactly like this and still exits 0.
        var executedTests = 0
        afterTest(closureOf<Any> { executedTests++ })
        doLast {
            if (executedTests == 0) {
                throw GradleException(
                    "The test task ran but executed ZERO tests — the JUnit engine is misconfigured. " +
                            "A passing build that verified nothing is not a pass."
                )
            }
        }
    }
}

/**
 * Generates `MockkHttpBuild.kt` with the plugin version Gradle already knows.
 *
 * The alternative is asking the platform at runtime, and every entry point for that —
 * `PluginManagerCore.getPlugin`, `PluginManager.getPluginByClass` — is marked
 * `@ApiStatus.Internal`, which the Marketplace verifier reports. The version is a build-time fact,
 * so baking it in is both simpler and immune to the platform reshuffling that API again.
 */
val generateBuildInfo by tasks.registering {
    group = "build"
    description = "Writes the plugin version into a generated Kotlin source file."

    val pluginVersion = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("pluginVersion", pluginVersion)
    outputs.dir(outputDir)

    doLast {
        val target = outputDir.get().asFile.resolve("com/sergiy/dev/mockkhttp")
        target.mkdirs()
        target.resolve("MockkHttpBuild.kt").writeText(
            """
            package com.sergiy.dev.mockkhttp

            // GENERATED by the `generateBuildInfo` Gradle task. Do not edit.
            object MockkHttpBuild {
                const val VERSION: String = "$pluginVersion"
            }

            """.trimIndent()
        )
    }
}

kotlin.sourceSets.main { kotlin.srcDir(generateBuildInfo) }

/**
 * First half of the zero-test guard, and the half that matters.
 *
 * With no test sources Gradle marks `:test` NO-SOURCE and skips it **entirely** — `doLast` never
 * runs, and the build is green having verified nothing. That is not hypothetical here: this repo
 * shipped for months with an empty `src/test` that was never in git, while stale classes from the
 * build cache produced phantom "35 tests" results. A dependency of `test` still runs when `test`
 * itself is skipped, which is what makes this catch the case the in-task check cannot.
 */
val verifyTestSourcesExist by tasks.registering {
    group = "verification"
    description = "Fails if the test source set is empty, which would silently skip the whole suite."

    val testSources = sourceSets.test.get().allSource.matching { include("**/*.kt", "**/*.java") }
    // Captured at configuration time so the check stays configuration-cache safe.
    val fileCount = providers.provider { testSources.files.size }

    doLast {
        if (fileCount.get() == 0) {
            throw GradleException(
                "src/test contains no test sources, so `test` would be skipped as NO-SOURCE and the " +
                        "build would pass without verifying anything. Restore the suite, or remove " +
                        "this guard deliberately."
            )
        }
    }
}

tasks.named("test") { dependsOn(verifyTestSourcesExist) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Include Python scripts in resources
sourceSets {
    main {
        resources {
            srcDir("src/main/python")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// MCP bridge (agent control plane — plan §6, decision D1-A)
//
// `:mcp-bridge` is a plain Kotlin/JVM module (Gson only, no IntelliJ Platform) that produces the
// stdio JSON-RPC bridge Claude Code launches. The plugin ships it as a resource and BridgeVendor
// copies it to ~/.mockkhttp/bin at runtime, so the jar MUST be rebuilt whenever the plugin is built:
// a stale copy in src/main/resources would be published to the Marketplace unnoticed.
//
// The subproject's task is referenced by PATH, not by TaskProvider: the root project is configured
// before :mcp-bridge, so project(":mcp-bridge").tasks would still be empty at this point.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
val vendorBridge by tasks.registering(Copy::class) {
    group = "build"
    description = "Builds the stdio MCP bridge fat jar and vendors it into the plugin resources."

    dependsOn(":mcp-bridge:fatJar")

    val bridgeJar = layout.projectDirectory.file("mcp-bridge/build/libs/mockkhttp-mcp.jar")
    from(bridgeJar)
    into(layout.projectDirectory.dir("src/main/resources/bridge"))

    // A Copy whose source does not exist is silently NO-SOURCE. Declaring the jar as an input turns
    // that into a loud failure instead of a plugin that ships without its bridge.
    inputs.file(bridgeJar).withPropertyName("bridgeJar")
}

// processResources consumes src/main/resources, which is exactly where vendorBridge writes, so the
// dependency has to be explicit or Gradle fails the build with an implicit-dependency error.
tasks.named("processResources") {
    dependsOn(vendorBridge)
}
