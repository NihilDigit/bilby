import groovy.json.JsonOutput
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.desktop.application.dsl.AotMode
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// 编译用 Azul 25,与打包、运行同一份 JDK:自绘标题栏经 FFM(java.lang.foreign,JDK 22 转正)
// 直调 Win32,编译期要看得到这套 API。字节码仍与 :shared 同为 17。
kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AZUL
    }
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
    runtimeOnly(libs.compose.mp.desktop.windows.x64)
}

// mpv 与 FFmpeg 的 DLL(约 55MB)解开放进应用资源目录,不走类路径。mediamp 的运行时包原本
// 放在类路径上时,每次启动后首次播放都把整包 DLL 解压到 %TEMP% 下一个新目录,DLL 被进程占着,
// deleteOnExit 删不掉,每运行一次就留一份。放进资源目录后安装时就位,两个版本之间逐字节相同,
// 增量更新不碰它们;启动时经 MpvMediampPlayer.prepareLibraries 指过去。
// 只做 x64,mediamp 的运行时包目前只有这一种。
val mpvRuntimeJar = configurations.detachedConfiguration(
    dependencies.create(libs.mediamp.mpv.runtime.windows.x64.get()),
).apply { isTransitive = false }
// zstd-jni 同样默认把 DLL 解压到 %TEMP%,改由 DesktopAppUpdater 经 ZstdNativePath 指过来。
// CI 也据旧版清单里有没有这个 DLL 判断旧版客户端会不会用差分,见 .github/scripts/delta-updates.sh。
val zstdJniJar = configurations.detachedConfiguration(
    dependencies.create("${libs.zstd.jni.get()}:win_amd64"),
).apply { isTransitive = false }
val bundledAppResources by tasks.registering(Sync::class) {
    from({ mpvRuntimeJar.map { zipTree(it) } }) {
        include("*.dll", "*.txt")
        into("mpv")
    }
    from({ zstdJniJar.map { zipTree(it) } }) {
        include("**/*.dll")
        eachFile { path = "zstd/$name" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("appResources/common"))
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundledAppResources) }

