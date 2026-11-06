import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 版本 ----------
// 版本名可由 CI 覆盖：./gradlew assembleRelease -PappVersionName=1.2.0
val baseVersionName = "1.2.0"
val appVersionName = (findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: baseVersionName

/** 1.2.3 -> 10203；忽略 "-xxx" 后缀，同一版本的 CI 构建可以互相覆盖安装 */
fun versionCodeOf(name: String): Int {
    val p = name.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    return p[0] * 10000 + p[1] * 100 + p[2]
}

// ---------- 签名 ----------
// 优先读环境变量（GitHub Actions 从 Secrets 注入），其次读本地 keystore/keystore.properties（已加入 .gitignore）。
// 都没有时退回调试签名，保证始终能打出可安装的 APK。
val signingProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseKeystore = rootProject.file(signingValue("KAVITAINK_KEYSTORE", "storeFile") ?: "keystore/release.jks")
val releaseStorePassword = signingValue("KAVITAINK_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("KAVITAINK_KEY_ALIAS", "keyAlias") ?: "kavitaink"
val releaseKeyPassword = signingValue("KAVITAINK_KEY_PASSWORD", "keyPassword") ?: releaseStorePassword
val hasReleaseSigning = releaseKeystore.isFile && releaseStorePassword != null

android {
    namespace = "com.manus.kavitaink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manus.kavitaink"
        // Android 5.0+，兼容大多数老款墨水屏阅读器
        minSdk = 21
        // 34 可避免 Android 15 强制全面屏（edge-to-edge）带来的布局问题
        targetSdk = 34
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 仅用于排查问题：./gradlew assembleRelease -PrelDebug=true
            isDebuggable = project.findProperty("relDebug") == "true"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("KavitaInk: 未找到发布签名，release 包将使用调试签名（不能覆盖安装正式签名的版本）")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // 不生成参数 / 调用处的空值断言（只是更早抛出 NPE），能让 dex 小一些
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // 只跑翻译相关的检查（CI 的 i18n 步骤会运行 lintDebug）：缺翻译、多余翻译、复数、占位符不一致都算错误
        val i18n = setOf(
            "MissingTranslation", "ExtraTranslation", "MissingQuantity",
            "StringFormatInvalid", "StringFormatCount", "StringFormatMatches",
        )
        checkOnly += i18n + setOf("UnusedQuantity", "ImpliedQuantity")
        error += i18n
        abortOnError = true
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    // 运行时零第三方依赖：只用系统自带的 HttpURLConnection / org.json / WebView / PdfRenderer
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
