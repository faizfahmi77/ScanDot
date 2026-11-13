plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "com.roomscan.app"
    compileSdk = 36
    defaultConfig { applicationId = "com.roomscan.app"; minSdk = 26; targetSdk = 36; versionCode = 7; versionName = "0.3.0-preview.1" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    implementation("com.google.ar:core:1.48.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.1")
    implementation("androidx.compose.foundation:foundation:1.11.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    // Android's JSONObject methods are stubs in local JVM tests.
    testImplementation("org.json:json:20260814")
}
