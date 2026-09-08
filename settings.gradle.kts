pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "MockkHttp"

include(":gradle-plugin")
include(":android-library")

// Stdio MCP bridge (agent control plane, plan D1-A). Plain Kotlin/JVM: it runs OUTSIDE the IDE as a
// `java -jar` process launched by the MCP client, so it must never apply the IntelliJ Platform plugin.
include(":mcp-bridge")
