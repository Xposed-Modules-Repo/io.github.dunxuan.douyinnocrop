plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.dunxuan.douyinnocrop"
    compileSdk = 37          // 本机已安装 platforms/android-37.0

    defaultConfig {
        applicationId = "io.github.dunxuan.douyinnocrop"
        minSdk = 28          // 抖音最低 Android 9；LSPosed 现代模块建议 ≥27
        targetSdk = 35
        versionCode = 8
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // 第一版关闭混淆，避免 java_init.list 类名不同步的经典坑；
            // 想瘦身时打开，并使用下方 proguard-rules.pro 中的规则。
            isMinifyEnabled = false
            // 用 debug 签名，构建完可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // 保证 META-INF/xposed/* 清单文件打进 APK
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // 现代 Xposed API（compileOnly：只在编译期使用，不打进 APK）
    // 官方坐标：https://repo.maven.apache.org/maven2/io/github/libxposed/api/
    compileOnly("io.github.libxposed:api:102.0.0")
}
