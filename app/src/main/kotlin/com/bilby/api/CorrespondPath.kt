package com.bilby.api

import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Cookie 刷新流程第一步:把毫秒时间戳加密成一个路径,再去 www.bilibili.com/correspond/1/<path>
 * 换 refresh_csrf。算法逆向自官方首页的 wasm,公钥与 OAEP 参数一个都不能改:
 * 主 hash 与 MGF1 都是 SHA-256(不是常见的 SHA-1 默认值),输出小写 hex。
 *
 * 想验证本地实现对不对,可以拿同一个时间戳对比 https://wasm-rsa.vercel.app/api/rsa?t=<ts>
 */
object CorrespondPath {

    private const val PUBLIC_KEY_BASE64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
            "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
            "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
            "JNrRuoEUXpabUzGB8QIDAQAB"

    fun of(timestampMillis: Long): String {
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT,
                ),
            )
        }
        return cipher.doFinal("refresh_$timestampMillis".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
