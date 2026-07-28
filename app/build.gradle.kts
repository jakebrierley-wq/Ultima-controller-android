plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jakebrierley.ultimacontroller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jakebrierley.ultimacontroller"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-import"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
