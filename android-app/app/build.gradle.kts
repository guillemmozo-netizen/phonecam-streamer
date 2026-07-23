import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonecam.streamer"
    compileSdk = 34

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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0") // Material3 components
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Guava ListenableFuture — required by CameraX at compile time
    implementation("com.google.guava:guava:32.1.3-android")

    // CameraX: capture pipeline
    // 1.4+ needed for Preview.Builder.setDynamicRange (10-bit HLG HDR viewfinder)
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")

    // Rewarded ads. RewardManager depends only on the AdController interface,
    // not on this SDK directly.
    implementation("com.google.android.gms:play-services-ads:23.2.0")

    // GDPR/UMP consent gate, required before requesting ads for EEA/UK users.
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
