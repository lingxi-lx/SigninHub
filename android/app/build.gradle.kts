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
    versionCode = 295
        versionName = "2.63"

        // L12（BUGS-v2.61）：只保留真机 ABI。原来打出来的 APK 带 arm64-v8a /
        // armeabi-v7a / x86 / x86_64 四份 native 库（libdatastore_shared_counter.so），
        // 而 x86/x86_64 只在模拟器上有用，纯属体积浪费。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        // V2.63：关掉 compose。旧的「原生 Compose 界面」MainActivity 已整个删除
        // （零 Intent 入口的死代码），全仓再无 @Composable，留着只会让 APK 多背
        // compose-runtime / material3 / icons-extended 三套库。
        compose = false
        // AGP 8 默认关掉 BuildConfig 生成；设置页要读 BuildConfig.VERSION_NAME 显示版本号，
        // 必须显式打开，否则 LocalApi 里会报 Unresolved reference: BuildConfig。
        buildConfig = true
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
    // V2.63：删掉整套 Compose 依赖（compose-bom / ui / ui-tooling-preview / material3 /
    // material-icons-extended / activity-compose / lifecycle-*-compose）。原生的
    // MainActivity 是零入口死代码，已整个删除，全仓再无 @Composable。
    // 注意 WebUiActivity 仍然继承 androidx.activity.ComponentActivity，
    // 所以 activity 依赖不能整个拿掉 —— 换成不带 Compose 的 activity-ktx。
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
