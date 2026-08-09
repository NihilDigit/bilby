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

// 弹幕引擎(https://github.com/NihilDigit/tdanmaku)已发布到 Maven Central,按坐标解析,
// 见版本目录里的 `tdanmaku`。
//
// 这里曾经是一行 `includeBuild("../tdanmaku")`,让开发期直接吃隔壁仓库的源码。代价是
// **一个干净的 clone 编不过**:CI 只 checkout 这个仓库,那行会指向一个不存在的目录,
// 打 v tag 就失败。要临时回到那种模式(两边一起改的时候)就把它加回来,发版前记得撤掉。
