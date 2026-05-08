plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = project.findProperty("RELEASE_STORE_FILE") as String?
val releaseStorePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
val releaseKeyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
val releaseKeyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?

val hasCompleteReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.skylarkin.evfinder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.skylarkin.evfinder"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "0.27"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val ocmApiKey = (project.findProperty("OPEN_CHARGE_MAP_API_KEY") as String?)
            ?: (project.findProperty("\uFEFFOPEN_CHARGE_MAP_API_KEY") as String?)
            ?: (project.findProperty("ï»¿OPEN_CHARGE_MAP_API_KEY") as String?)
            ?: (project.findProperty("OPEN_CHARGE_MAP_API") as String?)
            ?: ""
        val chargetripClientId = (project.findProperty("CHARGETRIP_CLIENT_ID") as String?) ?: ""
        val chargetripAppId = (project.findProperty("CHARGETRIP_APP_ID") as String?) ?: ""
        val chargetripAppIdentifier = (project.findProperty("CHARGETRIP_APP_IDENTIFIER") as String?)
            ?: applicationId
        val chargetripAppFingerprint = (project.findProperty("CHARGETRIP_APP_FINGERPRINT") as String?)
            ?: (project.findProperty("CHARGETRIP_ANDROID_SHA256") as String?)
            ?: ""
        buildConfigField("String", "OPEN_CHARGE_MAP_API_KEY", "\"$ocmApiKey\"")
        buildConfigField("String", "CHARGETRIP_CLIENT_ID", "\"$chargetripClientId\"")
        buildConfigField("String", "CHARGETRIP_APP_ID", "\"$chargetripAppId\"")
        buildConfigField("String", "CHARGETRIP_APP_IDENTIFIER", "\"$chargetripAppIdentifier\"")
        buildConfigField("String", "CHARGETRIP_APP_FINGERPRINT", "\"$chargetripAppFingerprint\"")
    }

    signingConfigs {
        create("release") {
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
            }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

android.applicationVariants.all {
    val variantName = name
    outputs.all {
        @Suppress("UNCHECKED_CAST")
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "Skylarkin-$variantName.apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
