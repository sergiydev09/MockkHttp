// The stdio MCP bridge: a self-contained fat JAR that Claude Code (or any MCP client) launches with
// the JBR that Android Studio already ships. It talks JSON-RPC 2.0 on stdin/stdout and HTTP/1.1 to
// the plugin's loopback control plane.
//
// This module deliberately does NOT apply `org.jetbrains.intellij.platform`: the bridge runs in its
// own process, outside the IDE classloader, and must not see a single IntelliJ class. Keeping MCP
// out of the plugin is what lets the MCP spec churn (the 2026-07-28 revision deleted the initialize
// handshake) cost a jar rebuild instead of a Marketplace review.
plugins {
    // No version here on purpose: the root project's plugins block already resolves
    // org.jetbrains.kotlin.jvm 2.1.21 onto the build classpath for every subproject.
    id("org.jetbrains.kotlin.jvm")
}

group = "com.sergiy.dev"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    // gradle.properties sets kotlin.stdlib.default.dependency=false for the WHOLE build, because the
    // IntelliJ Platform provides the stdlib to the plugin module. The bridge has no platform under
    // it, so it has to ask for the stdlib by hand or the fat jar would not start anywhere.
    // Keep the version in sync with the Kotlin plugin version in the root build.gradle.kts.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")

    // The one and only third-party dependency, same version as the plugin so both ends of the REST
    // contract are parsed by identical code.
    implementation("com.google.code.gson:gson:2.13.2")

    // Tests only — none of this reaches the fat jar, which is built from the main source set and the
    // RUNTIME classpath. kotlin-test-junit5 is named explicitly rather than left to the framework
    // auto-selection of plain `kotlin("test")`, so the assertion library cannot silently resolve to
    // the JUnit 4 variant on a machine where the test task is configured differently.
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.21")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.21")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    // Gradle 8.13 needs the launcher on the test runtime classpath when the engine is declared here.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()

    // McpProtocolTest starts the bridge as a real `java -cp … MainKt` child, so the tests need a
    // predictable environment: never the developer's own ~/.mockkhttp, whose live IDE would make
    // "no instance is running" assertions pass or fail depending on who runs them.
    environment("MOCKKHTTP_HOME", layout.buildDirectory.dir("test-mockkhttp-home").get().asFile.absolutePath)
    // Discovery reads these three before anything else; a value inherited from the developer's shell
    // would bypass the very resolution the tests exercise.
    environment("MOCKKHTTP_BASE_URL", "")
    environment("MOCKKHTTP_TOKEN", "")
    environment("MOCKKHTTP_PROJECT", "")

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

// Fat jar: the client starts us with `java -jar mockkhttp-mcp.jar`, so every class we need has to be
// inside — there is no classpath to inherit.
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the standalone stdio MCP bridge jar (bridge classes + Kotlin stdlib + Gson)."

    archiveFileName.set("mockkhttp-mcp.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    // The Jar task contributes its own META-INF/MANIFEST.MF first, so EXCLUDE keeps ours and drops
    // the manifests of the dependencies we unpack.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            mapOf(
                "Main-Class" to "com.sergiy.dev.mockkhttp.bridge.MainKt",
                "Implementation-Title" to "mockkhttp-mcp",
                // Read back at runtime for the X-MockkHttp-Client header and serverInfo.version.
                "Implementation-Version" to project.version.toString()
            )
        )
    }

    from(sourceSets.main.get().output)

    // Resolved eagerly (this block only runs when the task is realised): handing the copy spec plain
    // FileTree objects keeps the configuration cache able to serialise the task, which a lambda
    // calling Project.zipTree at execution time would not.
    from(configurations.runtimeClasspath.get().filter { it.isFile && it.name.endsWith(".jar") }.map { zipTree(it) })

    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/INDEX.LIST",
        // Multi-release module descriptors are meaningless on the classpath and confuse jar tooling.
        "META-INF/versions/*/module-info.class",
        "module-info.class"
    )
}

// `:mcp-bridge:build` must produce the artifact the plugin vendors, not just the thin jar.
tasks.named("assemble") {
    dependsOn(fatJar)
}
