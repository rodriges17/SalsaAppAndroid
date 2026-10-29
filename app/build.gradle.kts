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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = providers.gradleProperty("stepVersionCode").map { it.toInt() }.getOrElse(73)
        versionName = "0.7.66"
    }
    // Distribution builds use an explicitly restored key. Local/emulator-only debug builds
    // retain Android's normal debug identity and are never published by the workflow.
    System.getenv("STEPBYSTEP_SIGNING_STORE")?.let { path ->
        signingConfigs.getByName("debug") {
            storeFile = file(path)
            storePassword = System.getenv("STEPBYSTEP_SIGNING_PASSWORD")
                ?: error("Missing distribution signing password")
            keyAlias = "stepbystep"
            keyPassword = storePassword
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core:1.15.0")
}
