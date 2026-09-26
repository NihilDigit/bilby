import groovy.json.JsonOutput
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// 与 :shared 同一个字节码版本。
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// mediamp 0.5.0 把 ui-test-junit4 误放在运行时作用域,会把 junit、truth、guava、coroutines-test
// 带进发行包。排除写在配置上而不是 mediamp 依赖上:它是 KMP 根坐标,经 available-at 跳到
// -desktop 变体后,依赖级的 exclude 不再生效。
configurations.configureEach {
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
}

val releaseVersionName: String = (findProperty("bilbyVersion") as String?) ?: "0.0.0-dev"

// 这个模块只是桌面的打包入口:窗口、平台对象与发行包。代码与界面在 :shared。
dependencies {
    implementation(project(":shared"))
    implementation(libs.jna.platform)
    runtimeOnly(compose.desktop.windows_x64)
}

// mpv 与 FFmpeg 的 DLL(约 55MB)解开放进应用资源目录,不走类路径。mediamp 的运行时包原本
// 放在类路径上时,每次启动后首次播放都把整包 DLL 解压到 %TEMP% 下一个新目录,DLL 被进程占着,
// deleteOnExit 删不掉,每运行一次就留一份。放进资源目录后安装时就位,两个版本之间逐字节相同,
// 增量更新不碰它们;启动时经 MpvMediampPlayer.prepareLibraries 指过去。
// 只做 x64,mediamp 的运行时包目前只有这一种。
val mpvRuntimeJar = configurations.detachedConfiguration(
    dependencies.create(libs.mediamp.mpv.runtime.windows.x64.get()),
).apply { isTransitive = false }
val bundledAppResources by tasks.registering(Sync::class) {
    from({ mpvRuntimeJar.map { zipTree(it) } }) {
        include("*.dll", "*.txt")
        into("mpv")
    }
    into(layout.buildDirectory.dir("appResources/common"))
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundledAppResources) }

// 热重载的运行任务(hotRun、hotDev)不走 prepareAppResources,也不设资源目录的系统属性,
// Main 的 useBundledMpvRuntime 找不到 DLL,播放器建不起来。补上这两样,指向同一份解好的 DLL。
//
// 去掉 NoDefaultCurrentDirectoryInExePath:应用里的重编译器在仓库根执行 `cmd /c gradlew.bat`,
// 这个变量设着时 cmd 不在当前目录找可执行文件,改了代码不会重载,日志里只有一句
// "'gradlew.bat' is not recognized"。它不是系统默认值,是启动 Gradle 的那个环境带进来的
// (Claude Code 在 Windows 上给子进程设它),一路传给了应用。
tasks.withType<JavaExec>().matching { it.name.startsWith("hot") }.configureEach {
    dependsOn(bundledAppResources)
    environment.remove("NoDefaultCurrentDirectoryInExePath")
    systemProperty(
        "compose.application.resources.dir",
        layout.buildDirectory.dir("appResources/common").get().asFile.absolutePath,
    )
}

// jlink 与 jpackage 默认用运行 Gradle 的那个 JDK,本机是 JBR 21。改用 Azul 的 25:
// Temurin 25 的发行包不带 jmods,jlink 无从取模块。
// 用 Provider 绑定而不写 application.javaHome:后者是 String,配置期就要解析,而只跑单测的
// CI 装的是 Temurin 21,同样会配置这个模块。放进 afterEvaluate:插件在它自己的 afterEvaluate
// 里给这些任务设 javaHome,先登记的会被盖掉。
val packagingJdkHome = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.AZUL
}.map { it.metadata.installationPath.asFile.absolutePath }
afterEvaluate {
    tasks.withType<AbstractJvmToolOperationTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractSuggestModulesTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach { jdkHome.set(packagingJdkHome) }
}

// MSI 的升级标识。换了它,已安装的版本会被当成另一个产品,升级变成并排安装。切勿修改。
// 可覆写只为在本机测试 MSI 升级:另起一个产品与安装目录,不碰已安装的 Bilby。
val msiUpgradeUuid = providers.gradleProperty("bilbyDesktopUpgradeUuid")
    .getOrElse("8481dd59-0107-4e44-bfc0-bc0dba075087")
val desktopPackageName = providers.gradleProperty("bilbyDesktopPackageName").getOrElse("Bilby")

