import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 고정 서명 키: keystore.properties 또는 환경변수(CI)에서 읽는다. 둘 다 없으면 기본 debug 서명으로 빌드.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.choon.presence"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.choon.presence"
        minSdk = 29
        targetSdk = 34
        versionCode = 9
        versionName = "0.3.3"
    }

    val storePath = signingValue("storeFile", "SIGNING_STORE_FILE")
    if (storePath != null) {
        signingConfigs.create("fixed") {
            storeFile = rootProject.file(storePath)
            storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("fixed")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
