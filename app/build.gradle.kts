// 城市足迹 · 安卓端构建文件。
// 这是**根项目**的 build 文件（见 settings.gradle.kts 的说明）：application 插件挂在这里，
// 源集就是同级的 src/main。技术栈跟同工作室的 Class-Assistant/android 对齐：
// AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.2，compileSdk/targetSdk 34、minSdk 24、Java 17、XML View（viewBinding）。
plugins {
    id("com.android.application") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

android {
    namespace = "top.qxwkstudio.travel"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.qxwkstudio.travel"
        minSdk = 24
        targetSdk = 34
        // 这两行由 CI 用 sed 覆盖（.github/workflows/build-android.yml 的 Inject version 一步），
        // 本地手改也行；改完记得两边一致，否则发出来的包版本号与 Release 标签对不上
        versionCode = 1
        versionName = "0.0.1"
    }

    // 正式签名：CI 从 Secrets 还原出 keystore，再用环境变量把路径与口令传进来。
    // 缺环境变量时不创建这个配置，release 会产出未签名的 app-release-unsigned.apk
    // （能编译、装不上）—— 既不影响 assembleDebug，也杜绝「本地没密钥却拿 debug 密钥签个包发出去」。
    // 注意环境变量名换成了 CF_ 前缀（模板是 CA_），与 Secrets 里的名字无关，只跟 CI 那几行 echo 对齐。
    val keystorePath = System.getenv("CF_KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CF_KEY_ALIAS")
                keyPassword = System.getenv("CF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 压缩 + 混淆：省体积，也是最基本的反编译门槛。
            // 风险点在网上查不到的那一类 —— 反射/XML 里按类名写死的组件会被改名后失效，
            // 所以 proguard-rules.pro 里 keep 住了地图那块按类名 inflate 的自定义 View。
            isMinifyEnabled = true
            // 资源收缩必须与代码收缩一起开：它靠的就是 R8 结果里「哪些资源还被引用」
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 缺 Secrets 时为 null → 出未签名包（CI 的改名一步会因为找不到 app-release.apk 而失败，
            // 正是想要的：绝不把未签名包当正式包传上去）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        // 「我的」页要显示 App 版本号（BuildConfig.VERSION_NAME）；
        // AGP 8 起 buildConfig 默认关闭，不显式打开就没有这个类
        buildConfig = true
    }
}

dependencies {
    // 依赖刻意压到最少：网络与 JSON 分别是 JDK 的 HttpURLConnection 与 Android 自带的 org.json，
    // 不引 Retrofit/OkHttp/Moshi（多一份依赖就多一份体积、R8 规则与升级风险），理由写在 README 的「依赖取舍」。
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // 地图选型见 README：osmdroid 是纯原生 Android View 的 OSM 地图，不需要任何 API key，
    // 与「界面本身和地图都要原生」的要求一致。6.1.20 是它在 Maven Central 上的最后一个正式版
    // （上游仓库 2024-11 已归档，但包还在、API 稳定）。
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // 纯 Kotlin 逻辑（成就判定 / visit_date 校验与展示 / 城市搜索）用 JVM 单测钉住：
    // 这些代码算错了不崩，只会悄悄少给一个成就、或把日期显示成别的样子。
    // 命令：./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}