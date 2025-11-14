plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.sergiy.dev"
version = "1.4.29"

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
        create("IC", "2025.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    
    // ADB/ddmlib for emulator communication (standalone, no Android plugin needed)
    implementation("com.android.tools.ddms:ddmlib:31.7.2")
    
    // HTTP client for mitmproxy communication
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

intellijPlatform {
    publishing {
        token.set(providers.gradleProperty("intellijPublishToken"))
    }

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
        }

        changeNotes = """
            <h3>Version 1.4.29 - BoxLayout LEFT_ALIGNMENT Fix</h3>
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
                <li><strong>Step 1:</strong> Add <code>id("io.github.sergiydev09.mockkhttp") version "1.4.19"</code> to plugins block</li>
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
                <li><strong>Gradle plugin:</strong> <code>id("io.github.sergiydev09.mockkhttp") version "1.4.19"</code></li>
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
