package com.sergiy.dev.mockkhttp.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that automatically injects MockkHttpInterceptor into OkHttpClient instances.
 *
 * Usage:
 * ```kotlin
 * plugins {
 *     id("com.sergiy.dev.mockkhttp")
 * }
 * ```
 */
class MockkHttpGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Get Android extension
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: throw IllegalStateException(
                "MockkHttp plugin requires Android Gradle Plugin. " +
                        "Apply 'com.android.application' or 'com.android.library' first."
            )

        // Extract and publish bundled AAR to local Maven repository
        val aarFile = extractBundledAar(project)
        publishAarToLocalMaven(project, aarFile)

        // Add android-library dependency automatically (debug builds only)
        project.afterEvaluate {
            // Check if user accidentally used 'implementation' instead of 'debugImplementation'
            val configurations = project.configurations
            val implementationConfig = configurations.findByName("implementation")
            val releaseConfig = configurations.findByName("releaseImplementation")

            // Check if MockkHttp is in wrong configuration
            implementationConfig?.dependencies?.forEach { dep ->
                if (dep.group == "com.sergiy.dev.mockkhttp" || dep.name.contains("mockk-http")) {
                    throw IllegalStateException(
                        """
                        ❌ SECURITY ERROR: MockkHttp detected in 'implementation' configuration!

                        MockkHttp should NEVER be included in release builds as it:
                        • Logs all network traffic (security risk)
                        • Exposes sensitive data
                        • Adds unnecessary overhead

                        SOLUTION: Change to 'debugImplementation':

                        dependencies {
                            debugImplementation("com.sergiy.dev.mockkhttp:android-interceptor:1.0.0")
                        }

                        Build aborted for security reasons.
                        """.trimIndent()
                    )
                }
            }

            releaseConfig?.dependencies?.forEach { dep ->
                if (dep.group == "com.sergiy.dev.mockkhttp" || dep.name.contains("mockk-http")) {
                    throw IllegalStateException(
                        """
                        ❌ SECURITY ERROR: MockkHttp detected in 'releaseImplementation' configuration!

                        MockkHttp should NEVER be included in release builds.

                        SOLUTION: Use 'debugImplementation' instead:

                        dependencies {
                            debugImplementation("com.sergiy.dev.mockkhttp:android-interceptor:1.0.0")
                        }

                        Build aborted for security reasons.
                        """.trimIndent()
                    )
                }
            }

            // Automatically add android-library dependency for debug builds
            val pluginVersion = "1.4.9" // Must match gradle-plugin version
            project.dependencies.add(
                "debugImplementation",
                "com.sergiy.dev.mockkhttp:mockk-http-interceptor:$pluginVersion"
            )
        }

        // Register bytecode transformation ONLY for debug builds
        androidComponents.onVariants { variant ->
            val buildType = variant.buildType

            // SECURITY: Silently skip release builds
            if (buildType == "release") {
                return@onVariants
            }

            // Only inject on debug builds
            if (buildType == "debug") {
                variant.instrumentation.transformClassesWith(
                    OkHttpInterceptorTransform::class.java,
                    InstrumentationScope.ALL
                ) { _ ->
                    // No parameters needed
                }

                variant.instrumentation.setAsmFramesComputationMode(
                    FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
                )
            }
        }
    }

    private fun extractBundledAar(project: Project): java.io.File {
        val aarResourcePath = "/aar/mockk-http-interceptor.aar"
        val aarInputStream = javaClass.getResourceAsStream(aarResourcePath)
            ?: throw IllegalStateException("Bundled AAR not found in plugin resources: $aarResourcePath")

        val cacheDir = java.io.File(project.gradle.gradleUserHomeDir, "caches/mockk-http/1.4.9")
        cacheDir.mkdirs()

        val aarFile = java.io.File(cacheDir, "mockk-http-interceptor.aar")

        // Only extract if not already cached
        if (!aarFile.exists()) {
            aarInputStream.use { input ->
                aarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return aarFile
    }

    private fun publishAarToLocalMaven(project: Project, aarFile: java.io.File) {
        val groupId = "com.sergiy.dev.mockkhttp"
        val artifactId = "mockk-http-interceptor"
        val version = "1.4.9"

        val localRepo = java.io.File(project.gradle.gradleUserHomeDir, "caches/mockk-http-maven")

        // Add local repository
        project.repositories.maven {
            name = "MockkHttpLocal"
            url = project.uri(localRepo)
        }

        // Create POM file
        val groupPath = groupId.replace('.', '/')
        val artifactDir = java.io.File(localRepo, "$groupPath/$artifactId/$version")
        artifactDir.mkdirs()

        val pomFile = java.io.File(artifactDir, "$artifactId-$version.pom")
        if (!pomFile.exists()) {
            pomFile.writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>$groupId</groupId>
                    <artifactId>$artifactId</artifactId>
                    <version>$version</version>
                    <packaging>aar</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>com.squareup.okhttp3</groupId>
                            <artifactId>okhttp</artifactId>
                            <version>4.12.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.code.gson</groupId>
                            <artifactId>gson</artifactId>
                            <version>2.10.1</version>
                        </dependency>
                        <dependency>
                            <groupId>org.jetbrains.kotlinx</groupId>
                            <artifactId>kotlinx-coroutines-android</artifactId>
                            <version>1.9.0</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent())
        }

        // Copy AAR to local repository
        val targetAar = java.io.File(artifactDir, "$artifactId-$version.aar")
        if (!targetAar.exists()) {
            aarFile.copyTo(targetAar, overwrite = true)
        }
    }
}
