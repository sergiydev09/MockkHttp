plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.sergiy.dev"
version = "1.1.0"

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
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
        }

        changeNotes = """
            <h3>Version 1.1.0 - UX Improvements & Auto-Configuration</h3>
            <ul>
                <li><strong>Improved UI Layout:</strong> Redesigned Inspector panel with horizontal mode buttons and clearer flow list</li>
                <li><strong>Visual Feedback:</strong> Mode buttons now show "Stop [Mode]" with pause icon when active</li>
                <li><strong>Config Tab:</strong> New in-app configuration panel (no need for separate Settings menu)</li>
                <li><strong>Auto-Detection:</strong> Automatically finds and configures mitmproxy executable path</li>
                <li><strong>Path Validation:</strong> Smart validation of mitmproxy executable and certificates directory</li>
                <li><strong>Automatic Certificate Installation:</strong> Certificates are now installed automatically when starting any mode</li>
                <li><strong>Better Error Handling:</strong> All mode buttons auto-deactivate on errors or device disconnection</li>
                <li><strong>Enhanced mitmproxy Detection:</strong> Supports Homebrew, pip, pipx installations with custom path override</li>
            </ul>

            <h3>Version 1.0.3 - Initial Release</h3>
            <ul>
                <li><strong>Three Operation Modes:</strong> Recording, Debug, and Mockk modes for flexible network interception</li>
                <li><strong>Automatic Certificate Setup:</strong> One-click mitmproxy certificate installation on Android emulators</li>
                <li><strong>Real-time Traffic Interception:</strong> Pause HTTP/HTTPS requests and modify responses before delivery</li>
                <li><strong>App-Level Filtering:</strong> iptables-based filtering to intercept only selected app traffic</li>
                <li><strong>Mock Rule Management:</strong> Create, save, and auto-apply mock responses</li>
                <li><strong>Comprehensive Logging:</strong> Unified logging panel with detailed operation tracking</li>
                <li><strong>Apple Silicon Support:</strong> Optimized for M1/M2/M3 Macs with ARM64 emulators</li>
            </ul>

            <h4>Requirements:</h4>
            <ul>
                <li>macOS (Apple Silicon or Intel)</li>
                <li>mitmproxy installed (Homebrew, pip, or pipx)</li>
                <li>Android emulator API 24+ (Google Api but not Playstore)</li>
                <li>App must trust user certificates (network_security_config.xml)</li>
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
