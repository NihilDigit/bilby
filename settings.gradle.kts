pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bilby"
include(":app")

// 弹幕引擎在隔壁仓库(https://github.com/NihilDigit/tdanmaku),两个仓库放同级目录。
// 库还没发布,而且两边经常一起改,所以走 composite build 而不是 mavenLocal 的 SNAPSHOT:
// 改库的源码在这边立刻生效,不用每次 publish 一遍。
//
// 不需要写 dependencySubstitution —— 被包含的构建声明了 group `dev.nihildigit`、
// rootProject.name `tdanmaku`,Gradle 按这对坐标自动替换掉 app 里那行外部依赖。
includeBuild("../tdanmaku")
