import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonecam.streamer"
    // Several updated dependencies (androidx.activity, core-ktx, the
    // androidx.navigationevent transitive) now require compiling against
    // API 36+ — bumped only compileSdk, not targetSdk/minSdk: compileSdk
    // just controls which APIs are visible at build time, it doesn't
    // change the shipped app's runtime behavior the way targetSdk does
    // (Android's own recommendation: bump these independently).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phonecam.streamer"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.0.13-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
