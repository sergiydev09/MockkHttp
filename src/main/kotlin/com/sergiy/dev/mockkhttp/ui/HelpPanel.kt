package com.sergiy.dev.mockkhttp.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sergiy.dev.mockkhttp.logging.MockkHttpLogger
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.net.URI
import javax.swing.ButtonGroup
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * Extension function to convert Color to hex string.
 */
private fun Color.toHex(): String {
    return String.format("#%02x%02x%02x", red, green, blue)
}

/**
 * Help panel with a platform selector: pick Android (native) or Flutter (Android & iOS)
 * and get a short 3-step setup guide for that platform.
 */
class HelpPanel(project: Project) : JPanel(BorderLayout()) {

    private val logger = MockkHttpLogger.getInstance(project)

    companion object {
        // Versions shown in the setup snippets — keep in sync on releases (see VERSION_FILES.md)
        private const val GRADLE_PLUGIN_VERSION = "1.6.1"
        private const val FLUTTER_PACKAGE_VERSION = "1.6.1"

        private const val CARD_ANDROID = "android"
        private const val CARD_FLUTTER = "flutter"

        private val FOOTER_HTML = """
            <p style="margin-top: 16px;">
                <a href="https://github.com/sergiydev09/MockkHttp">GitHub</a> ·
                <a href="https://github.com/sergiydev09/MockkHttp/issues">Report an issue</a>
            </p>
        """.trimIndent()
    }

    init {
        logger.info("Initializing Help Panel...")

        border = JBUI.Borders.empty(10)

        val cardLayout = CardLayout()
        val cards = JPanel(cardLayout).apply {
            add(createHelpCard(androidGuideHtml()), CARD_ANDROID)
            add(createHelpCard(flutterGuideHtml()), CARD_FLUTTER)
        }

        val androidButton = JToggleButton("🤖 Android", true)
        val flutterButton = JToggleButton("🐦 Flutter (Android & iOS)")
        ButtonGroup().apply {
            add(androidButton)
            add(flutterButton)
        }
        androidButton.addActionListener { cardLayout.show(cards, CARD_ANDROID) }
        flutterButton.addActionListener { cardLayout.show(cards, CARD_FLUTTER) }

        val selectorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JLabel("My app is:"))
            add(androidButton)
            add(flutterButton)
        }

        add(selectorPanel, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)

        logger.info("✅ Help Panel initialized")
    }

    private fun createHelpCard(html: String): JBScrollPane {
        val textPane = JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = false
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    try {
                        Desktop.getDesktop().browse(URI(e.url.toString()))
                    } catch (ex: Exception) {
                        logger.error("Failed to open URL: ${e.url}", ex)
                    }
                }
            }
            caretPosition = 0
        }
        return JBScrollPane(textPane).apply {
            border = JBUI.Borders.empty()
        }
    }

    // ========== HTML content ==========

    /** Wrap guide content with theme-aware styling shared by both platform cards. */
    private fun htmlPage(content: String): String {
        val backgroundColor = UIUtil.getPanelBackground().toHex()
        val textColor = UIUtil.getLabelForeground().toHex()
        val linkColor = JBUI.CurrentTheme.Link.Foreground.ENABLED.toHex()
        val codeBackground = UIUtil.getTextFieldBackground().toHex()
        val borderColor = JBColor.border().toHex()
        val noteBackground = JBColor(0xFFF9E6, 0x4A4A3A).toHex()
        val noteBorder = JBColor(0xFFC107, 0x8B7500).toHex()

        return """
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
                    h2 {
                        color: $linkColor;
                        font-size: 13px;
                        font-weight: bold;
                        margin-top: 16px;
                        margin-bottom: 6px;
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
                    .note {
                        background-color: $noteBackground;
                        padding: 8px;
                        border: 1px solid $noteBorder;
                        margin: 10px 0;
                    }
                    p { margin: 6px 0; }
                    ul { margin: 6px 0; padding-left: 20px; }
                    li { margin: 3px 0; }
                    a { color: $linkColor; text-decoration: none; }
                </style>
            </head>
            <body>
            $content
            $FOOTER_HTML
            </body>
            </html>
        """.trimIndent()
    }

    private fun androidGuideHtml(): String = htmlPage(
        """
        <h2>1 · Add the Gradle plugin</h2>
        <p>In your app's <code>build.gradle.kts</code>:</p>
        <pre>plugins {
    id("com.android.application")
    kotlin("android")
    id("io.github.sergiydev09.mockkhttp") version "$GRADLE_PLUGIN_VERSION"
}</pre>
        <p>That's it — no dependencies to declare. The plugin injects the interceptor into every
        <code>OkHttpClient</code> (Retrofit included) in <strong>debug builds only</strong>;
        release builds are protected by build-time, bytecode, runtime and R8 checks.</p>

        <h2>2 · Run your app</h2>
        <p>Build and launch a <strong>debug</strong> build on an emulator or physical device
        (port forwarding for physical devices is set up automatically).</p>

        <h2>3 · Start intercepting</h2>
        <p>In the <strong>Inspector</strong> tab: pick your device and app, choose a mode
        (Recording / Debug / Mockk) and press <strong>Start</strong>. Traffic appears live.</p>

        <div class="note">
            <strong>No flows?</strong> Rebuild with <code>./gradlew clean assembleDebug</code>,
            make sure the app uses OkHttp, and check the <strong>Logs</strong> tab.
        </div>
        """.trimIndent()
    )

    private fun flutterGuideHtml(): String = htmlPage(
        """
        <h2>1 · Add the package</h2>
        <p>In your <code>pubspec.yaml</code>:</p>
        <pre>dependencies:
  mockk_http: ^$FLUTTER_PACKAGE_VERSION</pre>

        <h2>2 · Initialize</h2>
        <p>In <code>main()</code>, wrapped in <code>assert</code> so it is compiled out of release builds:</p>
        <pre>import 'package:mockk_http/mockk_http.dart';

void main() {
  assert(() {
    MockkHttp.init(); // HttpClient, package:http, and friends
    return true;
  }());
  runApp(MyApp());
}</pre>
        <p>Using <strong>dio</strong>? Add its interceptor instead:
        <code>dio.interceptors.add(MockkHttpDioInterceptor())</code></p>

        <h2>3 · Run it &amp; start intercepting</h2>
        <ul>
            <li><strong>Android emulator</strong> and <strong>iOS Simulator</strong>: zero config — just run the app</li>
            <li><strong>Physical iPhone</strong>: <code>MockkHttp.init(host: '&lt;your Mac's LAN IP&gt;')</code>
                + <code>NSLocalNetworkUsageDescription</code> in <code>Info.plist</code> (same Wi-Fi)</li>
            <li><strong>Physical Android</strong>: <code>adb reverse tcp:9876 tcp:9876</code>
                + <code>MockkHttp.init(host: '127.0.0.1')</code></li>
        </ul>
        <p>Then in the <strong>Inspector</strong> tab: pick your device and app, choose a mode
        (Recording / Debug / Mockk) and press <strong>Start</strong>.</p>

        <div class="note">
            <strong>No flows?</strong> Launch order doesn't matter (the app retries every 15s), but make
            sure <code>MockkHttp.init()</code> runs before the first request and check the <strong>Logs</strong> tab.
        </div>
        """.trimIndent()
    )
}
