plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.sycompany.duomorph"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sycompany.duomorph"
        minSdk = 31
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
