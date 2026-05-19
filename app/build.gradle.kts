plugins {
    id("com.android.application")
}

android {
    namespace = "com.housamo.lsposed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.housamo.lsposed"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static"
                )
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden", "-fvisibility-inlines-hidden")
            }
        }
    }

    // No activity needed — LSPosed module has no UI
    buildTypes {
        release {
            isMinifyEnabled = false
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
    // LSPosed API (compileOnly — not bundled into APK, provided by framework)
    compileOnly("de.robv.android.xposed:api:82")
}

// ═══════ LSPosed 模块发布时不能包含 xposed API jar ═══════
// compileOnly 已确保不会打包进 APK，下方做二次保险
afterEvaluate {
    tasks.findByName("mergeReleaseJavaResource")?.enabled = false
}
