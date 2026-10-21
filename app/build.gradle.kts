plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.stepbystep.salsa"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.stepbystep.salsa"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.15.0")
}
