// 说明：.kts 中的 `java` 会先解析为 java 扩展，写全限定名 java.util.Properties 反而报 Unresolved，
// 因此必须在脚本最前面 import
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 签名只从 signing/keystore.properties 读：该文件和 keystore 实体都不进仓库（已在 .gitignore 里）。
// 找不到就退回 debug 签名并打一行警告：仓库公开后，缺失密钥的构建会回退到 debug 签名；正式包须配置自有密钥。
val signingPropsFile = rootProject.file("signing/keystore.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}
// 密码可以完全不落盘：环境变量 MINGENT_STORE_PASSWORD / MINGENT_KEY_PASSWORD 优先于 properties 文件。
// 打包时通过环境变量注入密码，磁盘不保留明文。
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
        // 版本规则（2026-10-26 定）：首位/中位由用户定（助手可推荐），末位由助手按改动程度自行 +1；
        // 每次打包 versionCode 必须 +1（跨 versionName 也不重置），否则覆盖安装会因「版本未升高」被系统直接拒掉
        versionCode = 329
        versionName = "1.3.8"
    }

    signingConfigs {
        // 仅在正式密钥存在时创建该配置；缺失时完全不声明，避免其作为默认值进入开源仓库
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
        // 调试包同样使用正式密钥：签名与正式包一致才能互相覆盖安装，无需每次卸载
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

        // 共存版（正式用途）：与 release 同配置（非 debuggable、不混淆），但包名与名称带 dev，
        // 可与正式版同装。debug 构建为 debuggable，动效明显更卡顿，不能用于性能评估。
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
    // 纯逻辑单测（定时任务的下次触发时刻等），不依赖 Android 框架
    testImplementation("junit:junit:4.13.2")

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
    // zstd-jni：会话事件流的帧压缩与校验（D1）。
    // 该坐标同时发布 jar（桌面原生库）与 aar（jni/{arm64-v8a,armeabi-v7a,x86,x86_64}），
    // POM packaging 是 jar 且无 Gradle 模块元数据：不显式指定 artifact type 时
    // Android 工程会解析到 jar（实测 1.5.7-28 确实下载了 jar），APK 内没有原生库，运行时加载即崩。
    // 因此主实现强制取 aar；本地单元测试跑在桌面 JVM 上，需要另引 jar 取桌面原生库。
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    testImplementation(libs.zstd.jni) { artifact { type = "jar" } }
    // org.json 在 android.jar 里只是桩（方法体直接抛异常）：单元测试需要真实现
    // 才能覆盖会话事件流的 JSON 编解码（迁移幂等、补偿事件等测试都依赖它）。
    testImplementation("org.json:json:20240303")
    implementation(project(":terminal"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
}

// ---------------------------------------------------------------
// 架构边界检查
//
// 把「谁不许依赖谁」从文档约定变成构建失败：
//   gradle :app:checkArchitecture
//
// 规则来源：项目分层与依赖矩阵约定。只检查新层
// （core / uikit / infra / capability / feature / app），不属于任何层的包跳过。
//
// 白名单为空：跨 feature 依赖经由 app/navigation 装配，共享件放在 uikit，
// 跨 feature 的窄口下沉到 capability。新增条目必须写明编号与原因。
// ---------------------------------------------------------------
val checkArchitecture by tasks.registering {
    group = "verification"
    description = "检查包依赖方向，违反即构建失败（依据项目分层与依赖矩阵约定）"
    doLast {
        val srcRoot = file("src/main/java")
        val packageRe = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val importRe = Regex("""^\s*import\s+([\w.]+)""", RegexOption.MULTILINE)

        // 注意：包名可能正好是层根（如 com.zmf.agent.uikit，没有尾部点），
        // 仅用 startsWith("com.zmf.agent.uikit.") 会漏掉这类文件——此前的测试探针即由此漏检
        fun layerOf(pkg: String?): String? {
            if (pkg == null || !pkg.startsWith("com.zmf.agent.")) return null
            val rest = pkg.removePrefix("com.zmf.agent.")
            return when {
                // core 也是层：它是数据与工具的容器，所有层都依赖它，它不依赖任何层。
                // 早期漏掉这一分支，core.log / core.AppPaths 会被误判为「老目录」，infra 一引用即报违规。
                rest == "core" || rest.startsWith("core.") -> "core"
                rest == "uikit" || rest.startsWith("uikit.") -> "uikit"
                rest == "infra" || rest.startsWith("infra.") -> "infra"
                rest == "capability" || rest.startsWith("capability.") -> "capability"
                rest == "feature" || rest.startsWith("feature.") -> "feature"
                // app 是装配层：谁都可以用它，它可以用任何人。
                rest == "app" || rest.startsWith("app.") -> "app"
                else -> null
            }
        }

        fun featureOf(pkg: String): String? =
            if (pkg.startsWith("com.zmf.agent.feature.")) {
                pkg.removePrefix("com.zmf.agent.feature.").substringBefore('.')
            } else null

        val allowed = mapOf(
            "core" to setOf("core"),
            "uikit" to setOf("core", "uikit"),
            "infra" to setOf("core", "infra"),
            "capability" to setOf("core", "infra", "capability"),
            "feature" to setOf("core", "infra", "capability", "uikit", "feature"),
            "app" to setOf("core", "infra", "capability", "uikit", "feature", "app")
        )

        // 编号: 层 / 来源包前缀 / 被依赖包前缀 / 原因
        // from 前缀不可省：feature 层若只按层放行，等于放开了整个 feature 之间的依赖
        data class Wl(val layer: String, val fromPrefix: String, val toPrefix: String, val why: String)
        val whiteList = emptyList<Wl>()

        val problems = mutableListOf<String>()
        val files = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        var checkedFiles = 0
        for (f in files) {
            val text = f.readText()
            val pkg = packageRe.find(text)?.groupValues?.get(1) ?: continue
            val rel = f.relativeTo(srcRoot).toString().replace('\\', '/')

            // 规则 6：vendored 终端只能从 infra 进。这条对所有文件生效，不豁免老目录——
            // 该边界对所有层一律禁止，不存在需要迁移的存量。
            for (m in importRe.findAll(text)) {
                val imp = m.groupValues[1]
                if (imp.startsWith("com.ai.assistance.operit.") && !pkg.startsWith("com.zmf.agent.infra.")) {
                    problems += "$rel: only infra may depend on vendored terminal ($imp)"
                }
            }

            val layer = layerOf(pkg) ?: continue
            checkedFiles++
            val selfFeature = featureOf(pkg)
            for (m in importRe.findAll(text)) {
                val imp = m.groupValues[1]
                if (!imp.startsWith("com.zmf.agent.")) continue
                if (whiteList.any {
                        it.layer == layer &&
                            (pkg == it.fromPrefix || pkg.startsWith(it.fromPrefix + ".")) &&
                            imp.startsWith(it.toPrefix)
                    }
                ) continue
                val targetLayer = layerOf(imp)
                val rel = f.relativeTo(srcRoot).toString().replace('\\', '/')
                if (targetLayer == null) {
                    problems += "$rel: new layer '$layer' must not depend on legacy code ($imp)"
                } else if (targetLayer !in allowed.getValue(layer)) {
                    problems += "$rel: '$layer' must not depend on '$targetLayer' ($imp)"
                } else if (layer == "feature" && targetLayer == "feature" && featureOf(imp) != selfFeature) {
                    problems += "$rel: feature modules must not depend on each other ($imp)"
                }
            }
        }

        if (problems.isEmpty()) {
            logger.lifecycle("checkArchitecture: OK (scanned ${files.size} kt, new-layer ${checkedFiles}, whitelisted ${whiteList.size})")
        } else {
            problems.forEach { logger.error("  [violation] $it") }
            throw GradleException("Architecture check failed: " + problems.size + " violation(s), see list above")
        }
    }
}