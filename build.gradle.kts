plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.sergiy.dev"
version = "1.4.0"

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
            <h3>Version 1.4.0 - Android 16KB Page Size Support & Gradle Plugin Improvements</h3>
            <ul>
                <li><strong>📱 Android 16KB Page Size:</strong> Full support for devices with 16KB page size (Android 15+)</li>
                <li><strong>🔧 Gradle Plugin Auto-Injection:</strong> Automatically injects android-library dependency (no manual setup needed)</li>
                <li><strong>⚡ AGP 8.7.3:</strong> Updated to latest Android Gradle Plugin for better compatibility</li>
                <li><strong>🐛 JitPack Fixes:</strong> Improved multi-module publishing and groupId consistency</li>
                <li><strong>✨ Code Cleanup:</strong> Removed unused dependencies and improved code quality</li>
            </ul>

            <h3>Important: Update Gradle Plugin</h3>
            <ul>
                <li><strong>⚠️ REQUIRED:</strong> Update Gradle plugin to version <code>1.4.0</code> in your app</li>
                <li>New version includes automatic dependency injection and 16KB page size support</li>
                <li>Compatible with Android 15+ and devices using 16KB memory pages</li>
            </ul>

            <h3>Previous Version 1.3.9 - Multi-Project Support</h3>
            <ul>
                <li><strong>🎯 Multi-Project Support:</strong> Multiple Android Studio projects can run simultaneously</li>
                <li><strong>📦 Package Name Filtering:</strong> Strict project isolation with automatic flow routing</li>
                <li><strong>🌐 GlobalOkHttpInterceptorServer:</strong> Application-level server routes flows correctly</li>
            </ul>

            <h3>Version 1.2.8 - Interceptor Architecture</h3>
            <ul>
                <li><strong>🚀 New Architecture:</strong> Switched from mitmproxy to OkHttp Interceptor</li>
                <li><strong>⚡ Zero Configuration:</strong> No proxy setup, no certificates needed</li>
                <li><strong>🔒 Production Safety:</strong> 4-layer security system prevents release inclusion</li>
                <li><strong>💉 Automatic Injection:</strong> Gradle plugin auto-injects interceptor</li>
            </ul>

            <h3>Requirements:</h3>
            <ul>
                <li>Android SDK with platform-tools (ADB)</li>
                <li>Android emulator or physical device (API 21+)</li>
                <li>App must use OkHttp (Retrofit uses OkHttp internally)</li>
                <li><strong>Gradle plugin:</strong> <code>id("io.github.sergiydev09.mockkhttp") version "1.4.0"</code></li>
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
