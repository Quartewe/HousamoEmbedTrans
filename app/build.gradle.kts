plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.quarty.housamoembedtrans"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.quarty.housamoembedtrans"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    }

    // LSPosed 模块无需 Activity（纯后台 Hook）
    buildTypes {
        release {
            isMinifyEnabled = false
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
