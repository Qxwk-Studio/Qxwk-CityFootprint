// 单模块工程：整套安卓工程就住在 app/ 下（仓库根另有 frontend/ 与 backend/），
// 所以 com.android.application 直接挂在**根项目**上，没有 :app 子模块 ——
// 源集、assets、build.gradle.kts 全在 app/ 这一层（app/src/main/...）。
// 这样 CI 里 working-directory: app 下的 ./gradlew，与仓库根相对的 app/gradlew、
// app/build/outputs/... 指的是同一层，不会出现 app/app/... 这种双层路径。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CityFootprint"