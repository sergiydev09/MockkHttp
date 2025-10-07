plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.sergiy.dev"
version = "1.3.0"

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
            <h3>Version 1.2.8 - Interceptor Architecture & Security Hardening</h3>
            <ul>
                <li><strong>🚀 New Architecture:</strong> Switched from mitmproxy to OkHttp Interceptor for simpler, more reliable operation</li>
                <li><strong>⚡ Zero Configuration:</strong> No proxy setup, no certificates, no iptables - just add Gradle plugin and go!</li>
                <li><strong>🔒 Production Safety:</strong> 4-layer security system prevents accidental inclusion in release builds</li>
                <li><strong>💉 Automatic Injection:</strong> Gradle plugin automatically injects interceptor via bytecode transformation</li>
                <li><strong>🐛 Debug Mode Enhanced:</strong> Visual indicator for modified responses with [DEBUG: Modified] tag in cyan</li>
                <li><strong>🍎 Cross-Platform:</strong> Works on macOS, Windows, and Linux (no longer Mac-only)</li>
                <li><strong>📱 API 21+ Support:</strong> Compatible with Android 5.0+ emulators</li>
                <li><strong>🎨 Theme Support:</strong> Help panel now adapts to IDE light/dark themes</li>
            </ul>

            <h3>Breaking Changes:</h3>
            <ul>
                <li>Removed mitmproxy dependency - no longer required!</li>
                <li>Removed certificate installation step - no longer needed!</li>
                <li>New Gradle plugin ID: <code>io.github.sergiydev09.mockkhttp</code></li>
                <li>Setup now requires adding Gradle plugin to your app</li>
            </ul>

            <h3>Version 1.0.3 - Initial Release (Proxy Architecture)</h3>
            <ul>
                <li><strong>Three Operation Modes:</strong> Recording, Debug, and Mockk modes</li>
                <li><strong>mitmproxy Integration:</strong> Python addon for traffic interception</li>
                <li><strong>Certificate Management:</strong> Automatic mitmproxy CA certificate installation</li>
                <li><strong>App-Level Filtering:</strong> iptables-based traffic filtering</li>
                <li><strong>Mock Rule Management:</strong> Create and apply mock responses</li>
            </ul>

            <h4>Requirements:</h4>
            <ul>
                <li>Android SDK with platform-tools (ADB)</li>
                <li>Android emulator API 21+</li>
                <li>App must use OkHttp (Retrofit uses OkHttp internally)</li>
                <li>Add Gradle plugin: <code>id("io.github.sergiydev09.mockkhttp") version "1.2.0"</code></li>
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
