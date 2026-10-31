import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val sharing = Properties().apply {
    rootProject.file("sharing.properties").inputStream().use { load(it) }
}
val sharingHost = sharing.getProperty("host")
val sharingPath = sharing.getProperty("path")
require(sharingHost.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*"))) { "Invalid sharing host" }
require(sharingPath.matches(Regex("/(?:[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*/)*"))) { "Invalid sharing path" }
android {
    namespace = "com.pod4me"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pod4me"
        minSdk = 34
        targetSdk = 36
        versionCode = 19
        versionName = "1.0.11"
        buildConfigField("String", "SHARE_BASE_URL", "\"https://$sharingHost$sharingPath\"")
        manifestPlaceholders["sharingHost"] = sharingHost
        manifestPlaceholders["sharingPath"] = sharingPath
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("personal") {
            storeFile = file(System.getenv("POD4ME_KEYSTORE") ?: "${System.getProperty("user.home")}/.android/pod4me-v1.keystore")
            storePassword = System.getenv("POD4ME_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("POD4ME_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("POD4ME_KEY_PASSWORD") ?: storePassword
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
