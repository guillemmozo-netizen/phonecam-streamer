import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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
        // The applicationId predates the FrameCast rename and CANNOT change:
        // it is the installed app's identity — a new id would be a different
        // app that existing installs never update to.
        applicationId = "com.phonecam.streamer"
        minSdk = 26
        targetSdk = 34
        // 15, not 14: lets the public 0.0.14 install cleanly over the
        // rc1 builds already on testers' phones.
        versionCode = 15
        versionName = "0.0.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing from an untracked keystore.properties next to this
    // module's parent (android-app/). Falls back to an UNSIGNED release build
    // on machines without it (CI, collaborators) rather than failing.
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties()
        keystoreProps.inputStream().use { stream -> props.load(stream) }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Deliberately NOT minified for this launch: R8 has never been
            // exercised against the CameraX internal-API usage this app
            // depends on (see the camera-core pin below), and turning it on
            // untested the night before a release trades a smaller APK for
            // an unknown. Revisit with its own validation pass.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        // CameraX "impl" internal APIs are used deliberately and are pinned
        // to the exact version they were verified against (see the
        // camera-core dependency comment) — the pin is what manages that
        // risk, so RestrictedApi is a known condition to keep visible in
        // reports, not an error that should break the build.
        informational += "RestrictedApi"
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

    // On-device instrumentation, added for CameraDiagnosticsDeviceTest: the
    // Camera2 probe is only meaningful against a real HAL, and org.json on the
    // JVM is a stand-in that cannot tell you whether *this* phone answers.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
