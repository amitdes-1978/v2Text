plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.v2text.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.v2text.app"
        minSdk = 33          // Android 13+: needed for feeding our own audio to the system recognizer
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        ndk { abiFilters += listOf("arm64-v8a") }   // Galaxy A52s (Snapdragon 778G) is arm64
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }

    buildTypes {
        debug {
            // whisper.cpp is unusably slow at -O0; build native code optimised even in debug
            externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
        }
        release {
            isMinifyEnabled = false
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads its Android runtime jar; use Google's Maven Central mirror (Central rate-limits)
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
