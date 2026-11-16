import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.opentokenusage.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.opentokenusage.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"

        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        val agyClientId = providers.gradleProperty("antigravityOAuthClientId")
            .orElse(providers.environmentVariable("ANTIGRAVITY_OAUTH_CLIENT_ID"))
            .getOrElse(localProps.getProperty("antigravityOAuthClientId", ""))
        val agyClientSecret = providers.gradleProperty("antigravityOAuthClientSecret")
            .orElse(providers.environmentVariable("ANTIGRAVITY_OAUTH_CLIENT_SECRET"))
            .getOrElse(localProps.getProperty("antigravityOAuthClientSecret", ""))
        buildConfigField("String", "ANTIGRAVITY_OAUTH_CLIENT_ID", "\"${agyClientId.replace("\"", "\\\"")}\"")
        buildConfigField("String", "ANTIGRAVITY_OAUTH_CLIENT_SECRET", "\"${agyClientSecret.replace("\"", "\\\"")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
