# L1（BUGS-v2.61）：开 R8 + 资源压缩后的保留规则。
#
# 目标：能删的都删（类名、方法名、字符串常量），但下面几类**必须**保留，
# 否则运行时会 ClassNotFoundException / NoSuchMethodError / 桥方法失效。
#
# 注意：AGP 的 proguard-android-optimize.txt 已经包含 Compose、AndroidX、
# View 的 keep 规则，这里只补它不管的部分。

# ---------------- WebView 的 JS 桥 ----------------
# SignHubApi / SignHubLogin 都是 addJavascriptInterface 注入的对象，
# 方法名就是网页里 bridge.xxx() 的调用名 —— 混淆掉方法名就等于桥全废。
# AGP 默认会保留 @JavascriptInterface 注解的方法，这里显式写一遍，
# 防止将来换 AGP 版本时默认规则变动。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.signhub.app.WebUiActivity$Bridge { *; }
-keep class com.signhub.app.LoginWebActivity$* { *; }

# ---------------- OkHttp / Okio ----------------
# OkHttp 4.x 自带 consumer rules，但 -dontwarn 能消掉可选依赖
# （Conscrypt / BouncyCastle / OpenJSSE / animal-sniffer）的警告噪音。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------- Kotlin ----------------
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
# Kotlin 的协程 Continuation 在栈上，R8 全量优化偶尔会破坏 suspend 恢复
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---------------- org.json ----------------
# 项目大量用 org.json.JSONObject 手工解析，不涉及反射，但保留名字便于崩溃定位
-keep class org.json.** { *; }

# ---------------- 序列化 / 反射入口 ----------------
# WorkManager 用反射实例化 Worker（AutoCheckinWorker），必须保名字
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.signhub.app.AutoCheckinWorker { *; }

# 广播接收器 / 服务 / Activity 都在 Manifest 里按类名声明，
# R8 会自动保留，这里显式列出便于阅读
-keep class com.signhub.app.AutoCheckinService { *; }
-keep class com.signhub.app.AutoCheckinAlarmReceiver { *; }
-keep class com.signhub.app.BootReceiver { *; }
-keep class com.signhub.app.ReminderReceiver { *; }

# ---------------- 调试信息 ----------------
# 保留行号，崩溃栈才有意义（不保留局部变量名，减小体积）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
