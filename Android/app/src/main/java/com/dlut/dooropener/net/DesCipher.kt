package com.dlut.dooropener.net

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 移植 ESP32 固件 des.cpp 的 strEnc(data, "1", "2", "3")。
 *
 * C 实现要点(与 javax.crypto 的 DES 字节序一致,可直接复现):
 * - strToBt:每个字符占 16 位,高 8 位为 0 —— 即 64 位块中字符字节前各插一个 0x00:
 *   block = [0x00, c0, 0x00, c1, 0x00, c2, 0x00, c3],不足 4 字符的部分保持全 0
 * - 密钥 "1"/"2"/"3" 同规则转 8 字节:[0x00, 0x31, 0,0,0,0,0,0] 等
 * - 每 4 字符一块,依次用三个密钥做三次 ECB-DES(无填充),输出大写 hex 拼接
 */
object DesCipher {

    fun strEnc(data: String): String {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 4)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(4, bytes.size - i)
            var block = ByteArray(8)
            for (j in 0 until 4) {
                if (j < n) {
                    block[2 * j] = 0
                    block[2 * j + 1] = bytes[i + j]
                }
                // 不足 4 字符:对应 16 位保持 0(strToBt 的零填充)
            }
            block = desEncrypt(block, keyOf("1"))
            block = desEncrypt(block, keyOf("2"))
            block = desEncrypt(block, keyOf("3"))
            out.append(block.joinToString("") { b -> "%02X".format(b.toInt() and 0xFF) })
            i += 4
        }
        return out.toString()
    }

    /** strToBt(key, 1) 展开为 8 字节:16 位 = 0x00 + 字符,其余 48 位为 0 */
    private fun keyOf(ch: String): ByteArray =
        byteArrayOf(0, ch[0].code.toByte(), 0, 0, 0, 0, 0, 0)

    private fun desEncrypt(block: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(block)
    }
}
