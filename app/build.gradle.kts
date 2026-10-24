// 注意：.kts 里的 `java` 会先解析成 java 扩展，写全限定名的 java.util.Properties 反而报 Unresolved，
// 所以必须在脚本最前面 import 进来
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 签名只从 signing/keystore.properties 读：该文件和 keystore 实体都不进仓库（已在 .gitignore 里）。
// 找不到就退回 debug 签名并打一行警告——开源出去后谁都能 build，正式包用自己的 key。
val signingPropsFile = rootProject.file("signing/keystore.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}
// 密码可以完全不落盘：环境变量 MINGENT_STORE_PASSWORD / MINGENT_KEY_PASSWORD 优先于 properties 文件。
// 这样钥匙主人可以在自己要打包的那一刻才把密码交给构建进程，磁盘上不留明文。
val signingValue: (String, String) -> String? = { propKey, envKey ->
    System.getenv(envKey)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
}
// storeFile 按仓库根解析（形如 ../MG_Keys/mingent.jks，keystore 实体放在仓库外的兄弟目录）
val releaseKeystore = signingProps.getProperty("storeFile")?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { rootProject.file(it) }
val hasReleaseKey = releaseKeystore?.exists() == true
if (!hasReleaseKey) {
    logger.warn("未找到 signing/keystore.properties 里的 storeFile：本次构建使用 debug 签名，产物不能直接发布。")
}

android {
    // namespace 故意不跟着 applicationId 改：包名保持 com.zmf.agent，避免动全部 Kotlin 包名
    namespace = "com.zmf.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mingmaple.mingent"
        // 正式版名字；debug（共存版）在 buildTypes 里覆盖成 "Mingent Dev"
        manifestPlaceholders["appLabel"] = "Mingent"
        minSdk = 26
        targetSdk = 28
        // 版本规则：加新功能中间位 +1，只修问题末位 +1，大重构首位 +1；
        // 每次打包 versionCode 必须 +1，否则覆盖安装会因「版本未升高」被系统直接拒掉
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // 只有真 key 存在时才建这条配置：没有就完全不声明，免得把它当默认值带进开源仓库
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("storePassword", "MINGENT_STORE_PASSWORD")
                keyAlias = signingProps.getProperty("keyAlias")?.takeIf { it.isNotBlank() } ?: "mingent"
                keyPassword = signingValue("keyPassword", "MINGENT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // 调试包也用真 key：和正式包同签名才能互相覆盖安装，省掉每次卸载
        val appSigning = if (hasReleaseKey) {
            signingConfigs.getByName("release")
        } else {
            signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = appSigning
            // 共存版：包名不同可同时安装；产物在 outputs/apk/debug/
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Mingent Dev"
        }
        release {
            isMinifyEnabled = false
            signingConfig = appSigning
        }

        // 共存版（正式用途）：和 release 同配置（非 debuggable、不混淆），但包名/名字带 dev，
        // 可与正式版同装。debug 构建是 debuggable 的，跑动效会明显更卡，不能拿它评性能。
        create("coexist") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Mingent Dev"
            isDebuggable = false
            // :terminal / :core 等模块没有 coexist 变体，回落用 release
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        // PRoot 依赖 targetSdk 28（升到 29+ 终端起不来），Play 的「目标版本过低」政策检查对本机自用无意义
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // 悬浮窗里用 ComposeView 需要直接挂在 View 树上的 LifecycleOwner / SavedStateRegistryOwner
    // （Service 里没有 Activity，不挂会抛 ViewTreeLifecycleOwner not found）
    implementation("androidx.lifecycle:lifecycle-runtime:2.11.0")
    implementation("androidx.savedstate:savedstate:1.5.0")
    implementation(libs.salt.ui)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    // Shizuku：借 shell(uid 2000) 通道做权限授予；provider 由 Manifest 里的 ShizukuProvider 提供
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(project(":terminal"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation("androidx.compose.ui:ui-tooling")
}