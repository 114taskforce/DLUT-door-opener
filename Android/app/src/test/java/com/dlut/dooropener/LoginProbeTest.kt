package com.dlut.dooropener

import com.dlut.dooropener.net.DesCipher
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PC 端登录探针:用真实凭据(环境变量 DOOR_ACC / DOOR_PW)逐变体复现
 * 「GET 登录页 → rsa POST → 读 Location」的固件流程,定位 invalid request 的成因。
 * 运行:.\gradlew.bat :app:testDebugUnitTest --tests com.dlut.dooropener.LoginProbeTest -i
 */
class LoginProbeTest {

    private val sso = "https://sso.dlut.edu.cn"
    private val loginUrl = "$sso/cas/login?service=http://menjin.dlut.edu.cn/cser/static/menjin/index.html"

    private fun client(vararg preset: Pair<String, String>): Pair<OkHttpClient, MutableList<Cookie>> {
        val jar = mutableListOf<Cookie>()
        val c = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    for (n in cookies) {
                        jar.removeAll { it.name == n.name && it.domain == n.domain }
                        jar.add(n)
                    }
                }

                override fun loadForRequest(url: HttpUrl) =
                    jar.filter { it.matches(url) } + preset.map { (k, v) ->
                        Cookie.Builder().name(k).value(v).hostOnlyDomain("sso.dlut.edu.cn").path("/").build()
                    }
            })
            .build()
        return c to jar
    }

    /** 返回 true = 拿到 menjin 跳转(登录成功) */
    private fun runVariant(
        name: String,
        acc: String,
        pw: String,
        ua: String,
        referer: String?,
        origin: String?,
        presetCookies: List<Pair<String, String>> = emptyList(),
        preLogout: Boolean = false,
        withCodeParam: String? = null,
        dumpCodeInput: Boolean = false,
    ): Boolean {
        val (c, _) = client(*presetCookies.toTypedArray())
        if (preLogout) {
            runCatching {
                c.newCall(Request.Builder().url("$sso/cas/logout").header("User-Agent", ua).build())
                    .execute().use { println("[$name] pre-logout: ${it.code}") }
            }
        }
        val page = c.newCall(Request.Builder().url(loginUrl).header("User-Agent", ua).build())
            .execute().use { it.body?.string().orEmpty() }
        val lt = Regex("name=\"lt\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: ""
        val exec = Regex("name=\"execution\" value=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: ""
        if (lt.isEmpty()) {
            println("[$name] 登录页无 lt —— 可能已被视为已登录(302 后内容),len=${page.length}")
            return false
        }
        if (dumpCodeInput) {
            val names = Regex("name=\"([^\"]+)\"").findAll(page).map { it.groupValues[1] }
                .distinct().joinToString(",")
            val codeVisible = Regex("id=\"code\"[^>]*type=\"text\"|<input[^>]*name=\"code\"").containsMatchIn(page)
            println("[$name] 登录页字段=[$names] 含code输入=$codeVisible len=${page.length}")
        }
        val rsa = DesCipher.strEnc(acc + pw + lt)
        var fb = FormBody.Builder()
            .add("rsa", rsa)
            .add("ul", acc.length.toString())
            .add("pl", pw.length.toString())
            .add("sl", "0")
            .add("lt", lt)
            .add("execution", exec)
            .add("_eventId", "submit")
        if (withCodeParam != null) fb = fb.add("code", withCodeParam)
        val rb = Request.Builder().url(loginUrl).header("User-Agent", ua).post(fb.build())
        if (origin != null) rb.header("Origin", origin)
        if (referer != null) rb.header("Referer", referer)
        c.newCall(rb.build()).execute().use { resp ->
            val loc = resp.header("Location")
            if (loc != null && loc.contains("menjin")) {
                println("[$name] ✅ 登录成功 302 → ${loc.take(80)}")
                return true
            }
            val body = resp.body?.string().orEmpty()
            val err = Regex("id=\"errormsghide\"[^>]*>([^<]{0,200})<").find(body)?.groupValues?.get(1)?.trim()
            println("[$name] ❌ code=${resp.code} Location=$loc err=[$err] len=${body.length}")
            if (body.length < 3000) println("[$name] 小页面全文:\n${body.replace("\r", "")}")
        }
        return false
    }

    @Test
    fun jsCompare() {
        val cases = listOf(
            "a" to "A62B4F77D5F8C6C7",
            "abcd" to "A9CF2704230383D1",
            "abcdefghij" to "A9CF2704230383D1EEE0EB174D2B1826B7B9AA475B58A378",
            "testuserMyPassLT-123-x-cas" to
                "D8D35E5019288C41EB6D16D13160C8DCB72739C23A2D0081BBE41E1C36D7E4325B6830E26368DDFC8A9A9EDC8FFFAFA19A216BCFE6054686",
        )
        for ((input, expected) in cases) {
            val mine = DesCipher.strEnc(input)
            println("input=${input.take(12)}... equal=${mine.equals(expected, true)} mine=$mine")
        }
    }

    @Test
    fun probe() {
        // 凭据来源:环境变量 DOOR_ACC/DOOR_PW,或项目根 probe.txt(第一行账号,第二行密码)
        var acc = System.getenv("DOOR_ACC").orEmpty()
        var pw = System.getenv("DOOR_PW").orEmpty()
        if (acc.isEmpty() || pw.isEmpty()) {
            val f = listOf("probe.txt", "env", "../probe.txt", "../env", "../../probe.txt", "../../env")
                .map { java.io.File(it) }.firstOrNull { it.exists() }
            if (f != null) {
                for (line in f.readLines()) {
                    val idx = line.indexOf('=')
                    if (idx <= 0) continue
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    when (k) {
                        "account", "acc", "user", "username", "door_acc" -> acc = v
                        "password", "pw", "pass", "door_pw" -> pw = v
                    }
                }
                // 兼容纯两行格式(第一行账号,第二行密码)
                if (acc.isEmpty() || pw.isEmpty()) {
                    val lines = f.readLines().filter { it.isNotBlank() }
                    if (acc.isEmpty() && lines.isNotEmpty() && !lines[0].contains('=')) acc = lines[0].trim()
                    if (pw.isEmpty() && lines.size > 1 && !lines[1].contains('=')) pw = lines[1].trim()
                }
            }
        }
        assumeTrue("跳过:未提供 DOOR_ACC/DOOR_PW 或 probe.txt", acc.isNotEmpty() && pw.isNotEmpty())
        println("账号长度=${acc.length} 密码长度=${pw.length} 纯ASCII=${pw.all { it.code < 128 }}")
        val full = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        runVariant("V1 固件形态", acc, pw, "Mozilla/5.0", "$sso/cas/login", sso)
        // 对照:故意错密码。若报 "Incorrect username and password" 而 V1 报 invalid request,
        // 说明 invalid request 发生在凭据比对成功之后(策略拦截),而非请求形态问题
        // (V9 对照已完成使命:证明新加密被正确解密。暂注释减少请求量)
        // runVariant("V9 故意错密码", acc, pw + "Xx", "Mozilla/5.0", "$sso/cas/login", sso)
        Thread.sleep(3000)
        runVariant("V4 先logout再登", acc, pw, "Mozilla/5.0", "$sso/cas/login", sso, preLogout = true)
    }
}
