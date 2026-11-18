plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.verbo.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.verbo.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.24.2"
        // Real phones only: the offline-translation library is ~17 MB per processor type, and x86 is for emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        // The release build is not debuggable, which is what makes typing smooth; debug builds run much slower.
        // It is signed with the debug key for now so it can be installed directly. Use a real key before publishing.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:genai-proofreading:1.0.0-beta1")
    implementation("com.google.mlkit:genai-rewriting:1.0.0-beta1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
