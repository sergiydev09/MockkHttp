package com.sergiy.dev.mockkhttp.settings

import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent state component for MockkHttp plugin settings.
 * Stores custom paths for mitmproxy executable and certificates directory.
 */
@State(
    name = "MockkHttpSettings",
    storages = [Storage("MockkHttpSettings.xml")],
    category = SettingsCategory.TOOLS
)
@Service
class MockkHttpSettingsState : PersistentStateComponent<MockkHttpSettingsState> {

    /**
     * Custom path to mitmproxy/mitmdump executable.
     * If null or empty, auto-detection will be used.
     */
    var customMitmproxyPath: String? = null

    /**
     * Custom path to mitmproxy certificates directory.
     * If null or empty, ~/.mitmproxy will be used.
     */
    var customCertificatesPath: String? = null

    override fun getState(): MockkHttpSettingsState = this

    override fun loadState(state: MockkHttpSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(): MockkHttpSettingsState {
            return service()
        }
    }
}
