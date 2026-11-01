plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.mytube"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mytube"
        minSdk = 26
        targetSdk = 36
        // Each CI build gets a higher number, so Android treats it as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        // 1.0.<build number>, so every build from CI has its own version.
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Signing comes from CI secrets (MYTUBE_KEYSTORE_PATH etc.) so no key lives in the repo.
    // Without them, builds fall back to the standard debug key.
    val keystorePath = System.getenv("MYTUBE_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("sideload") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MYTUBE_STORE_PASSWORD")
                keyAlias = System.getenv("MYTUBE_KEY_ALIAS") ?: "mytube"
                keyPassword = System.getenv("MYTUBE_KEY_PASSWORD")
            }
        }
    }
    val signing = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signing
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signing
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    dependenciesInfo {
        // No Google-encrypted dependency metadata in the APK.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.media3.common.util.UnstableApi",
        )
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    // Precompiles Compose and the app on install, since sideloaded apps miss Play Store cloud profiles.
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.newpipe.extractor)
}
