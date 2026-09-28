import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services) apply false
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val envProps = Properties().apply {
    val f = rootProject.file(".env")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    localProps.getProperty(key)
        ?: System.getenv(key)
        ?: envProps.getProperty(key)
        ?: envProps.getProperty("VITE_$key")
        ?: default

fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val releaseStoreFile = localProp("RELEASE_STORE_FILE")
val releaseStorePassword = localProp("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localProp("RELEASE_KEY_ALIAS")
val releaseKeyPassword = localProp("RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = releaseStoreFile.isNotBlank() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()

val googleServicesFile = file("google-services.json")
fun googleWebClientId(): String {
    localProp("GOOGLE_WEB_CLIENT_ID").takeIf(String::isNotBlank)?.let { return it }
    if (!googleServicesFile.exists()) return ""

    val config = JsonSlurper().parse(googleServicesFile) as? Map<*, *> ?: return ""
    val clients = config["client"] as? List<*> ?: return ""
    return clients.asSequence()
        .mapNotNull { it as? Map<*, *> }
        .filter { client ->
            val info = client["client_info"] as? Map<*, *>
            val androidInfo = info?.get("android_client_info") as? Map<*, *>
            androidInfo?.get("package_name") == "com.nextbench.app"
        }
        .flatMap { client -> (client["oauth_client"] as? List<*>).orEmpty().asSequence() }
        .mapNotNull { it as? Map<*, *> }
        .firstOrNull { it["client_type"].toString() == "3" }
        ?.get("client_id")
        ?.toString()
        .orEmpty()
}

if (googleServicesFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val releasePackagingRequested = gradle.startParameter.taskNames.any { taskName ->
    val normalized = taskName.substringAfterLast(':').lowercase()
    normalized == "build" ||
        normalized == "assemble" ||
        normalized.startsWith("assemblerelease") ||
        normalized.startsWith("bundlerelease") ||
        normalized.startsWith("publishrelease")
}

if (releasePackagingRequested) {
    check(googleServicesFile.exists()) {
        "app/google-services.json is required for release builds. See docs/SETUP.md."
    }
    check(localProp("CLOUDINARY_CLOUD_NAME").isNotBlank()) {
        "CLOUDINARY_CLOUD_NAME is required for release builds."
    }
    check(localProp("CLOUDINARY_UPLOAD_PRESET").isNotBlank()) {
        "CLOUDINARY_UPLOAD_PRESET is required for release builds."
    }
    check(releaseSigningConfigured) {
        "Release signing is not configured. Set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD in ignored local.properties or the environment."
    }
    check(rootProject.file(releaseStoreFile).isFile) {
        "Release keystore was not found at '$releaseStoreFile'."
    }
}

android {
    namespace = "com.nextbench.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nextbench.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "FIREBASE_CONFIGURED", googleServicesFile.exists().toString())
        buildConfigField("String", "GIPHY_API_KEY", quoted(localProp("GIPHY_API_KEY")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(googleWebClientId()))
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile.isNotBlank()) {
                storeFile = rootProject.file(releaseStoreFile)
            }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":data:model"))
    implementation(project(":data:firebase"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
