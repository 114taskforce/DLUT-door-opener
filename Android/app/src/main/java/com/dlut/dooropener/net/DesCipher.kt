package com.dlut.dooropener.net

/**
 * CAS 登录 rsa 字段:逐行移植学校页面 /cas/comm/js/des.js 的 strEnc(data,"1","2","3")。
 *
 * 重要:该 JS 是 2006 年 "Guapo DESCore" 变体,S 盒/IP/FP/P/E/PC1/PC2 均与标准 DES 相同,
 * 但密钥编排移位表为 loop=[1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1] —— 第 15 轮移位 2 位
 * (标准 DES 是 1 位),导致后两轮子密钥与标准 DES 不同、整块密文不同。
 * 因此不能用 javax.crypto 的标准 DES 复现(旧实现即错在这),服务端解密只认这套变体。
 *
 * 语义细节(与 JS 逐一对应):
 * - 每 4 个字符(UTF-16 code unit)一 64bit 块,每字符占 16 bit 大端;不足 4 字符零填充
 * - 每块依次用 key"1"、key"2"、key"3" 各做一次 enc(每把 key 经 getKeyBytes 得 1 个 64bit 密钥)
 * - 输出大写 hex 拼接
 */
object DesCipher {

    /** 非标准 DES 的每轮移位量(第 15 项为 2,标准表是 1)——保留厂商 bug */
    private val LOOP = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    fun strEnc(data: String): String {
        if (data.isEmpty()) return ""
        val key1 = strToBt("1")
        val key2 = strToBt("2")
        val key3 = strToBt("3")
        val out = StringBuilder((data.length / 4 + 1) * 16)
        var i = 0
        while (i < data.length) {
            val chunk = data.substring(i, minOf(i + 4, data.length))
            var bt = strToBt(chunk)
            bt = enc(bt, key1)
            bt = enc(bt, key2)
            bt = enc(bt, key3)
            out.append(bt64ToHex(bt))
            i += 4
        }
        return out.toString()
    }

    /** strToBt:≤4 字符 → 64bit 数组(16bit 大端/字符,右侧零填充) */
    private fun strToBt(str: String): IntArray {
        val bt = IntArray(64)
        val len = minOf(4, str.length)
        for (i in 0 until len) {
            val k = str[i].code
            for (j in 0 until 16) {
                bt[16 * i + j] = (k ushr (15 - j)) and 1
            }
        }
        return bt
    }

    private fun bt64ToHex(byteData: IntArray): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            var v = 0
            for (j in 0 until 4) v = v * 2 + byteData[i * 4 + j]
            sb.append(v.toString(16).uppercase())
        }
        return sb.toString()
    }

    /** 标准 IP(以公式表达,与 JS initPermute 相同的交错取位) */
    private fun initPermute(d: IntArray): IntArray {
        val out = IntArray(64)
        for (i in 0 until 4) {
            for (k in 0 until 8) {
                out[i * 8 + k] = d[(7 - k) * 8 + (2 * i + 1)]
                out[i * 8 + k + 32] = d[(7 - k) * 8 + (2 * i)]
            }
        }
        return out
    }

    /** 标准 E 扩展 */
    private fun expandPermute(r: IntArray): IntArray {
        val out = IntArray(48)
        for (i in 0 until 8) {
            out[i * 6] = if (i == 0) r[31] else r[i * 4 - 1]
            out[i * 6 + 1] = r[i * 4]
            out[i * 6 + 2] = r[i * 4 + 1]
            out[i * 6 + 3] = r[i * 4 + 2]
            out[i * 6 + 4] = r[i * 4 + 3]
            out[i * 6 + 5] = if (i == 7) r[0] else r[i * 4 + 4]
        }
        return out
    }

    /** 标准 S 盒 */
    private val SBOX = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private fun sBoxPermute(e: IntArray): IntArray {
        val out = IntArray(32)
        for (m in 0 until 8) {
            val i = e[m * 6] * 2 + e[m * 6 + 5]
            val j = e[m * 6 + 1] * 8 + e[m * 6 + 2] * 4 + e[m * 6 + 3] * 2 + e[m * 6 + 4]
            val v = SBOX[m][i * 16 + j]
            out[m * 4] = (v ushr 3) and 1
            out[m * 4 + 1] = (v ushr 2) and 1
            out[m * 4 + 2] = (v ushr 1) and 1
            out[m * 4 + 3] = v and 1
        }
        return out
    }

    /** 标准 P */
    private val P_PERM = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    private fun pPermute(s: IntArray): IntArray =
        IntArray(32) { s[P_PERM[it]] }

    /** 标准 FP(IP 逆) */
    private val FP_PERM = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )

    private fun finallyPermute(end: IntArray): IntArray =
        IntArray(64) { end[FP_PERM[it]] }

    /** PC1(公式形)+ 非标准 LOOP + PC2 —— 与 JS generateKeys 一致 */
    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private fun generateKeys(keyByte: IntArray): Array<IntArray> {
        val key = IntArray(56)
        for (i in 0 until 7) {
            for (j in 0 until 8) {
                key[i * 8 + j] = keyByte[8 * (7 - j) + i]
            }
        }
        val keys = Array(16) { IntArray(48) }
        for (i in 0 until 16) {
            repeat(LOOP[i]) {
                val tempLeft = key[0]
                val tempRight = key[28]
                for (k in 0 until 27) {
                    key[k] = key[k + 1]
                    key[28 + k] = key[29 + k]
                }
                key[27] = tempLeft
                key[55] = tempRight
            }
            for (m in 0 until 48) keys[i][m] = key[PC2[m]]
        }
        return keys
    }

    private fun xor(a: IntArray, b: IntArray): IntArray =
        IntArray(a.size) { a[it] xor b[it] }

    /** 单块 16 轮 Feistel(厂商变体密钥表) */
    private fun enc(dataByte: IntArray, keyByte: IntArray): IntArray {
        val keys = generateKeys(keyByte)
        val ipByte = initPermute(dataByte)
        val ipLeft = IntArray(32) { ipByte[it] }
        val ipRight = IntArray(32) { ipByte[32 + it] }
        for (i in 0 until 16) {
            val tempLeft = ipLeft.copyOf()
            System.arraycopy(ipRight, 0, ipLeft, 0, 32)
            val tempRight = xor(
                pPermute(sBoxPermute(xor(expandPermute(ipRight), keys[i]))),
                tempLeft,
            )
            System.arraycopy(tempRight, 0, ipRight, 0, 32)
        }
        val finalData = IntArray(64)
        for (i in 0 until 32) {
            finalData[i] = ipRight[i]
            finalData[32 + i] = ipLeft[i]
        }
        return finallyPermute(finalData)
    }
}
