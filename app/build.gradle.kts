plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jakebrierley.ultimacontroller"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.jakebrierley.ultimacontroller"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1-video-bootstrap"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            ndkBuild {
                arguments += "NDK_APPLICATION_MK:=src/main/jni/Application.mk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
