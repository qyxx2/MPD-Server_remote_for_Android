import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appDisplayName = "MPD-Server Remote"
val signingPropertiesFile = file("${System.getProperty("user.home")}/.config/mpd-server-remote/signing.properties")
if (!signingPropertiesFile.isFile) {
    throw GradleException(
        "Unified installer signing properties are missing at ${signingPropertiesFile.path}. " +
            "Provision the user-local signing configuration; refusing to fall back to the default debug key."
    )
}
val signingProperties = Properties().apply {
    signingPropertiesFile.inputStream().use(::load)
}
val requiredSigningKeys = listOf("storeFile", "keyAlias", "storePassword", "keyPassword")
val missingSigningKeys = requiredSigningKeys.filter { signingProperties.getProperty(it).isNullOrBlank() }
if (missingSigningKeys.isNotEmpty()) {
    throw GradleException("Unified installer signing configuration is incomplete: ${missingSigningKeys.joinToString()}")
}
val signingStoreFile = file(signingProperties.getProperty("storeFile"))
if (!signingStoreFile.isFile) {
    throw GradleException("Unified installer keystore does not exist at ${signingStoreFile.path}")
}

android {
    namespace = "io.github.qyxx2.mpdserver.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.qyxx2.mpdserver.remote"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "app_name", appDisplayName)
        buildConfigField("String", "APP_DISPLAY_NAME", "\"$appDisplayName\"")
    }

    signingConfigs {
        create("internal") {
            storeFile = signingStoreFile
            storeType = "JKS"
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("internal")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("internal")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
}
