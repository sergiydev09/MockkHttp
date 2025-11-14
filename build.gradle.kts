plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.sergiy.dev"
version = "1.4.17"

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
            <h3>Version 1.4.17 - Smart App Filtering</h3>
            <ul>
                <li><strong>🎭 MAJOR UX:</strong> Apps dropdown now shows ONLY apps with MockkHttp installed!</li>
                <li><strong>🔍 Auto-Detection:</strong> Plugin automatically scans APKs for MockkHttpInterceptor class</li>
                <li><strong>✨ Visual Indicator:</strong> MockkHttp-enabled apps marked with 🎭 emoji</li>
                <li><strong>📝 Clear Logging:</strong> Shows exactly how many MockkHttp apps found vs total</li>
            </ul>

            <h3>Previous: Version 1.4.16 - Perfect Mode Synchronization</h3>
            <ul>
                <li><strong>🔄 CRITICAL FIX:</strong> Interceptor now perfectly synced with IDE plugin mode changes</li>
                <li><strong>⚡ MOCKK_DEBUG Mode:</strong> Pre-checks for mock (no network), then opens dialog instantly</li>
            </ul>

            <h3>Setup (It's This Simple!)</h3>
            <ul>
                <li><strong>Step 1:</strong> Add <code>id("io.github.sergiydev09.mockkhttp") version "1.4.17"</code> to plugins block</li>
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
                <li><strong>Gradle plugin:</strong> <code>id("io.github.sergiydev09.mockkhttp") version "1.4.17"</code></li>
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
