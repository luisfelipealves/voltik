plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "com.voltik.player"
 compileSdk = 35
 defaultConfig { applicationId = "com.voltik.player"; minSdk = 26; targetSdk = 35; versionCode = 9; versionName = "0.1.8-experimental"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 if (System.getenv("CI") == "true") {
  signingConfigs {
   getByName("debug") {
    storeFile = rootProject.file(".debug-signing/debug.keystore")
    storePassword = "android"
    keyAlias = "androiddebugkey"
    keyPassword = "android"
   }
  }
 }
 // Disposable x86 CI only: omit ARM libraries so a playback-only emulator can install.
 // This does NOT test the projectM renderer and must never affect distributable APKs.
 val x86PlaybackOnly = providers.gradleProperty("voltik.ci.x86PlaybackOnly").orNull == "true"
 if (x86PlaybackOnly) {
  require(System.getenv("CI") == "true") { "x86PlaybackOnly is restricted to CI" }
  require(gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }) { "x86PlaybackOnly cannot build release APKs" }
 }
 packaging {
  jniLibs.useLegacyPackaging = true
  if (x86PlaybackOnly) jniLibs.excludes += setOf("**/arm64-v8a/**", "**/armeabi-v7a/**")
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(files("libs/projectM-TV-core-2.3.57.aar"))
 implementation(platform("androidx.compose:compose-bom:2025.02.00"))
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-session:1.5.1")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}

