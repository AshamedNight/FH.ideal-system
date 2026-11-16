plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号由 CI 的 tag 注入（v0.1.0 → 0.1.0）；本地出包用默认值。
// versionCode = major*10000 + minor*100 + patch，保证单调递增 ——
// 否则手机把新包当旧包，覆盖安装直接失败。
val versionFromCi = (System.getenv("APP_VERSION") ?: "").trim().removePrefix("v")
val appVersionName = if (versionFromCi.isEmpty()) "0.1.0" else versionFromCi
val versionParts = appVersionName.split(".").map { part ->
    part.filter { ch -> ch in '0'..'9' }.toIntOrNull()
}
// CI 直接给递增的 versionCode（github.run_number）。
// 只靠 versionName 推导是不够的：dev 包的 versionName 里 patch 段是文字，
// 推出来恒为 1 —— 每次构建 versionCode 都一样，系统就当同一个版本，
// 弹「已安装相同版本」而不是升级。这条路必须优先。
val versionCodeFromCi = (System.getenv("APP_VERSION_CODE") ?: "").trim().toIntOrNull()
val appVersionCode: Int = run {
    val fromCi = versionCodeFromCi
    if (fromCi != null && fromCi > 0) {
        fromCi
    } else {
        val major = versionParts.getOrNull(0)
        val minor = versionParts.getOrNull(1)
        val patch = versionParts.getOrNull(2)
        if (major != null && minor != null && patch != null) {
            (major * 10000 + minor * 100 + patch).coerceAtLeast(1)
        } else {
            1
        }
    }
}

android {
    namespace = "com.autoreply.app"
    compileSdk = 34

    // 签名密钥只在 CI 里经环境变量注入，仓库不放私钥。
    // 签名必须前后一致，否则每次升级都要先卸载 —— 卸载会清掉 SharedPreferences，
    // 用户的人设、词表、已回复记录一起没。
    signingConfigs {
        create("stable") {
            val ksFile = System.getenv("KEYSTORE_FILE").orEmpty().ifBlank { "testkey.p12" }
            val ksPass = System.getenv("KEYSTORE_PASS").orEmpty().ifBlank { "autoreply-test-2026" }
            val ksAlias = System.getenv("KEYSTORE_ALIAS").orEmpty().ifBlank { "autoreply" }
            storeFile = file(ksFile)
            storeType = "PKCS12"
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksPass
        }
    }

    defaultConfig {
        applicationId = "com.autoreply.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 刻意零第三方依赖（不引 androidx / Compose / OkHttp / Gson）。
// 理由：每多一个依赖，CI 首次构建就多一处会坏的地方；而本项目需要的
// HTTP、JSON、Trie、通知，用系统 API + 手写几百行就能覆盖。
// 等骨架在 CI 上稳定出包之后，再按需引入。
