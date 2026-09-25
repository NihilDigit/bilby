package dev.bilby.update

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.W32APIOptions
import java.io.File

/**
 * 查 Windows Installer 的登记,判断应用目录是不是由我们的 MSI 装的。便携版 zip 解出来的目录
 * 不在登记里,交给 msiexec 会另装一份到 LocalAppData,而不是更新用户正在用的那份。
 *
 * 按 UpgradeCode 找产品,再比 InstallLocation;UpgradeCode 由构建经系统属性传进来,与 MSI 同源。
 *
 * 走 JNA 而不是 Piko 那样的 FFM:FFM 要 JDK 22 的字节码,而这个模块编译到 17(Compose 自带的
 * ProGuard 读不了更新的 class 文件),JNA 本来就在依赖里(无边框全屏也用它)。
 */
internal object WindowsInstaller {
    /** 构建写进启动配置的 MSI UpgradeCode,见 desktop/build.gradle.kts。 */
    const val UPGRADE_CODE_PROPERTY = "bilby.upgrade-code"

    private const val ERROR_SUCCESS = 0
    private const val ERROR_MORE_DATA = 234
    private const val GUID_CHARS = 39

    @Suppress("FunctionName")
    private interface Msi : Library {
        // UINT MsiEnumRelatedProductsW(LPCWSTR lpUpgradeCode, DWORD dwReserved, DWORD iProductIndex, LPWSTR lpProductBuf)
        fun MsiEnumRelatedProductsW(upgradeCode: WString, reserved: Int, index: Int, productBuf: CharArray): Int

        // UINT MsiGetProductInfoW(LPCWSTR szProduct, LPCWSTR szAttribute, LPWSTR lpValueBuf, LPDWORD pcchValueBuf)
        fun MsiGetProductInfoW(product: WString, attribute: WString, valueBuf: CharArray, length: IntByReference): Int
    }

    fun isInstalledAt(upgradeCode: String, installDir: File): Boolean = runCatching {
        val expected = installDir.canonicalPath.trimEnd('\\')
        installLocations(upgradeCode).any { it.trimEnd('\\').equals(expected, ignoreCase = true) }
    }.getOrDefault(false)

    private fun installLocations(upgradeCode: String): List<String> {
        val msi = Native.load("msi", Msi::class.java, W32APIOptions.UNICODE_OPTIONS)
        val code = WString("{${upgradeCode.trim('{', '}').uppercase()}}")
        val property = WString("InstallLocation")
        val product = CharArray(GUID_CHARS)
        return buildList {
            var index = 0
            while (msi.MsiEnumRelatedProductsW(code, 0, index, product) == ERROR_SUCCESS) {
                val productCode = WString(Native.toString(product))
                var capacity = 512
                while (true) {
                    val buffer = CharArray(capacity)
                    val length = IntByReference(capacity)
                    when (msi.MsiGetProductInfoW(productCode, property, buffer, length)) {
                        ERROR_SUCCESS -> add(Native.toString(buffer))
                        // 返回的长度不含结尾的 0。
                        ERROR_MORE_DATA -> {
                            capacity = length.value + 1
                            continue
                        }
                    }
                    break
                }
                index++
            }
        }
    }
}
