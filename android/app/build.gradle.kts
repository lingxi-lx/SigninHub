import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// H1（BUGS-v2.61）：release 签名。
// 原来 buildTypes.release 只有 isMinifyEnabled，没有 signingConfig —— 交付的
// SigninHub-mobile.apk 一直是 assembleDebug 产物：application-debuggable、
// 证书是公开的 "CN=Android Debug"，任何人拿到这个 keystore（Android SDK 里自带）
// 都能签一个同样身份的包去覆盖安装。
// 现在 release 用本机 keystore 签名，口令放在 android/keystore.properties（不入库）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.signhub.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.signhub.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 262
        versionName = "2.62"

        // L12（BUGS-v2.61）：只保留真机 ABI。原来打出来的 APK 带 arm64-v8a /
        // armeabi-v7a / x86 / x86_64 四份 native 库（libdatastore_shared_counter.so），
        // 而 x86/x86_64 只在模拟器上有用，纯属体积浪费。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        // AGP 8 默认关掉 BuildConfig 生成；设置页要读 BuildConfig.VERSION_NAME 显示版本号，
        // 必须显式打开，否则 LocalApi 里会报 Unresolved reference: BuildConfig。
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                // storeFile 里的相对路径是相对 **android/ 根**（rootProject）写的，
                // 而 signingConfigs 里的 file() 默认相对 app/ 模块目录 —— 必须显式
                // 用 rootProject.file() 解析，否则会找成 android/app/keystore/...
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v3 才有密钥轮换能力；v2 是 Android 7.0+ 的要求
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // L1（BUGS-v2.61）：开 R8 + 资源压缩。原来两项都没开，APK 里能直接
            // 反编译出完整类名/方法名和全部字符串常量。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // H1：兜底 —— 即使有人用 debug 变体打包含，也不允许 debuggable
            isDebuggable = false
        }
        debug {
            // 保持默认（debuggable），仅本地调试用
        }
    }

    // L14（BUGS-v2.61）：assets 与 backend/web 双份 UI 靠人工 Copy-Item 同步，
    // 没有机制保证一致（历史上就出现过 backend/web 改了、assets 忘了同步，
    // 手机上还是旧页面的情况）。这里在 preBuild 前自动同步，MD5 天然一致。
    val syncWebAssets by tasks.registering(Copy::class) {
        from(rootProject.file("../backend/web")) {
            include("index.html", "sw.js", "manifest.webmanifest")
            include("static/**")
            // manifest.webmanifest 与图标由 backend/web 提供，整目录镜像
            exclude("**/*.map")
        }
        into(layout.projectDirectory.dir("src/main/assets"))
    }
    tasks.named("preBuild") {
        dependsOn(syncWebAssets)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
