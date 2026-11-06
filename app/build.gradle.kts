plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 网络（tailnet / 代理 / mosh 中继）的 Go 部分：scripts/build-netbridge.sh 生成，不入库。
val netbridgeAar = file("libs/netbridge.aar")

android {
    namespace = "com.briqt.moke"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.briqt.moke"
        minSdk = 30
        targetSdk = 35
        versionCode = 67
        versionName = "0.2.1-rc.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // mosh native 目前仅提供 arm64-v8a 预编译（scripts/build-mosh-native.sh）；其它 ABI 后续补齐。
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // 稳定发布签名：CI 通过 MOKE_* 环境变量（来自 GitHub secrets）注入 keystore。
        // 本地无环境变量时不配置，release 构建回退到 debug 签名，方便本地出包。
        create("release") {
            val ksPath = System.getenv("MOKE_KEYSTORE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("MOKE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MOKE_KEY_ALIAS")
                keyPassword = System.getenv("MOKE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有稳定 keystore 用它（各版本签名一致，可升级安装）；否则回退 debug（本地）。
            signingConfig = if (!System.getenv("MOKE_KEYSTORE").isNullOrBlank())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        dex {
            // minSdk ≥ 28 时 AGP 默认不压缩 dex（面向应用商店按需压缩下载）；moke 直接分发 APK，
            // 不压缩会让下载体积翻倍以上，故保持压缩。
            useLegacyPackaging = true
        }
        jniLibs {
            // 必须解压 native 库到 nativeLibraryDir，才能作为独立进程 exec libmosh-client.so。
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)

    implementation(files(netbridgeAar))

    // SSH 传输（引导 + 交互）
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        // Verification only: never download fonts/tools or rebuild the Go artifact here.
        for (script in listOf("scripts/verify-netbridge.sh", "scripts/verify-bundled-fonts.sh")) {
            val process = try {
                ProcessBuilder("bash", rootProject.file(script).absolutePath, "check")
                    .directory(rootProject.projectDir)
                    .redirectErrorStream(true)
                    .start()
            } catch (error: java.io.IOException) {
                throw GradleException("无法运行 $script：需要 Bash 和 GNU coreutils", error)
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.waitFor() == 0) { output }
            logger.lifecycle(output)
        }
    }
}
