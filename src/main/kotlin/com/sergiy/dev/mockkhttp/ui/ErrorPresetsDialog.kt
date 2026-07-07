package com.sergiy.dev.mockkhttp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sergiy.dev.mockkhttp.store.ErrorPresetsStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

/**
 * Editor for the error-response presets shown in the Debug intercept dialog.
 * Works on an in-memory copy; nothing is persisted until OK.
 */
class ErrorPresetsDialog(project: Project) : DialogWrapper(project) {

    private val presets: MutableList<ErrorPresetsStore.ErrorPreset> =
        ErrorPresetsStore.getInstance().getPresets().map { it.copy() }.toMutableList()

    private val listModel = DefaultListModel<ErrorPresetsStore.ErrorPreset>()
    private val presetList = JBList(listModel)

    private val nameField = JBTextField()
    private val statusCodeField = JBTextField()
    private val headersTextArea = JTextArea(5, 40)
    private val bodyTextArea = JTextArea(10, 40)

    /** Index of the preset currently loaded in the form, -1 when none. */
    private var editedIndex = -1

    /** Guards against selection-listener reentry while the form is being (re)loaded. */
    private var updatingForm = false

    init {
        title = "Edit Response Presets"

        headersTextArea.font = Font("Monospaced", Font.PLAIN, 12)
        bodyTextArea.font = Font("Monospaced", Font.PLAIN, 12)

        presets.forEach { listModel.addElement(it) }

        presetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        presetList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is ErrorPresetsStore.ErrorPreset) {
                    text = "${value.name}  →  ${value.statusCode}"
                }
                return this
            }
        }
        presetList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !updatingForm) {
                commitForm()
                loadForm(presetList.selectedIndex)
            }
        }

        init()

        if (listModel.size() > 0) {
            presetList.selectedIndex = 0
        } else {
            loadForm(-1)
        }
    }

    override fun createCenterPanel(): JComponent {
        val listPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Presets")
            add(JBScrollPane(presetList), BorderLayout.CENTER)
            add(createListToolbar(), BorderLayout.SOUTH)
            preferredSize = Dimension(220, 450)
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, createFormPanel()).apply {
            resizeWeight = 0.3
            dividerLocation = 220
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(760, 480)
            add(splitPane, BorderLayout.CENTER)
        }
    }

    private fun createListToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        toolbar.add(JButton("Add", AllIcons.General.Add).apply {
            toolTipText = "Add a new preset"
            addActionListener { addPreset() }
        })
        toolbar.add(JButton("Remove", AllIcons.General.Remove).apply {
            toolTipText = "Remove the selected preset"
            addActionListener { removeSelectedPreset() }
        })
        toolbar.add(JButton("Restore Defaults").apply {
            toolTipText = "Replace all presets with the built-in defaults"
            addActionListener { restoreDefaults() }
        })
        return toolbar
    }

    private fun createFormPanel(): JComponent {
        val topRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(4)
            add(JLabel("Name: "))
            add(nameField)
            add(Box.createHorizontalStrut(10))
            add(JLabel("Status code: "))
            add(statusCodeField.apply {
                maximumSize = Dimension(80, preferredSize.height)
                preferredSize = Dimension(80, preferredSize.height)
            })
        }

        val headersPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Headers (one per line: Key: Value)")
            add(JBScrollPane(headersTextArea), BorderLayout.CENTER)
        }
        val bodyPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Body")
            add(JBScrollPane(bodyTextArea), BorderLayout.CENTER)
        }
        val editorSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, headersPanel, bodyPanel).apply {
            resizeWeight = 0.3
        }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Preset")
            add(topRow, BorderLayout.NORTH)
            add(editorSplit, BorderLayout.CENTER)
        }
    }

    // ========== Form <-> model ==========

    private fun loadForm(index: Int) {
        updatingForm = true
        try {
            editedIndex = index
            val preset = presets.getOrNull(index)
            val enabled = preset != null
            nameField.isEnabled = enabled
            statusCodeField.isEnabled = enabled
            headersTextArea.isEnabled = enabled
            bodyTextArea.isEnabled = enabled

            nameField.text = preset?.name ?: ""
            statusCodeField.text = preset?.statusCode?.toString() ?: ""
            headersTextArea.text = preset?.headers ?: ""
            bodyTextArea.text = preset?.body ?: ""
        } finally {
            updatingForm = false
        }
    }

    /** Write the form values back into the preset being edited (no-op when none). */
    private fun commitForm() {
        val preset = presets.getOrNull(editedIndex) ?: return
        preset.name = nameField.text.trim()
        preset.statusCode = statusCodeField.text.trim().toIntOrNull() ?: preset.statusCode
        preset.headers = headersTextArea.text
        preset.body = bodyTextArea.text
        listModel.set(editedIndex, preset)
    }

    private fun addPreset() {
        commitForm()
        // Unique name: the Debug dialog resolves presets by name at click time
        val base = "New Preset"
        var candidate = base
        var n = 2
        while (presets.any { it.name.equals(candidate, ignoreCase = true) }) {
            candidate = "$base ${n++}"
        }
        val preset = ErrorPresetsStore.ErrorPreset(name = candidate, statusCode = 500)
        presets.add(preset)
        listModel.addElement(preset)
        presetList.selectedIndex = listModel.size() - 1
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun removeSelectedPreset() {
        val index = presetList.selectedIndex
        if (index < 0) return
        // Skip commitForm(): the edited preset is the one being deleted.
        editedIndex = -1
        presets.removeAt(index)
        listModel.remove(index)
        if (listModel.size() > 0) {
            presetList.selectedIndex = index.coerceAtMost(listModel.size() - 1)
        } else {
            loadForm(-1)
        }
    }

    private fun restoreDefaults() {
        val answer = Messages.showYesNoDialog(
            contentPane,
            "Replace all presets with the built-in defaults?",
            "Restore Defaults",
            Messages.getQuestionIcon()
        )
        if (answer != Messages.YES) return

        editedIndex = -1
        presets.clear()
        listModel.clear()
        ErrorPresetsStore.defaultPresets().forEach {
            presets.add(it)
            listModel.addElement(it)
        }
        if (listModel.size() > 0) {
            presetList.selectedIndex = 0
        }
    }

    // ========== Validation & persistence ==========

    override fun doValidate(): ValidationInfo? {
        if (editedIndex >= 0) {
            if (nameField.text.isBlank()) {
                return ValidationInfo("Preset name cannot be empty", nameField)
            }
            val status = statusCodeField.text.trim().toIntOrNull()
            if (status == null || status !in 100..599) {
                return ValidationInfo("Status code must be a number between 100 and 599", statusCodeField)
            }
            firstMalformedHeaderLine(headersTextArea.text)?.let {
                return ValidationInfo("Header lines must be \"Key: Value\" — invalid line: \"$it\"", headersTextArea)
            }
        }

        // The form only shows one preset, but selection changes commit form values without
        // validation — so the rest of the working list must be validated here too.
        presets.forEachIndexed { i, preset ->
            if (i == editedIndex) return@forEachIndexed
            if (preset.name.isBlank()) {
                return ValidationInfo("Preset #${i + 1} has an empty name", presetList)
            }
            if (preset.statusCode !in 100..599) {
                return ValidationInfo("Preset '${preset.name}' has an invalid status code (${preset.statusCode})", presetList)
            }
            firstMalformedHeaderLine(preset.headers)?.let {
                return ValidationInfo("Preset '${preset.name}' has an invalid header line: \"$it\"", presetList)
            }
        }

        // The Debug dialog resolves presets by name at click time, so names must be unique
        val seen = mutableSetOf<String>()
        currentNames().forEach { name ->
            if (!seen.add(name.lowercase())) {
                return ValidationInfo("Duplicate preset name: \"$name\"", presetList)
            }
        }
        return null
    }

    /** Preset names as they would be after committing the form. */
    private fun currentNames(): List<String> =
        presets.mapIndexed { i, preset -> if (i == editedIndex) nameField.text.trim() else preset.name }

    /** First non-blank header line missing the "Key: Value" colon, or null when all are valid. */
    private fun firstMalformedHeaderLine(text: String): String? =
        text.lines().firstOrNull { it.isNotBlank() && !it.contains(':') }

    override fun doOKAction() {
        commitForm()
        ErrorPresetsStore.getInstance().setPresets(presets)
        super.doOKAction()
    }

    override fun doCancelAction() {
        commitForm()
        if (presets != ErrorPresetsStore.getInstance().getPresets()) {
            val answer = Messages.showYesNoDialog(
                contentPane,
                "Discard changes to presets?",
                "Unsaved Changes",
                Messages.getQuestionIcon()
            )
            if (answer != Messages.YES) return
        }
        super.doCancelAction()
    }
}
