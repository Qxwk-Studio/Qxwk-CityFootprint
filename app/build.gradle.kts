// City Footprint · 安卓端构建文件。
// 这是**根项目**的 build 文件（见 settings.gradle.kts 的说明）：application 插件挂在这里，
// 源集就是同级的 src/main。技术栈跟同工作室的 Class-Assistant/android 对齐：
// AGP 8.2.2 + Kotlin 1.9.22 + Gradle 8.2，compileSdk/targetSdk 34、minSdk 24、Java 17、XML View（viewBinding）。
plugins {
    id("com.android.application") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    // kotlinx.serialization 的编译器插件：序列化器在**编译期**生成，运行时不用反射，
    // 所以 R8 不需要额外 keep 规则（这正是不选 Gson/Moshi 的原因）
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
    // Room 的注解处理器走 KSP。版本号必须与上面的 Kotlin **完全对齐**（前缀就是 Kotlin 版本），
    // 对不上时 KSP 会直接拒绝启动（报 ksp 与 kotlin 版本不匹配），不是慢慢报错
    id("com.google.devtools.ksp") version "1.9.22-1.0.18"
}

android {
    namespace = "top.qxwkstudio.travel"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.qxwkstudio.travel"
        minSdk = 24
        targetSdk = 34
        // 这两行由 CI 用 sed 覆盖（.github/workflows/build-android.yml 的 Inject version 一步），
        // 本地手改也行；改完记得两边一致，否则发出来的包版本号与 Release 标签对不上。
        //
        // 2.0 的首发版本号刻意取 **20000 起跳**（不是 1）：1.x 线上最高是 103xx，
        // 覆盖安装要求 versionCode 严格大于线上值；直接从 20000 起跳也顺手给 2.0 划了版本段。
        versionCode = 20100
        versionName = "2.1.0"

        // MapLibre 的原生库四个 ABI 各 12 MB 左右，全打进去 APK 会从 8 MB 涨到 50 MB。
        // ABI 的取舍写在下面的 `splits { abi { include(...) } }` 里 —— 那里已经限定了
        // 只打 arm64-v8a 与 armeabi-v7a，**不能再写 ndk.abiFilters**：
        // 两者同时存在时 AGP 直接报 "Conflicting configuration ... cannot be present when
        // splits abi filters are set"。
    }

    // 按 ABI 拆包：两个 ABI 打在一个包里是 31.8 MB，拆开后 arm64 单包约 25 MB。
    // 注意 AGP 的 splits 是**全局**的（没有按 buildType 开关）：debug 也会拆成两个，
    // 装真机时取 CityFootprint-arm64-v8a-debug.apk 那个。CI 的改名步骤按 arm64 的名字取。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
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
        debug {
            // 并行内测：debug 包用 `top.qxwkstudio.travel.dev`，可以和线上 1.x 并排装在一台机器上
            // （否则正式签名与 debug 签名冲突，得先卸载 1.x）。release 包名不受影响，
            // 2.0 正式顶替时仍是 `top.qxwkstudio.travel`（见 2.0 工程口径文档 §2）。
            applicationIdSuffix = ".dev"
        }
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

// Room 的 schema 导出：每次改表，schemas/ 下会多一份带版本号的 DDL 快照，**要入库**。
// 它是迁移测试与「这一版到底改了什么列」的唯一凭据 —— 没有它，改表就只能靠人记。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // 依赖仍然压得很小：网络还是 JDK 的 HttpURLConnection（**不引** Retrofit/OkHttp），
    // JSON 从 org.json 换成 kotlinx-serialization（编译期生成、无反射、无需 keep 规则），
    // 异步从裸 Thread 换成协程 + lifecycle（页面销毁自动取消请求）。
    // 取舍理由写在 README 的「依赖取舍」。
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // 主页右上角那颗三横菜单现在是 PopupMenu（appcompat 自带），不再用侧滑抽屉，
    // 所以原先显式声明的 androidx.drawerlayout 已经移除 —— 别再顺手加回来。

    // 协程 + lifecycle：把「后台跑一段活、回主线程交结果」绑到页面生命周期上。
    // lifecycle-runtime-ktx 提供 lifecycleScope / viewLifecycleOwner.lifecycleScope，
    // 页面销毁时协程自动取消 —— 这正是各页不再需要 `_binding ?: return` 兜底的原因
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // JSON：@Serializable 数据类 + 编译期序列化器（见 data/Json.kt）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // 地图选型见 README：osmdroid 是纯原生 Android View 的 OSM 地图，不需要任何 API key，
    // 与「界面本身和地图都要原生」的要求一致。6.1.20 是它在 Maven Central 上的最后一个正式版
    // （上游仓库 2024-11 已归档，但包还在、API 稳定）。
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // 本地库（2.0 起是数据的唯一真相）：4 张表见 data/db/AppDatabase.kt。
    // room-ktx 提供挂起 DAO 的实现（CoroutinesRoom），没有它就只能在 DAO 上写阻塞调用。
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 地图：MapLibre Native（12.3.1 是「支持 PMTiles 且没有 Vulkan 硬要求」的最高点，见
    // 2.0 工程口径文档 §7.1）。PMTiles 读取在原生层，Kotlin 侧只负责拼 pmtiles://file:// 的 URL。
    implementation("org.maplibre.gl:android-sdk:12.3.1")

    // 纯 Kotlin 逻辑（成就判定 / visit_date 校验与展示 / 城市搜索）用 JVM 单测钉住：
    // 这些代码算错了不崩，只会悄悄少给一个成就、或把日期显示成别的样子。
    // 命令：./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}