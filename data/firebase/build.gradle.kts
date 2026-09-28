import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val envProps = Properties().apply {
    val file = rootProject.file(".env")
    if (file.exists()) file.inputStream().use(::load)
}

fun localProp(key: String): String =
    localProps.getProperty(key)
        ?: System.getenv(key)
        ?: envProps.getProperty(key)
        ?: envProps.getProperty("VITE_$key")
        ?: ""

fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.nextbench.data.firebase"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        buildConfigField(
            "boolean",
            "FIREBASE_CONFIGURED",
            rootProject.file("app/google-services.json").exists().toString(),
        )
        buildConfigField("String", "CLOUDINARY_CLOUD_NAME", quoted(localProp("CLOUDINARY_CLOUD_NAME")))
        buildConfigField("String", "CLOUDINARY_UPLOAD_PRESET", quoted(localProp("CLOUDINARY_UPLOAD_PRESET")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":data:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
