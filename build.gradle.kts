plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.sergiy.dev"
version = "1.3.9"

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
            <h3>Version 1.3.9 - Multi-Project Support & Major Cleanup</h3>
            <ul>
                <li><strong>🎯 Multi-Project Support:</strong> Multiple Android Studio projects can now run simultaneously without conflicts</li>
                <li><strong>📦 Package Name Filtering:</strong> Each project only receives flows from its selected app (strict isolation)</li>
                <li><strong>🌐 GlobalOkHttpInterceptorServer:</strong> Application-level server routes flows to correct project automatically</li>
                <li><strong>🔧 ADB Multi-Project Fix:</strong> Handles ADB initialization when multiple projects are open</li>
                <li><strong>🧹 Code Cleanup:</strong> Removed all legacy mitmproxy, certificate, and root access code</li>
                <li><strong>📱 Physical Device Support:</strong> Now works on both emulators and physical devices (API 21+)</li>
                <li><strong>⚡ Improved Stability:</strong> Better error handling and thread-safe flow routing</li>
            </ul>

            <h3>Important: Update Android Library</h3>
            <ul>
                <li><strong>⚠️ REQUIRED:</strong> Update Gradle plugin to version <code>1.3.9</code> in your app</li>
                <li>New library includes <code>packageName</code> field for proper flow routing</li>
                <li>Without update, flows may not be routed correctly in multi-project setups</li>
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
                <li><strong>Gradle plugin:</strong> <code>id("io.github.sergiydev09.mockkhttp") version "1.3.9"</code></li>
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
