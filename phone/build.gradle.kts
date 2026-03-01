import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.rokid.hud.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokid.hud.phone"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ROKID_CLIENT_ID",
            "\"${localProps.getProperty("rokid.client.id", "")}\"")
        buildConfigField("String", "ROKID_CLIENT_SECRET",
            "\"${localProps.getProperty("rokid.client.secret", "")}\"")
        buildConfigField("String", "ROKID_ACCESS_KEY",
            "\"${localProps.getProperty("rokid.access.key", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Rokid CXR-M SDK for glasses connection and device control
    implementation("com.rokid.cxr:client-m:1.0.4")

    // CXR SDK required transitive dependencies
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}
