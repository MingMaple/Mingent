import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.operit.terminal"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 见文件末尾那段说明：AIDL 生成物的文件头注释里混了 GBK 字节，必须按单字节解码
        encoding = "ISO-8859-1"
    }
    buildFeatures {
        aidl = true
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
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

/**
 * aidl.exe 会把「生成命令」原样写进产物文件头的注释里，而命令行里有中文用户目录
 * （C:\Users\<中文名>\.gradle\…），那部分是系统 ANSI(GBK) 字节；JDK 18+ 的 javac
 * 默认按 UTF-8 读源码，于是报「编码 UTF-8 的不可映射字符」直接编译失败。
 *
 * 本模块没有任何手写 .java（只有 AIDL 生成的 stub），生成的 stub 内容全是 ASCII，
 * 注释按单字节直通解码不影响语义，所以这里统一用 ISO-8859-1 读，绕开这个坑。
 */
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "ISO-8859-1"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.ui.graphics.android)
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.text.android)
    implementation(libs.androidx.animation.android)
    implementation(libs.androidx.ui.android)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization)
    
    // SSH 依赖
    implementation("com.jcraft:jsch:0.1.55")
    
    // FTP服务器依赖
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.ftpserver:ftplet-api:1.2.0")
    
    // SSHD服务器依赖
    implementation("org.apache.sshd:sshd-core:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.sshd:sshd-sftp:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    // BouncyCastle for SSHD on Android (avoids JMX issues)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
}