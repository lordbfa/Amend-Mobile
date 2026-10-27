plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.amend.wear"
    compileSdk = 36

    defaultConfig {
        // Same app id as the phone app: Wear OS pairs them and Google Play ships them together.
        applicationId = "app.amend.messenger"
        minSdk = 30 // Wear OS 3+: Pixel Watch, Galaxy Watch4 and newer
        targetSdk = 36
        versionCode = 1_000_000 + (project.property("amend.versionCode") as String).toInt()
        versionName = project.property("amend.versionName") as String
    }

    signingConfigs {
        create("dev") {
            storeFile = file("../app/amend-dev.keystore")
            storePassword = "amenddev"
            keyAlias = "amend"
            keyPassword = "amenddev"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug" // pairs with the phone's debug build
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (project.findProperty("amend.unsigned") == "true") null else signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = System.getenv("CI") != null }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-navigation:1.4.1")
    implementation("androidx.wear:wear-input:1.1.0")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
