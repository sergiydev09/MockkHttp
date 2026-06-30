package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.model.HttpFlowData
import java.awt.datatransfer.StringSelection
import javax.swing.JButton

/**
 * Shared helpers for copying flow data (or fragments of it) to the system clipboard,
 * plus small reusable copy buttons used across the flow visualizations.
 *
 * Copy granularity ("desde fragmentos hasta request/response/ambos"):
 *  - Fragments: URL, request/response headers, request/response body
 *  - Whole request / whole response / both together
 *  - cURL command to reproduce the request
 */
object FlowCopyUtils {

    /**
     * Copy [text] to the system clipboard using the platform-sanctioned API.
     */
    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /**
     * Whole request as text: request line + headers + body.
     */
    fun buildRequestText(flow: HttpFlowData): String {
        val request = flow.request
        return buildString {
            append("${request.method} ${request.url}")
            if (request.headers.isNotEmpty()) {
                append("\n\n")
                append(formatHeaders(request.headers))
            }
            if (request.content.isNotEmpty()) {
                append("\n\n")
                append(request.content)
            }
        }
    }

    /**
     * Whole response as text: status line + headers + body.
     * Returns an empty string when there is no response yet.
     */
    fun buildResponseText(flow: HttpFlowData): String {
        val response = flow.response ?: return ""
        return buildResponseText(
            statusLine = "${response.statusCode} ${response.reason}".trim(),
            headers = formatHeaders(response.headers),
            body = response.content
        )
    }

    /**
     * Whole response as text from raw parts. Used by the Debug dialog where the
     * response is edited live in separate fields.
     */
    fun buildResponseText(statusLine: String, headers: String, body: String): String {
        return buildString {
            append(statusLine.trim())
            if (headers.isNotBlank()) {
                append("\n\n")
                append(headers.trim())
            }
            if (body.isNotEmpty()) {
                append("\n\n")
                append(body)
            }
        }
    }

    /**
     * Request and response together, with clear separators.
     */
    fun buildFullFlowText(flow: HttpFlowData): String {
        return buildString {
            append("===== REQUEST =====\n")
            append(buildRequestText(flow))
            append("\n\n===== RESPONSE =====\n")
            append(if (flow.response != null) buildResponseText(flow) else "(no response)")
        }
    }

    /**
     * A cURL command that reproduces the request (method, URL, headers and body).
     */
    fun buildCurl(flow: HttpFlowData): String {
        val request = flow.request
        return buildString {
            append("curl -X ${request.method} ")
            append(shellQuote(request.url))
            request.headers.forEach { (key, value) ->
                append(" \\\n  -H ")
                append(shellQuote("$key: $value"))
            }
            // Only emit the body when it is the real captured payload. Synthetic markers
            // (binary / too-large placeholders) must NOT be sent literally as --data-raw.
            if (request.content.isNotEmpty() && !isPlaceholderBody(request.content)) {
                append(" \\\n  --data-raw ")
                append(shellQuote(request.content))
            }
        }
    }

    /**
     * Detects the synthetic body markers emitted by the interceptor when the real bytes
     * cannot be captured (binary content or a body over the size cap).
     */
    private fun isPlaceholderBody(content: String): Boolean =
        content.startsWith("<binary body") || content.startsWith("<body too large")

    /**
     * Format a header map as one `Key: Value` per line.
     */
    fun formatHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    /**
     * POSIX-safe single-quoting for shell arguments (handles embedded single quotes).
     */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * A labeled copy button (icon + text) for toolbars.
     */
    fun createCopyButton(label: String, tooltip: String, supplier: () -> String): JButton =
        JButton(label, AllIcons.Actions.Copy).apply {
            toolTipText = tooltip
            isFocusable = false
            addActionListener { copyToClipboard(supplier()) }
        }

    /**
     * A compact, icon-only copy button for inline fragment rows (next to a header/body label).
     */
    fun createCompactCopyButton(tooltip: String, supplier: () -> String): JButton =
        JButton(AllIcons.Actions.Copy).apply {
            toolTipText = tooltip
            isFocusable = false
            margin = JBUI.insets(2)
            addActionListener { copyToClipboard(supplier()) }
        }
}
