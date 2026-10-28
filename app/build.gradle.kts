plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI passes these; local builds fall back to dev values.
val redlineVersionName: String = (findProperty("redline.versionName") as String?) ?: "0.0.0-dev"
val redlineVersionCode: Int = ((findProperty("redline.versionCode") as String?) ?: "1").toInt()

android {
    namespace = "com.redline.camera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.redline.camera"
        minSdk = 29
        targetSdk = 37
        versionCode = redlineVersionCode
        versionName = redlineVersionName
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("REDLINE_KEYSTORE_PATH")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("REDLINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REDLINE_KEY_ALIAS")
                keyPassword = System.getenv("REDLINE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = System.getenv("REDLINE_KEYSTORE_PATH")
            signingConfig = if (ks != null && file(ks).exists()) {
                signingConfigs.getByName("release")
            } else {
                // No release key in this environment: sign with the debug key so the APK installs.
                signingConfigs.getByName("debug")
            }
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
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    testImplementation(libs.junit)
}
