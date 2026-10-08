plugins {
    id("com.android.application")
    kotlin("android")
}

val appVersion = providers.gradleProperty("appVersionName")
    .orElse(providers.fileContents(rootProject.layout.projectDirectory.file("VERSION")).asText.map { it.trim() })
    .get()
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(appVersion)) {
    "版本号必须为 major.minor.patch"
}
val versionParts = appVersion.split('.').map { it.toInt() }
require(versionParts[0] in 0..2000 && versionParts[1] in 0..999 && versionParts[2] in 0..999) {
    "版本号范围：major 0..2000，minor/patch 0..999"
}
val appVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
require(appVersionCode > 0) { "版本号须大于 0.0.0" }
val signingValues = listOf("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val hasSigning = signingValues.values.all { !it.isNullOrBlank() }
require(hasSigning || signingValues.values.all { it.isNullOrBlank() }) { "正式签名配置不完整" }
require(providers.environmentVariable("REQUIRE_RELEASE_SIGNING").orNull != "true" || hasSigning) {
    "正式发布必须配置 Android 签名"
}

android {
    namespace = "com.example.qiafan"
    compileSdk = 34
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        applicationId = "com.example.qiafan"
        minSdk = 23
        targetSdk = 30
        versionCode = appVersionCode
        versionName = appVersion
    }

    if (hasSigning) signingConfigs.create("release") {
        storeFile = file(signingValues.getValue("ANDROID_KEYSTORE_PATH")!!)
        storePassword = signingValues.getValue("ANDROID_KEYSTORE_PASSWORD")
        keyAlias = signingValues.getValue("ANDROID_KEY_ALIAS")
        keyPassword = signingValues.getValue("ANDROID_KEY_PASSWORD")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        // APK 通过 GitHub Release 分发；目标 SDK 升级单独验收，保留其它 Release lint 检查。
        disable += "ExpiredTargetSdkVersion"
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.appcompat:appcompat:1.3.1")

    implementation("com.squareup.picasso:picasso:2.71828")

    implementation("androidx.core:core-ktx:1.6.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.12.0")
}
