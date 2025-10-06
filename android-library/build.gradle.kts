plugins {
    id("com.android.library") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("maven-publish")
}

repositories {
    google()
    mavenCentral()
}

group = "com.sergiy.dev.mockkhttp"
version = "1.2.0"

android {
    namespace = "com.sergiy.dev.mockkhttp.interceptor"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.sergiy.dev.mockkhttp"
                artifactId = "android-interceptor"
                version = "1.2.0"
            }
        }
    }
}
