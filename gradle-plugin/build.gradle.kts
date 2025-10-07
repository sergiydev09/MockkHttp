plugins {
    `kotlin-dsl`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.2.1"
}

group = "io.github.sergiydev09.mockkhttp"
version = "1.3.0"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.7.3")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    implementation("org.ow2.asm:asm-tree:9.6")
}

gradlePlugin {
    website = "https://github.com/sergiydev09/MockkHttp"
    vcsUrl = "https://github.com/sergiydev09/MockkHttp"

    plugins {
        create("mockkhttp") {
            id = "io.github.sergiydev09.mockkhttp"
            implementationClass = "com.sergiy.dev.mockkhttp.gradle.MockkHttpGradlePlugin"
            displayName = "MockkHttp Gradle Plugin"
            description = "Automatically inject network interceptor for MockkHttp IntelliJ plugin. Intercept and modify HTTP traffic from Android apps in debug builds only."
            tags = listOf("android", "okhttp", "network", "debugging", "interceptor", "http", "testing")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}
