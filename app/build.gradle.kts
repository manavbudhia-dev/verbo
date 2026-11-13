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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.3.0")
}
