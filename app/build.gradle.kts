plugins {
    id("com.android.application")
}

// ---------------------------------------------------------------------------
// 版本策略（阶段 8）：versionCode 默认取 HEAD 提交数（随每次合入单调递增），
// 商店上传可用 -Pbilliards.versionCode 覆盖；versionName 默认 1.0.0，
// CI 在打 tag 发布时用 -Pbilliards.versionName=<tag> 覆盖。
// ---------------------------------------------------------------------------
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    workingDir = rootDir
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }

// ---------------------------------------------------------------------------
// Release 签名（阶段 8，关闭风险 R9）：上传 keystore 永不入库。
// 本地或 CI 通过环境变量（或同名 gradle 属性）提供 RELEASE_KEYSTORE_PATH 及
// 三个口令/别名变量后，release 构建自动签名 APK 与 AAB；缺省时（如 fork 的
// PR）release 构建保持未签名，维持阶段 8 之前的 CI 行为。
// ---------------------------------------------------------------------------
// 空白字符串视为未提供（CI 用静态 env 表达式时缺省 secret 会是空串）。
fun signingEnv(envName: String, propertyName: String) =
    providers.environmentVariable(envName)
        .orElse(providers.gradleProperty(propertyName))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .orNull

val releaseKeystorePath = signingEnv("RELEASE_KEYSTORE_PATH", "billiards.releaseKeystorePath")
val releaseStorePassword = signingEnv("RELEASE_STORE_PASSWORD", "billiards.releaseStorePassword")
val releaseKeyAlias = signingEnv("RELEASE_KEY_ALIAS", "billiards.releaseKeyAlias")
val releaseKeyPassword = signingEnv("RELEASE_KEY_PASSWORD", "billiards.releaseKeyPassword")

val hasReleaseSigning = releaseKeystorePath != null

android {
    namespace = "com.minamikune.billiards"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    signingConfigs {
        if (hasReleaseSigning) {
            require(releaseStorePassword != null && releaseKeyAlias != null &&
                releaseKeyPassword != null
            ) {
                "Release signing is enabled via RELEASE_KEYSTORE_PATH, but one of " +
                    "RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD " +
                    "is missing."
            }
            require(file(releaseKeystorePath!!).isFile) {
                "Release keystore not found at '$releaseKeystorePath'."
            }
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.minamikune.billiards"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("billiards.versionCode")
            .map { it.toInt() }
            .orElse(gitCommitCount)
            .get()
        versionName = providers.gradleProperty("billiards.versionName").orElse("1.0.0").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation(project(":core"))
    // Stage 6 (Android 17 / foldables): androidx.window reports fold postures
    // (FoldingFeature) so the HUD/menus/camera can adapt; coroutines-android
    // provides Dispatchers.Main for the WindowLayoutInfo collector. 1.8.1 aligns
    // with the coroutines BOM constraint that androidx.test pulls in, so the
    // unit-test and androidTest classpaths resolve the same version.
    implementation("androidx.window:window:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
