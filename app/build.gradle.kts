plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.amend.messenger"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.amend.messenger"
        minSdk = 26
        targetSdk = 36
        // one version for phone and watch: see gradle.properties
        versionCode = (project.property("amend.versionCode") as String).toInt()
        versionName = project.property("amend.versionName") as String
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // A shared development key so every CI build installs over the previous one.
        // It is public in this repository — use your own key for store releases.
        create("dev") {
            storeFile = file("amend-dev.keystore")
            storePassword = "amenddev"
            keyAlias = "amend"
            keyPassword = "amenddev"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Bundle native debug symbols (for WebRTC / secp256k1) into the AAB so Play can
            // symbolicate native crashes and ANRs. Uploaded to Play only, never shipped to users.
            ndk { debugSymbolLevel = "FULL" }
            // -Pamend.unsigned=true builds an unsigned release (the Play Store bundle from CI),
            // which the owner signs on their own computer with the private upload key.
            signingConfig = if (project.findProperty("amend.unsigned") == "true") null else signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // The release lint check is heavy; on a laptop it runs at the same time as R8 and can
        // exhaust memory. CI (GitHub Actions sets CI=true) still runs it on every build.
        checkReleaseBuilds = System.getenv("CI") != null
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
        // Store native .so uncompressed and page-aligned so the app installs and runs on
        // devices with 16 KB memory pages (Android 15+). AGP 8.5.1+ then aligns them to 16 KB.
        jniLibs.useLegacyPackaging = false
    }
    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            // The live relay test only runs when explicitly requested (CI step).
            it.systemProperty("amend.liveRelays", System.getProperty("amend.liveRelays") ?: "false")
            it.systemProperty("amend.relayScan", System.getProperty("amend.relayScan") ?: "false")
            it.systemProperty("amend.blobScan", System.getProperty("amend.blobScan") ?: "false")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    // on-device translation (models download once from Google, text never leaves the phone)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // shrinks gallery videos to 720p before they are encrypted and sent
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // installs the Baseline Profile (ours + Compose's) so code is pre-compiled: no first-scroll jank
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Wear OS companion: sync with the amend watch app
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // Nearby pulls in an old androidx.fragment; activity-result APIs need >= 1.3
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("io.getstream:stream-webrtc-android:1.3.7")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
