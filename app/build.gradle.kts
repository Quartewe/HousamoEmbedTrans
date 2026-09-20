plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = providers.environmentVariable("HET_SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
)
if (releaseSigningValues.any { it != null }) {
    require(releaseSigningValues.all { !it.isNullOrEmpty() }) {
        "Release signing requires all four signing environment variables"
    }
}

android {
    namespace = "com.quarty.housamoembedtrans"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.quarty.housamoembedtrans"
        minSdk = 28
        targetSdk = 36
        versionCode = providers.gradleProperty("hetVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("hetVersionName").orElse("1.0").get()

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static"
                )
                cppFlags += listOf(
                    "-std=c++17",
                    "-fno-exceptions",
                    "-fno-rtti",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden"
                )
            }
        }
    }

    buildFeatures {
        prefab = true
        aidl = true
    }

    // 设置、字典与 scene 文件管理由已安装的 HET Activity 提供。
    signingConfigs {
        if (releaseStoreFile != null) {
            create("ciRelease") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }

    packaging {
        jniLibs {
            pickFirsts += listOf(
                "**/libshadowhook.so",
                "**/libshadowhook_nothing.so"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // LSPosed API — compileOnly: 由框架运行时提供，不打包进 APK
    compileOnly(libs.xposed.api)
    // ShadowHook — Android inline hook
    implementation(libs.shadowhook)
    // Material Components — 主题样式需要
    implementation(libs.material)
    // AndroidX AppCompat — 设置界面需要
    implementation(libs.androidx.appcompat)
}

// ═══════ 确保 xposed API jar 不会被打包 ═══════
afterEvaluate {
    tasks.findByName("mergeReleaseJavaResource")?.enabled = false
}
