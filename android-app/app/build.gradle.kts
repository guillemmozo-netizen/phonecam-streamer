import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from android-app/keystore.properties (gitignored) or,
// for CI, the matching FRAMECAST_* environment variables — the keystore and its
// passwords must never be in the repo. Play refuses an unsigned artifact, so a
// release build without either of these is only good for local inspection; it
// still *builds* rather than failing configuration, so debug builds and unit
// tests keep working on a machine that has no keystore at all.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSetting(key: String, environmentVariable: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(environmentVariable)

val releaseStorePath = signingSetting("storeFile", "FRAMECAST_KEYSTORE_FILE")
val releaseStorePassword = signingSetting("storePassword", "FRAMECAST_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingSetting("keyAlias", "FRAMECAST_KEY_ALIAS")
val releaseKeyPassword = signingSetting("keyPassword", "FRAMECAST_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.framecast.streamer"
    // Several updated dependencies (androidx.activity, core-ktx, the
    // androidx.navigationevent transitive) now require compiling against
    // API 36+ — bumped only compileSdk, not targetSdk/minSdk: compileSdk
    // just controls which APIs are visible at build time, it doesn't
    // change the shipped app's runtime behavior the way targetSdk does
    // (Android's own recommendation: bump these independently).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.framecast.streamer"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.0.13-alpha"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off until there's a device to verify it on: the AdMob
            // and UMP SDKs and CameraX's reflective internals are exactly the
            // kind of code that needs keep rules worked out against a real
            // run, and a stripped release that only breaks on a user's phone
            // is worse than a larger APK. proguard-rules.pro is where those
            // rules go when it's turned on.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("com.google.android.material:material:1.14.0") // Material3 components
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Guava ListenableFuture — required by CameraX at compile time
    implementation("com.google.guava:guava:33.4.8-android")

    // CameraX: capture pipeline
    // 1.4+ needed for Preview.Builder.setDynamicRange (10-bit HLG HDR viewfinder)
    //
    // Pinned at 1.4.2, NOT bumped to the current 1.6.1 stable: CameraStreamer's
    // Surface-based capture path (StreamingVideoOutput, MainActivity's
    // VideoCapture.Builder wiring) depends on androidx.camera.core.impl.Observable
    // and androidx.camera.core.impl.utils.futures.Futures — internal, non-public
    // "impl" package APIs whose exact signatures were verified against this
    // specific version via javap against the real AAR (see git history/session
    // notes). A minor-version CameraX bump is exactly the kind of change that
    // can silently break internal APIs a public release makes no compatibility
    // promise about — needs its own dedicated bump + re-verification pass, not
    // bundled into a general dependency update.
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    // Rewarded ads. RewardManager depends only on the AdController interface,
    // not on this SDK directly.
    implementation("com.google.android.gms:play-services-ads:25.4.0")

    // GDPR/UMP consent gate, required before requesting ads for EEA/UK users.
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
