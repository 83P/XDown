plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.xmediadownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.xmediadownloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../app /src/main/java")
            res.srcDirs("src/main/res", "../app /src/main/res")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("io.github.junkfood02.youtubedl-android:library:0.15.0")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.15.0")
}
