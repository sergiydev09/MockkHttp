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

            // Don't add dependency automatically - user must add it manually
            // This prevents version mismatch issues with JitPack
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
}
