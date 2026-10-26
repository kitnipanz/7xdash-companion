import java.net.URI
import java.security.MessageDigest

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 电视端 OAuth。设备码登录必须把这对值编进安装包。
// GitHub 推送保护不允许把 client secret 写进仓库，所以：
//   - Action 从仓库 Secrets 注入 DRIVE_CLIENT_ID / DRIVE_CLIENT_SECRET
//   - 本机从 gitignore 的 local.properties 读同名键
// 两边都空时，设置页里手填的一对仍然生效。
fun driveCredential(name: String): String {
    val fromEnv = System.getenv(name)?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return fromEnv
    val propsFile = rootProject.file("local.properties")
    if (!propsFile.isFile) return ""
    val props = Properties()
    propsFile.inputStream().use { props.load(it) }
    return props.getProperty(name)?.trim().orEmpty()
}

fun gradleStringLiteral(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

android {
    // sherpa-onnx's JNI library needs only onnxruntime; its C and C++ API libraries are for native callers
    packaging {
        jniLibs {
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }

    namespace = "com.kooo.evcam"
    compileSdk = 36
    // AirPlay 接收（app/src/main/cpp）：UxPlay 的原生库
    ndkVersion = "27.1.12297006"

    // 签名配置。
    // 默认沿用仓库内的公开测试密钥（AOSP 测试签名，口令公开），方便任何人自行构建、
    // 并保证不同版本之间可以覆盖安装。正式发布可用环境变量覆盖为自己的密钥：
    //   ZEEKR_KEYSTORE / ZEEKR_KEYSTORE_PASSWORD / ZEEKR_KEY_ALIAS / ZEEKR_KEY_PASSWORD
    signingConfigs {
        create("release") {
            val customStore = System.getenv("ZEEKR_KEYSTORE")
            if (!customStore.isNullOrBlank() && file(customStore).exists()) {
                storeFile = file(customStore)
                storePassword = System.getenv("ZEEKR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZEEKR_KEY_ALIAS")
                keyPassword = System.getenv("ZEEKR_KEY_PASSWORD")
            } else {
                storeFile = file("../keystore/release.jks")
                storePassword = "android"
                keyAlias = "apkeasytool"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "lab.sevenx.dash"
        minSdk = 28
        targetSdk = 36
        // 每次发布 tag 前同步更新这两个值：versionCode 决定能否覆盖安装，
        // versionName 会成为 Release 名称与 APK 文件名
        //
        // 7xDash 用自己的 applicationId，可以和 zeekr-shortcut-car 并排装。版本号从 1 重新起。
        versionCode = 50
        versionName = "1.3.17-beta"

        buildConfigField("String", "DRIVE_CLIENT_ID", gradleStringLiteral(driveCredential("DRIVE_CLIENT_ID")))
        buildConfigField("String", "DRIVE_CLIENT_SECRET", gradleStringLiteral(driveCredential("DRIVE_CLIENT_SECRET")))
        // GA4 Measurement Protocol, same lookup (Action secrets / local.properties). Empty = no usage stats sent
        buildConfigField("String", "GA_MEASUREMENT_ID", gradleStringLiteral(driveCredential("GA_MEASUREMENT_ID")))
        buildConfigField("String", "GA_API_SECRET", gradleStringLiteral(driveCredential("GA_API_SECRET")))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 车机和模拟器都是 arm64，只编这一种，OpenSSL 从源码编一次就够
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    buildTypes {
        release {
            // 使用签名配置
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.cardview)

    // JSON 解析
    implementation("com.google.code.gson:gson:2.10.1")

    // Glide 图片加载库（用于缓存和优化缩略图加载）
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // WorkManager 定时任务（用于保活）
    implementation("androidx.work:work-runtime:2.9.0")

    // 「发送到手机」：局域网内起一个 HTTP 服务 + 生成二维码。
    // 两个都是上游 EVCam 用过的，许可证与 GPL-3.0 兼容：
    // NanoHTTPD 是 BSD-3-Clause，ZXing 是 Apache-2.0。
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.1")
    // 远程观看：车机拨出到 7xDash 的 /ws/car，有人看的时候才推 JPEG。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 设置界面（PreferenceScreen）
    implementation("androidx.preference:preference:1.2.1")
    // 两栏设置的左右分栏（androidx 自己的 PreferenceHeaderFragmentCompat 也用它）
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")

    // AirPlay 接收的音频输出（原生库通过 prefab 链接）
    implementation("com.google.oboe:oboe:1.9.3")
    // AirPlay 只投视频（App 里的 AirPlay 按钮）：iPhone 给一个 HLS 地址，用 ExoPlayer 播
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    // 投视频时 YouTube 的分段走车机的 Wi-Fi（有的话），不走车机自己的流量：用 OkHttp 绑到 Wi-Fi 网络
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    // Announcements (beta): offline English voice. sherpa-onnx (Apache-2.0) is not on Maven Central,
    // so its release AAR is fetched once into .gradle/ and checked against a pinned hash
    implementation(files(sherpaOnnxAar()))
    // Unpacks the downloaded voice (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Where did I park: read floor / bay signs from the front and side cameras on the car.
    // Bundled Latin model, so it works offline and without Play services.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

/**
 * sherpa-onnx's Android AAR from its GitHub release, downloaded on first build and cached in the
 * project's .gradle/ (git-ignored). A file with any other hash is thrown away and the build fails.
 */
fun sherpaOnnxAar(): File {
    val version = "1.13.8"
    val sha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
    val aar = rootProject.file(".gradle/sherpa-onnx/sherpa-onnx-$version.aar")
    fun hash(f: File): String = MessageDigest.getInstance("SHA-256")
        .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (aar.isFile && hash(aar) == sha256) {
        return aar
    }
    aar.parentFile.mkdirs()
    val part = File(aar.path + ".part")
    URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar")
        .toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val got = hash(part)
    if (got != sha256) {
        part.delete()
        throw GradleException("sherpa-onnx-$version.aar hash $got, expected $sha256")
    }
    part.renameTo(aar)
    return aar
}
