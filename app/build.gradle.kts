plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---- konfigurasi dari gradle.properties / -P / environment ----
// Jika -PAPI_BASE_URL dikirim kosong (mis. repository variable GitHub Actions belum diisi),
// jatuh kembali ke nilai di gradle.properties alih-alih gagal dengan nilai "".
val apiBase: String = run {
    val fromProject = (findProperty("API_BASE_URL") as String?)?.trim().orEmpty()
    if (fromProject.isNotEmpty()) fromProject
    else java.util.Properties().apply {
        rootProject.file("gradle.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }.getProperty("API_BASE_URL")?.trim().orEmpty()
}
require(Regex("^https?://\\S+/$").matches(apiBase)) {
    "API_BASE_URL harus diawali http:// atau https:// dan diakhiri '/' (sekarang: \"$apiBase\")"
}
if (apiBase.contains("GANTI")) logger.warn("PERINGATAN: API_BASE_URL masih placeholder; aplikasi tidak akan bisa memuat data.")
val versionCodeProp = (findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1
val versionNameProp = (findProperty("VERSION_NAME") as String?) ?: "1.0"
// Keystore rilis (opsional). Tanpa ini, APK rilis ditandatangani dengan debug key supaya tetap bisa dipasang.
// Password TIDAK boleh ditulis di repo: isi lewat environment (KEYSTORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS, KEYSTORE_PATH) atau GitHub Secrets.
fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val keystoreFile: File? = file(env("KEYSTORE_PATH", "mitoon.jks")).takeIf { it.exists() }
val keystorePassword: String? = envOrNull("KEYSTORE_PASSWORD")
val keyPasswordValue: String? = envOrNull("KEY_PASSWORD") ?: keystorePassword
if (keystoreFile != null && keystorePassword == null) logger.warn("PERINGATAN: keystore ditemukan tetapi KEYSTORE_PASSWORD kosong; APK rilis ditandatangani debug key.")

android {
    namespace = "id.mitoon.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.mitoon.app"
        minSdk = 24; targetSdk = 34; versionCode = versionCodeProp; versionName = versionNameProp
        buildConfigField("String", "API_BASE_URL", "\"$apiBase\"")
        manifestPlaceholders["usesCleartext"] = apiBase.startsWith("http://").toString()
    }
    signingConfigs {
        if (keystoreFile != null && keystorePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = env("KEY_ALIAS", "mitoon")
                keyPassword = keyPasswordValue
            }
        }
    }
    buildTypes {
        release {
            // R8: kode lebih ringkas & cepat start. Bila ada masalah di runtime, build dengan -PMINIFY=false.
            val minify = (findProperty("MINIFY") as String?)?.toBooleanStrictOrNull() ?: true
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false } // lint tidak ikut memblokir build APK di CI
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1") // pasang baseline profile (Compose) -> start & scroll pertama lebih lancar
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0") // mascot loading (GIF animasi)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Masuk dengan Google (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}

// Firebase aktif hanya bila google-services.json ada di folder app/ (tanpa itu build tetap jalan, push realtime nonaktif).
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
