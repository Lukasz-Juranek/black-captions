import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// sherpa-onnx (runs the accurate GigaAM model) isn't on Maven, so its release .aar is fetched
// once into libs/ and checked against a known hash.
val sherpaAar = file("libs/sherpa-onnx-1.13.8.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL()
        .openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
}
val sherpaHash = MessageDigest.getInstance("SHA-256").digest(sherpaAar.readBytes())
    .joinToString("") { "%02x".format(it) }
check(sherpaHash == "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96") {
    sherpaAar.delete()
    "sherpa-onnx .aar hash mismatch"
}

// Release signing comes from CI secrets; without them the debug key is used.
val signingStore = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.lj.blackcaptions"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lj.blackcaptions"
        // Capturing other apps' audio (AudioPlaybackCapture) needs Android 10.
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // Vosk and ML Kit ship ~25 MB of native code per CPU type; phones only need the ARM ones.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = signingStore
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "player"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // Offline speech recognition. JNA is listed separately because Vosk needs its .aar, not the .jar.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // Accurate offline recognition for finished phrases.
    implementation(files(sherpaAar))
    // On-device translation.
    implementation("com.google.mlkit:translate:17.0.3")
}