// jlink、jpackage 与 ProGuard 默认用运行 Gradle 的那个 JDK,本机是 JBR 21。改用 Azul 的 25:
// Temurin 25 的发行包不带 jmods,jlink 无从取模块,ProGuard 也读不到 java.lang.Object。
// 用 Provider 绑定而不写 application.javaHome:后者是 String,配置期就要解析,而只跑单测的
// CI 装的是 Temurin 21,同样会配置这个模块。放进 afterEvaluate:插件在它自己的 afterEvaluate
// 里给这些任务设 javaHome,先登记的会被盖掉。
val packagingLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.AZUL
}
val packagingJdkHome = packagingLauncher.map { it.metadata.installationPath.asFile.absolutePath }
afterEvaluate {
    tasks.withType<AbstractJvmToolOperationTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractProguardTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractSuggestModulesTask>().configureEach { javaHome.set(packagingJdkHome) }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach { jdkHome.set(packagingJdkHome) }
    // 开发时的 run 也用同一个运行时:发行包里带的就是它,两边行为不会因运行时而分家。
    // 不用 JetBrains Runtime 与热重载:只有它支持增删方法的类重定义,换来的是开发与发行各跑
    // 一个运行时,而发行包换 JBR 得不到 Zulu 拿不到的东西(自绘标题栏同样能在 Zulu 25 上用
    // FFM 做),却要多带 10MB 用不上的字体,更新也更频繁。
    // 不能推迟到 doFirst:执行前 javaLauncher 已按旧的 executable 定值,届时再改会报两者不匹配。
    tasks.named<JavaExec>("run") { executable(packagingLauncher.get().executablePath.asFile) }
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
        // AOT 缓存里不存机器码。JDK 25 会把训练时生成的调用适配代码与桩代码一并存进 app.aot,
        // 换一台机器也不核对 CPU 特性:CI runner 支持 AVX-512,缓存里的适配代码用了 EVEX 指令,
        // 装到不支持的 CPU(例如 12 代酷睿)上随机报 EXCEPTION_ILLEGAL_INSTRUCTION,崩在
        // AdapterBlob(Piko 上查实过)。训练与运行都读这里的参数,两处一起关掉;类的加载与链接
        // 照常缓存,启动加速的大头仍在。
        jvmArgs += listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTAdapterCaching", "-XX:-AOTStubCaching")

        // ProGuard 只裁剪依赖:不优化、不混淆,Bilby 自己的 jar 裁完换回原件(见下面
        // proguardReleaseJars 那一段,它的预校验器会把 PlayerShell 的栈帧算错)。体积大头是
        // 依赖里没用到的图标与 Compose 组件,jar 从 90MB 裁到 39MB。
        // 不优化:换来的体积很小,打包却慢十几分钟。不混淆:崩溃栈要读得懂,依赖里按名字反射的
        // 地方也少出错。不合并输出:换回原件要靠输入输出一一对应。
        buildTypes.release.proguard {
            isEnabled = true
            version = "7.10.0"
            optimize = false
            obfuscate = false
            joinOutputJars = false
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        // JDK 25 的 AOT 缓存(JEP 483/514):打包时把应用跑一遍训练,启动路径上的类预先加载、
        // 链接好存进 app/app.aot,启动时直接映射。训练进程由 AotTraining.kt 在开窗后自行退出。
        // 缓存对不上时(类路径上的 jar 或运行时变了)JVM 往标准输出写一行错误,照常启动,只是慢。
        buildTypes.release.aot {
            mode = AotMode.AotPrebuild
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

// ProGuard 跑完,把 Bilby 自己的 jar 换回未经处理的原件。
//
// 它的预校验器会重算每个方法的栈帧,而在 PlayerShell 这种大 Composable 上合并出错:某个分支
// 目标处,一条路径上没赋值的 long 局部变量被写成 long 而不是 top,打包版一进播放页就
// VerifyError("Inconsistent stackmap frames",局部变量是 long 而当前帧是 top)。7.8.0 与 7.10.0
// 都一样,关掉优化也一样;-dontpreverify 则把所有栈帧一并剥掉,两千多个类都过不了校验。
// 只裁剪、不混淆时,我们自己的类经过它本来就只是栈帧被重算一遍(proguard-rules.pro 里整个保留
// dev.bilby),换回 kotlinc 写的原件等价而正确。依赖照常裁剪,体积的大头在那里。
//
// 判据是输入 jar 在本仓库目录下:那就是这次构建出的模块 jar,其余来自 Gradle 缓存。成对的
// -injars / -outjars 取自插件生成的 jars-config.pro,不合并输出(joinOutputJars = false)
// 才有一一对应。发布前 VerifyClasses 逐类校验,兜住这一步漏掉的情况。
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    // 配置缓存开着,doLast 只能捕获局部值。
    val jarsConfig = layout.buildDirectory.file("compose/tmp/proguardReleaseJars/jars-config.pro").get().asFile
    val repositoryRoot = rootDir
    doLast {
        fun pathOf(line: String) = File(line.substringAfter('\'').substringBeforeLast('\'').replace("\\\\", "\\"))
        jarsConfig.readLines().zipWithNext()
            .filter { (a, b) -> a.startsWith("-injars ") && b.startsWith("-outjars ") }
            .map { (a, b) -> pathOf(a) to pathOf(b) }
            .filter { (input, _) -> input.startsWith(repositoryRoot) }
            .forEach { (input, output) -> input.copyTo(output, overwrite = true) }
    }
}

// AOT 缓存按类路径上每个 jar 的修改时间校验,差一毫秒也整份作废。MSI 的 cab 与 zip 只存到
// 偶数秒,安装后的 jar 时间被取整,缓存随之失效(Piko 实测 msiexec /a 解出的 jar 比训练时晚了
// 两秒)。训练之前先把 jar 的时间取整到偶数秒,打包前后就是同一个值。
tasks.matching { it.name == "createReleaseAotArchive" }.configureEach {
    // 配置缓存开着,doFirst 只能捕获局部值。
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/app").get().asFile
    doFirst {
        appDir.walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .forEach { it.setLastModified(it.lastModified() / 2000 * 2000) }
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
 * 与只含易变文件的 app.zip。两次构建之间变的只有启动器 exe(版本资源)、app 下的 jar、AOT 缓存、
 * Bilby.cfg 与 .jpackage.xml,jlink 出的运行时与 app/resources 下的 DLL 逐字节相同,客户端据清单
 * 判断能否只换这几个。CI 另为最近的几个版本各出一份 zstd 差分,见 .github/scripts/delta-updates.sh。
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
     * 每次构建都会变的文件:根目录的启动器、app 下所有 jar、AOT 缓存与启动配置。
     *
     * 依赖的 jar 也算,有两个原因。一是 ProGuard 每次都重写全部依赖:条目时间戳是打包那一刻,
     * 依赖与代码都没变的两次构建,jar 也逐字节不同;Bilby 的用法一变,裁出来的内容也跟着变。
     * 文件名却不变,名字里的哈希取自裁剪前的原件。二是 AOT 缓存记着类路径上每个 jar 的大小与
     * 修改时间,换了新的 app.aot 而留着旧版的 jar,缓存整份作废。依赖升级后文件名变了,新 jar
     * 随补丁装上,旧的留在目录里,不在 Bilby.cfg 的类路径上,不会被加载。
     * app.zip 因此装着全部 jar,下载量靠差分包压下来:差分里未变的 jar 只剩几百字节。
     */
    private fun isPatch(path: String): Boolean =
        Regex("[^/]+\\.exe").matches(path) ||
            Regex("app/[^/]+\\.(jar|aot|cfg)").matches(path) ||
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

// CI 在打出 MSI 的同一次调用里跑它,清单与 MSI 取自同一份应用目录,jar 的修改时间与 AOT 训练时一致。
tasks.register<UpdateArtifactsTask>("packageReleaseUpdate") {
    dependsOn("createReleaseDistributable")
    appImage = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    version = releaseVersionName
    artifactPrefix = "bilby-windows-x64-$releaseVersionName"
    outputDir = layout.buildDirectory.dir("compose/binaries/main-release/update")
}
