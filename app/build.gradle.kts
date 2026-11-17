plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Firebase (remote control). Safe to keep even if you never set Firebase up:
    // the app just runs offline until you add a real google-services.json + DB url.
    id("com.google.gms.google-services")
}

android {
    namespace = "com.moshe.flipmdm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moshe.flipmdm"
        minSdk = 30          // TCL Flip 2 runs Android 11
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // One BOM pins every Firebase library to a matching, tested version.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // Resolves a Google account's factory-reset-protection ID from its email.
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