compose.desktop {
    application {
        mainClass = "dev.bilby.desktop.MainKt"
        // JDK 24 起加载 JNI 库属受限操作,不声明会在每次启动时打印警告
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        jvmArgs += "-Dbilby.version=$releaseVersionName"
        // 应用内更新据此在 Windows Installer 的登记里判断自己是否由 MSI 安装
        jvmArgs += "-Dbilby.upgrade-code=$msiUpgradeUuid"
        providers.gradleProperty("bilbyDebug").orNull?.let { jvmArgs += "-Dbilby.debug=$it" }

        // **不跑 ProGuard。** 它的预校验器给 PlayerShell 这种大 Composable 重算栈帧时出错(局部
        // 变量里的 long 被推成 top),打包版一进播放页就 VerifyError;7.8.0 与 7.10.0 都一样,而
        // 关掉预校验不是出路,Java 7 起的 class 文件必须带栈帧。gradle run 不经过它,开发时测不出。
        // 代价是安装体积多约 50MB;反过来增量更新变小:各依赖是独立且逐字节不变的 jar,补丁只带
        // Bilby 自己的两个(见 UpdateArtifactsTask.isPatch),不再是合并后每次都变的整个 jar。
        buildTypes.release.proguard {
            isEnabled = false
        }
        nativeDistributions {
            // mpv 的 DLL 在这里,见上面的 bundledAppResources。`run` 与发行包都读这个目录。
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            // Compose 默认的 jlink 模块集之外补 jdk.unsupported(sun.misc.Unsafe)。
            modules("jdk.unsupported")
            targetFormats(TargetFormat.Msi)
            packageName = desktopPackageName
            // MSI 的版本号只认 x.y.z,不带 -dev 之类的后缀。
            packageVersion = releaseVersionName.substringBefore('-').takeIf { it != "0.0.0" } ?: "1.0.0"
            vendor = "NihilDigit"
            // MSI 按 en-us 生成,数据库代码页 1252 容不下汉字,WiX 报 LGHT0311;描述只能用 ASCII。
            description = "Open-source bilibili client"
            copyright = "Copyright (C) NihilDigit"
            windows {
                menuGroup = desktopPackageName
                // exe、快捷方式与「添加或删除程序」的图标,生成方式见 package/windows/README.md
                iconFile.set(project.file("package/windows/icon.ico"))
                upgradeUuid = msiUpgradeUuid
                // 按用户安装到 LocalAppData:免 UAC,应用内更新静默安装时也不必提权。
                perUserInstall = true
            }
        }
    }
}

// jpackage 的 MSI 先卸旧版、单独提交,再装新版:新版装失败时旧版已经没了。打完即把卸载挪进
// 安装事务,失败时整体回滚,理由见脚本。用 pwsh 跑:Windows PowerShell 按 ANSI 读无 BOM 的脚本。
val transactionalUpgradeScript = file("package/windows/transactional-upgrade.ps1")
tasks.matching { it.name == "packageReleaseMsi" }.configureEach {
    // 配置缓存开着,doLast 只能捕获局部值;引用脚本顶层属性会连整个脚本对象一起序列化。
    val script = transactionalUpgradeScript
    val msiDir = layout.buildDirectory.dir("compose/binaries/main-release/msi").get().asFile
    doLast {
        msiDir.listFiles { f -> f.extension == "msi" }.orEmpty().forEach { msi ->
            val exit = ProcessBuilder("pwsh", "-NoProfile", "-File", script.absolutePath, "-Msi", msi.absolutePath)
                .inheritIO()
                .start()
                .waitFor()
            check(exit == 0) { "改写 ${msi.name} 的安装序列失败(pwsh 退出码 $exit)" }
        }
    }
}

/**
 * 应用内增量更新的两个 Release 附件:应用目录里每个文件的清单(路径、大小、SHA-256、修改时间),
 * 与只含易变文件的 app.zip。两次构建之间变的只有启动器 exe(版本资源)、app 下的 jar、Bilby.cfg
 * 与 .jpackage.xml,jlink 出的运行时与 app/resources/mpv 下的 DLL 逐字节相同,客户端据清单判断
 * 能否只换这几个。
 * 修改时间记在清单里而不是只靠 zip:zip 的时间戳按本地时区存,CI 与用户的时区不同。
 */
abstract class UpdateArtifactsTask : DefaultTask() {
    @get:InputDirectory
    abstract val appImage: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val artifactPrefix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val root = appImage.get().asFile
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val files = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
        val entries = files.map { (path, file) ->
            linkedMapOf(
                "path" to path,
                "size" to file.length(),
                "sha256" to sha256(file),
                "mtime" to file.lastModified(),
                "patch" to isPatch(path),
            )
        }
        val prefix = artifactPrefix.get()
        out.resolve("$prefix-files.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("version" to version.get(), "files" to entries))),
        )
        ZipOutputStream(out.resolve("$prefix-app.zip").outputStream().buffered()).use { zip ->
            files.filter { isPatch(it.first) }.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path).apply {
                    lastModifiedTime = FileTime.fromMillis(file.lastModified())
                })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * 每次构建都会变的文件:根目录的启动器、Bilby 自己的两个 jar、启动配置。依赖的 jar 不算:
     * 版本不变时它们逐字节相同;依赖升级过的版本,本机缺新 jar,canPatch 判不过,走整包重装。
     * jar 名带内容哈希(`shared-desktop-<哈希>.jar`),换了内容就是一个新文件名,旧的留在目录里,
     * 不在 Bilby.cfg 的类路径上,不会被加载。
     */
    private fun isPatch(path: String): Boolean =
        Regex("[^/]+\\.exe").matches(path) ||
            Regex("app/(desktop|shared-desktop)-[0-9a-f]+\\.jar").matches(path) ||
            Regex("app/[^/]+\\.cfg").matches(path) ||
            path == "app/.jpackage.xml"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// CI 在打出 MSI 的同一次调用里跑它,清单与 MSI 取自同一份应用目录。
tasks.register<UpdateArtifactsTask>("packageReleaseUpdate") {
    dependsOn("createReleaseDistributable")
    appImage = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    version = releaseVersionName
    artifactPrefix = "bilby-windows-x64-$releaseVersionName"
    outputDir = layout.buildDirectory.dir("compose/binaries/main-release/update")
}
