plugins {
    id("com.android.application")
}

android {
    compileSdk = Versions.compileSdk
    defaultConfig {
        multiDexEnabled = true
        versionCode = Versions.versionCode
        version = Versions.appVersion

        //testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))
        }
        // Coverage off for debug in this fork (2026-10-14). With it on, JaCoCo instruments every module's classes,
        // and OpenAPSAutoISFPlugin's invoke() body then grows past the JVM 64 KB method limit
        // (MethodTooLargeException in :app:mergeLibDexFullDebug). Release builds never instrument.
        named("debug") {
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
        }
    }

    compileOptions {
        sourceCompatibility = Versions.javaVersion
        targetCompatibility = Versions.javaVersion
    }

    lint {
        checkReleaseBuilds = false
        disable += "MissingTranslation"
        disable += "ExtraTranslation"
    }
}