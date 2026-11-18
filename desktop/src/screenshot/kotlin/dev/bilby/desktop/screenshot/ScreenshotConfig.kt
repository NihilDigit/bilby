package dev.bilby.desktop.screenshot

import java.io.File

internal enum class OutputFormat {
    AVIF,
    PNG,
    BOTH;

    companion object {
        fun parse(raw: String): OutputFormat = when (raw.trim().lowercase()) {
            "avif" -> AVIF
            "png" -> PNG
            "both" -> BOTH
            else -> throw IllegalArgumentException("Unsupported --format=$raw (expected avif|png|both)")
        }
    }
}

internal enum class ThemeFilter {
    LIGHT,
    DARK,
    BOTH;

    fun includesLight(): Boolean = this == LIGHT || this == BOTH
    fun includesDark(): Boolean = this == DARK || this == BOTH

    companion object {
        fun parse(raw: String): ThemeFilter = when (raw.trim().lowercase()) {
            "light" -> LIGHT
            "dark" -> DARK
            "both" -> BOTH
            else -> throw IllegalArgumentException("Unsupported --theme=$raw (expected light|dark|both)")
        }
    }
}

internal enum class SceneId(
    val cliName: String,
    val group: String,
    val includeInAll: Boolean = true,
) {
    FEED_WIDE("feed-wide", "feed"),
    LIBRARY_WIDE("library-wide", "library"),
    PROFILE_COMPACT("profile-compact", "profile", includeInAll = false),
    FEED_COMPACT("feed-compact", "feed"),
    AGENT_WIDE("agent-wide", "agent"),
    AGENT_RUNNING_COMPACT("agent-running-compact", "agent"),
    AGENT_ANSWER_COMPACT("agent-answer-compact", "agent"),
    VIDEO_WIDE("video-wide", "video"),
    IN_VIDEO_WIDE("in-video-wide", "video"),
    IN_VIDEO_COMPACT("in-video-compact", "video"),
    LISTEN_WIDE("listen-wide", "listen"),
    LYRICS_COMPACT("lyrics-compact", "listen"),
    SLEEP_TIMER_COMPACT("sleep-timer-compact", "listen");

    companion object {
        fun parseSelection(raw: String): Set<SceneId> {
            val tokens = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty() || "all" in tokens) return entries.filter { it.includeInAll }.toSet()
            val selected = LinkedHashSet<SceneId>()
            for (token in tokens) {
                val exact = entries.firstOrNull { it.cliName == token }
                if (exact != null) {
                    selected += exact
                    continue
                }
                val byGroup = entries.filter { it.group == token }
                if (byGroup.isNotEmpty()) {
                    selected += byGroup
                    continue
                }
                val valid = (entries.map { it.cliName } + listOf("all", "feed", "library", "agent", "video", "listen")).distinct()
                throw IllegalArgumentException("Unknown scene '$token'. Valid values: ${valid.joinToString(", ")}")
            }
            return selected
        }
    }
}

internal data class ScreenshotConfig(
    val scenes: Set<SceneId>,
    val agentQuery: String,
    val listenQuery: String,
    val videoBvid: String?,
    val listenBvid: String?,
    val outDir: File,
    val format: OutputFormat,
    val theme: ThemeFilter,
    val redactAccount: Boolean,
) {
    companion object {
        const val DEFAULT_AGENT_QUERY = "CS2 新加坡 Major 一阶段预测"
        const val DEFAULT_LISTEN_QUERY = "小Lin说 一口气了解外汇"

        fun parse(args: Array<String>): ScreenshotConfig {
            var scenesRaw = "all"
            var agentQuery = DEFAULT_AGENT_QUERY
            var listenQuery = DEFAULT_LISTEN_QUERY
            var videoBvid: String? = null
            var listenBvid: String? = null
            var outDir = File("docs/screenshots")
            var format = OutputFormat.AVIF
            var theme = ThemeFilter.BOTH
            var redactAccount = false

            var i = 0
            while (i < args.size) {
                val arg = args[i]
                fun nextValue(prefix: String): String {
                    if (arg.startsWith("$prefix=")) return arg.substring(prefix.length + 1)
                    if (i + 1 >= args.size) throw IllegalArgumentException("Missing value for $prefix")
                    return args[++i]
                }
                when {
                    arg == "--help" || arg == "-h" -> {
                        printUsage()
                        kotlin.system.exitProcess(0)
                    }
                    arg == "--redact-account" -> redactAccount = true
                    arg == "--scenes" || arg.startsWith("--scenes=") -> scenesRaw = nextValue("--scenes")
                    arg == "--agent-query" || arg.startsWith("--agent-query=") -> agentQuery = nextValue("--agent-query")
                    arg == "--listen-query" || arg.startsWith("--listen-query=") -> listenQuery = nextValue("--listen-query")
                    arg == "--video-bvid" || arg.startsWith("--video-bvid=") -> videoBvid = nextValue("--video-bvid").trim().takeIf { it.isNotEmpty() }
                    arg == "--listen-bvid" || arg.startsWith("--listen-bvid=") -> listenBvid = nextValue("--listen-bvid").trim().takeIf { it.isNotEmpty() }
                    arg == "--out-dir" || arg.startsWith("--out-dir=") -> outDir = File(nextValue("--out-dir"))
                    arg == "--format" || arg.startsWith("--format=") -> format = OutputFormat.parse(nextValue("--format"))
                    arg == "--theme" || arg.startsWith("--theme=") -> theme = ThemeFilter.parse(nextValue("--theme"))
                    else -> throw IllegalArgumentException("Unknown screenshot option: $arg")
                }
                i++
            }

            return ScreenshotConfig(
                scenes = SceneId.parseSelection(scenesRaw),
                agentQuery = agentQuery,
                listenQuery = listenQuery,
                videoBvid = videoBvid,
                listenBvid = listenBvid,
                outDir = outDir,
                format = format,
                theme = theme,
                redactAccount = redactAccount,
            )
        }

        private fun printUsage() {
            println(
                """
                Bilby Hidden-Desktop Screenshot Harness
                Options:
                  --scenes=<list>        Comma-separated scene IDs or groups (all, feed, library, agent, video, listen)
                  --agent-query=<text>   Query used for Search + Agent scenes (default: $DEFAULT_AGENT_QUERY)
                  --listen-query=<text>  Query used to resolve a subtitled video for Listen scenes (default: $DEFAULT_LISTEN_QUERY)
                  --video-bvid=<bvid>    Explicit BV ID for video-wide / in-video-* scenes (optional)
                  --listen-bvid=<bvid>   Explicit BV ID for listen-wide / lyrics-compact / sleep-timer-compact (optional)
                  --out-dir=<path>       Output directory (default: docs/screenshots)
                  --format=avif|png|both Output format (default: avif)
                  --theme=light|dark|both Theme capture filter (default: both)
                  --redact-account       Mask logged-in username, UID, avatar, bio, coins, and following count
                """.trimIndent(),
            )
        }
    }
}
