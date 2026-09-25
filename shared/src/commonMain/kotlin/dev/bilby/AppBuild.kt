package dev.bilby

/**
 * 构建期常量,由各平台入口在创建 [AppContainer] 之前填入。
 *
 * 共享模块拿不到 Android 的 BuildConfig:debug 与 release 是 :app 的变体,库只有一个变体,
 * LLM 默认凭据又只在 debug 变体里注入。于是由知道变体的那一方启动时告诉这里。
 */
object AppBuild {
    var debug: Boolean = false
        private set
    var versionName: String = "0.0.0-dev"
        private set
    /** 设置页展示用,也是区分 debug 与 release 安装的依据。 */
    var applicationId: String = "dev.bilby"
        private set
    var llmBaseUrl: String = ""
        private set
    var llmApiKey: String = ""
        private set

    fun init(
        debug: Boolean,
        versionName: String,
        applicationId: String,
        llmBaseUrl: String = "",
        llmApiKey: String = "",
    ) {
        this.debug = debug
        this.versionName = versionName
        this.applicationId = applicationId
        this.llmBaseUrl = llmBaseUrl
        this.llmApiKey = llmApiKey
    }
}
