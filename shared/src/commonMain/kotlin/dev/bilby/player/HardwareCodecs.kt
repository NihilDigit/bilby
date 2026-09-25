package dev.bilby.player

/**
 * 本机能硬解的 B 站 codecid,选流时据此排除会落到软解的那几条,见 [selectStreams]。
 *
 * Android 上逐个查 MediaCodec;桌面上 mpv 按显卡能力自己在硬解与软解之间回退,
 * 不存在"选到一条就只能软解"的死路,三种编码都放行。
 */
expect val hardwareDecodableCodecIds: Set<Int>
