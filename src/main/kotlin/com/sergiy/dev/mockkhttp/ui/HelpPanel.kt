package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.net.URI
import javax.swing.*

/**
 * Extension function to convert Color to hex string.
 */
private fun Color.toHex(): String {
    return String.format("#%02x%02x%02x", red, green, blue)
}

/**
 * Help panel showing setup instructions for MockkHttp Interceptor.
 */
class HelpPanel(project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)

    init {
        logger.info("Initializing Help Panel...")

        border = JBUI.Borders.empty(15)

        // Get IDE theme colors
        val backgroundColor = UIUtil.getPanelBackground().toHex()
        val textColor = UIUtil.getLabelForeground().toHex()
        val linkColor = JBUI.CurrentTheme.Link.Foreground.ENABLED.toHex()
        val codeBackground = UIUtil.getTextFieldBackground().toHex()
        val borderColor = JBColor.border().toHex()
        val noteBackground = JBColor(0xFFF9E6, 0x4A4A3A).toHex()
        val noteBorder = JBColor(0xFFC107, 0x8B7500).toHex()

        val helpText = """
            <html>
            <head>
                <style>
                    body {
                        font-family: Dialog, sans-serif;
                        font-size: 12px;
                        color: $textColor;
                        background-color: $backgroundColor;
                        margin: 10px;
                    }
                    h1 {
                        color: $linkColor;
                        font-size: 16px;
                        font-weight: bold;
                        margin-top: 0;
                    }
                    h2 {
                        color: $linkColor;
                        font-size: 13px;
                        font-weight: bold;
                        margin-top: 20px;
                        margin-bottom: 8px;
                    }
                    code {
                        background-color: $codeBackground;
                        font-family: Monospaced;
                        font-size: 11px;
                    }
                    pre {
                        background-color: $codeBackground;
                        padding: 10px;
                        border: 1px solid $borderColor;
                        font-family: Monospaced;
                        font-size: 11px;
                    }
                    .step {
                        margin: 8px 0;
                    }
                    .note {
                        background-color: $noteBackground;
                        padding: 10px;
                        border: 1px solid $noteBorder;
                        margin: 12px 0;
                    }
                    p {
                        margin: 6px 0;
                    }
                    ul, ol {
                        margin: 6px 0;
                        padding-left: 20px;
                    }
                    li {
                        margin: 4px 0;
                    }
                    a {
                        color: $linkColor;
                        text-decoration: none;
                    }
                    a:hover {
                        text-decoration: underline;
                    }
                </style>
            </head>
            <body>
                <h1>🚀 MockkHttp Setup Guide</h1>

                <div class="note">
                    <strong>⚠️ IMPORTANT - Version 1.4.2 Update Required</strong>
                    <p>
                        This version (1.4.2) includes automatic dependency injection - <strong>no need to add dependencies manually!</strong>
                        Just apply the Gradle plugin and it handles everything.
                    </p>
                    <p>
                        Features: Koin/Dagger DI support, Android 16KB page size support, AGP 8.7.3 compatibility.
                    </p>
                </div>

                <p>
                    MockkHttp uses an OkHttp Interceptor to capture network traffic from your Android app.
                    Follow these steps to integrate it into your project.
                </p>

                <h2>📦 Step 1: Add the Gradle Plugin</h2>

                <div class="step">
                    <p><strong>In your app's <code>build.gradle.kts</code>:</strong></p>
                    <pre>plugins {
    id("com.android.application")
    kotlin("android")
    id("io.github.sergiydev09.mockkhttp") version "1.4.2"
}

// That's it! No dependencies needed.
// The plugin automatically handles everything.</pre>
                    <p><em>The plugin automatically adds the interceptor dependency and injects it into all OkHttpClient instances.</em></p>
                    <p><strong>⚠️ Do NOT add the dependency manually - the plugin does it for you!</strong></p>
                </div>

                <div class="note">
                    <strong>🔒 SECURITY - Multiple Protection Layers:</strong>
                    <ul>
                        <li><strong>Build-time Check:</strong> Gradle plugin fails the build if MockkHttp is in <code>implementation</code> or <code>releaseImplementation</code></li>
                        <li><strong>Bytecode Skip:</strong> Plugin automatically skips release variants</li>
                        <li><strong>Runtime Check:</strong> Interceptor verifies <code>BuildConfig.DEBUG</code> and disables itself in release builds</li>
                        <li><strong>ProGuard/R8:</strong> Strip rules remove any traces in release APKs</li>
                    </ul>
                    <p><strong>Result:</strong> MockkHttp is IMPOSSIBLE to include in production builds, even if you try!</p>
                </div>

                <h2>✅ Step 2: Build Your App</h2>

                <div class="step">
                    <p>Build and run your app in <strong>Debug mode</strong> on an emulator:</p>
                    <pre>./gradlew assembleDebug
./gradlew installDebug</pre>
                </div>

                <h2>📱 Step 3: Start Intercepting</h2>

                <div class="step">
                    <ol>
                        <li>Select your emulator from the dropdown</li>
                        <li>Select your app from the dropdown</li>
                        <li>Click <strong>"Start Interceptor"</strong></li>
                        <li>Launch your app and make network requests</li>
                        <li>View captured traffic in the Inspector tab</li>
                    </ol>
                </div>

                <h2>🔍 How It Works</h2>

                <div class="step">
                    <p>
                        The Gradle plugin automatically injects the MockkHttp interceptor into all OkHttpClient
                        instances in your app during the build process. The interceptor:
                    </p>
                    <ul>
                        <li>Captures HTTP requests and responses</li>
                        <li>Sends them to this IntelliJ plugin via socket connection</li>
                        <li>Only active in Debug builds (no impact on Release)</li>
                        <li>Falls back gracefully if plugin is not running</li>
                    </ul>
                </div>

                <h2>❓ Troubleshooting</h2>

                <div class="step">
                    <p><strong>No flows appearing?</strong></p>
                    <ul>
                        <li>Make sure the Gradle plugin is applied in your <code>build.gradle.kts</code></li>
                        <li>Rebuild your app: <code>./gradlew clean assembleDebug</code></li>
                        <li>Check that your app is using OkHttp (Retrofit uses OkHttp internally)</li>
                        <li>Verify the plugin server is running (green status)</li>
                        <li>Check Logs tab for connection messages</li>
                    </ul>
                </div>

                <div class="step">
                    <p><strong>App crashes or network errors?</strong></p>
                    <ul>
                        <li>Make sure you're using the latest version of the interceptor</li>
                        <li>Check that the emulator can reach host machine (10.0.2.2)</li>
                        <li>Verify port 9876 is not blocked by firewall</li>
                    </ul>
                </div>

                <h2>📚 Resources</h2>

                <div class="step">
                    <ul>
                        <li><a href="https://github.com/sergiydev09/MockkHttp">GitHub Repository</a></li>
                        <li><a href="https://github.com/sergiydev09/MockkHttp/issues">Report Issues</a></li>
                        <li><a href="https://github.com/sergiydev09/MockkHttp/wiki">Documentation</a></li>
                    </ul>
                </div>

                <br/><br/>
                <p style="color: #6c757d; font-size: 12px;">
                    MockkHttp v1.4.1 | Fully Automatic Setup | Built for Android Development
                </p>
            </body>
            </html>
        """.trimIndent()

        val textPane = JEditorPane("text/html", helpText).apply {
            isEditable = false
            isOpaque = false

            // Enable hyperlink clicking
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    try {
                        Desktop.getDesktop().browse(URI(e.url.toString()))
                    } catch (ex: Exception) {
                        logger.error("Failed to open URL: ${e.url}", ex)
                    }
                }
            }
        }

        val scrollPane = JBScrollPane(textPane).apply {
            border = JBUI.Borders.empty()
        }

        add(scrollPane, BorderLayout.CENTER)

        logger.info("✅ Help Panel initialized")
    }
}
