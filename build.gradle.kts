plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("com.android.library") version "8.12.3" apply false
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.sergiy.dev"
version = "1.5.6"

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
}

intellijPlatform {
    publishing {
        token.set(providers.gradleProperty("intellijPublishToken"))
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
                <li><strong>Step 1:</strong> Add <code>id("io.github.sergiydev09.mockkhttp") version "1.5.4"</code> to plugins block</li>
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
                <li><strong>Gradle plugin:</strong> <code>id("io.github.sergiydev09.mockkhttp") version "1.5.4"</code></li>
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
}

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
