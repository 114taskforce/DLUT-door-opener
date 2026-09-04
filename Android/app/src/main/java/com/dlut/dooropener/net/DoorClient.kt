package com.dlut.dooropener.net

import android.util.Log
import com.dlut.dooropener.data.SettingsStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LoginException(message: String) : Exception(message)

/** 开门结果 */
data class OpenResult(val success: Boolean, val code: Int, val body: String)

/**
 * 门禁网络客户端 —— 移植 ESP32 固件 main.cpp 的协议:
 * CAS 登录获取 shfb-token → 带 AUTH-SIGN / AUTH-TIMESTAMP 签名 POST 开门。
 */
class DoorClient(
    private val settings: SettingsStore,
) {

    // ==================== 常量(接口变动时改这里) ====================

    companion object {
        const val TAG = "DoorClient"

        const val SSO_BASE = "https://sso.dlut.edu.cn"
        const val SERVICE = "http://menjin.dlut.edu.cn/cser/static/menjin/index.html"
        const val MENJIN_BASE = "http://menjin.dlut.edu.cn"

        const val PROJECT_CD = "DA_LIAN_LI_GONG_MENJIN"

        /** AUTH-SIGN 的签名密钥 */
        const val SIGN_SECRET = "#2323dsfadfewrasa3434#"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /**
         * ESP 固件用了 setInsecure();若 sso.dlut.edu.cn 证书不在系统信任链导致 TLS 失败,
         * 置为 true 跳过校验(降低安全性,仅建议自用时开启)
         */
        const val TRUST_ALL_CERTS = false

        /** randomString 的字符集(与 ESP 固件一致) */
        const val RAND_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val secureRandom = SecureRandom()

        /** 设备编号形如 DL-LY-114514:至少三段、只含字母数字和连字符(整串校验用) */
        val CODE_PATTERN = Regex("^[A-Za-z0-9]+(-[A-Za-z0-9]+){2,}$")

        /** 全文扫描用(无锚点):JSON 文本中部的设备编号也能找到 */
        val LOOSE_CODE_REGEX = Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+){2,}")

        /** JSON 里视为设备编号的键名 */
        val DEVICE_CODE_KEYS = setOf(
            "deviceCode", "deviceNo", "deviceId", "equipCode",
            "equipmentCode", "lockCode", "code", "roomCode",
        )

        /** 从接口返回解析设备编号:优先按 JSON 键名,兜底正则扫描全文 */
        fun parseDeviceCodes(text: String): List<String> {
            val codes = LinkedHashSet<String>()

            fun add(s: String) {
                val v = s.trim()
                if (CODE_PATTERN.matches(v) && v.any { it.isDigit() }) codes.add(v)
            }

            // 服务端响应可能带 UTF-8 BOM 或 JSONP 包装,先剥掉(trim 不处理 BOM)
            val cleaned = text.removePrefix("\uFEFF").trim()

            try {
                walkJson(JSONObject(cleaned), codes, ::add)
            } catch (e: Exception) {
                // 前后有杂质(JSONP 等):截取第一个 { 到最后一个 } 再试
                val s = cleaned.indexOf('{')
                val t = cleaned.lastIndexOf('}')
                if (s != -1 && t > s) {
                    try {
                        walkJson(JSONObject(cleaned.substring(s, t + 1)), codes, ::add)
                    } catch (ignored: Exception) {
                    }
                }
            }

            // 兜底:无锚点正则扫全文
            // (原文带 ^$ 的 pattern 在 find 语义下只匹配整个字符串,JSON 中部的编号永远找不到)
            if (codes.isEmpty()) {
                LOOSE_CODE_REGEX.findAll(cleaned).forEach { add(it.value) }
            }
            return codes.toList()
        }

        private fun walkJson(value: Any?, codes: MutableSet<String>, add: (String) -> Unit) {
            when (value) {
                is JSONObject -> for (key in value.keys()) {
                    val v = value.opt(key)
                    if (key in DEVICE_CODE_KEYS && v is String) add(v)
                    walkJson(v, codes, add)
                }
                is JSONArray -> for (i in 0 until value.length()) walkJson(value.opt(i), codes, add)
                is String -> add(value)
            }
        }
    }

    // ==================== Cookie 管理 ====================

    /** 内存 CookieJar,支持 JSON 序列化持久化到 SharedPreferences */
    private inner class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                // 同名同域全清(不管路径):防止「预置的路径/旧 cookie」与
                // 服务端新下发「路径/cas 新 cookie」并存,导致 Cookie 头重复、服务端读到旧值
                store.removeAll { it.name == c.name && it.domain == c.domain }
                store.add(c)
            }
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt in 1 until now }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store.filter { it.expiresAt > now }.filter { it.matches(url) }
        }

        fun get(host: String, name: String): String? =
            store.firstOrNull { it.name == name && (it.domain == host || host.endsWith("." + it.domain.removePrefix("."))) }?.value

        fun listAll(): List<Cookie> = store.toList()

        /** 直接把 name=value 以会话 cookie 形式加入(用于预置网页登录拿到的信任 cookie) */
        fun addRaw(host: String, name: String, value: String) {
            store.removeAll { it.name == name && it.domain == host }
            store.add(Cookie.Builder().name(name).value(value).hostOnlyDomain(host).path("/").build())
        }

        fun serialize(): String {
            val arr = JSONArray()
            for (c in store) {
                arr.put(
                    JSONObject()
                        .put("name", c.name)
                        .put("value", c.value)
                        .put("expiresAt", c.expiresAt)
                        .put("hostOnly", c.hostOnly)
                        .put("domain", c.domain)
                        .put("path", c.path)
                        .put("secure", c.secure)
                        .put("httpOnly", c.httpOnly)
                )
            }
            return arr.toString()
        }

        fun restore(json: String?) {
            if (json.isNullOrEmpty()) return
            try {
                val arr = JSONArray(json)
                store.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val b = Cookie.Builder()
                        .name(o.getString("name"))
                        .value(o.getString("value"))
                        .expiresAt(o.getLong("expiresAt"))
                        .path(o.getString("path"))
                    if (o.getBoolean("hostOnly")) b.hostOnlyDomain(o.getString("domain"))
                    else b.domain(o.getString("domain"))
                    if (o.getBoolean("secure")) b.secure()
                    if (o.getBoolean("httpOnly")) b.httpOnly()
                    store.add(b.build())
                }
            } catch (e: Exception) {
                Log.w(TAG, "恢复 cookie 失败", e)
            }
        }
    }

    private val cookieJar = MemoryCookieJar()

    // ==================== 接口地址 ====================

    private fun indexUrl() = "$MENJIN_BASE/cser/static/menjin/index.html"

    /** 开门接口 */
    private fun openUrl() = "$MENJIN_BASE/cser/device/info/command/sendRoomBatch"

    /** 设备列表接口 */
    private fun listUrl() = "$MENJIN_BASE/cser/medium/device/listWithRoom"

    val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (TRUST_ALL_CERTS) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        builder.build()
    }

    init {
        cookieJar.restore(settings.loadCookieJar())
    }

    // ==================== Token ====================

    /** 当前 jar 中的 shfb-token(可能已失效,失效时重新登录) */
    fun currentToken(): String? = cookieJar.get("menjin.dlut.edu.cn", "shfb-token")

    fun persistCookies() = settings.saveCookieJar(cookieJar.serialize())

    // ==================== CAS 登录 ====================

    /**
     * CAS 登录(与 ESP 固件 login() 相同流程):
     * 0. 预置网页登录拿到的信任设备 cookie(固件 COOKIE_INPUT 等价,用于二次认证)
     * 1. GET 登录页提取 lt / execution
     * 2. rsa = strEnc(账号+密码+lt, "1", "2", "3")
     * 3. POST 登录,OkHttp 自动跟随 302 到 menjin 换取 ticket
     * 4. 访问门禁首页使 shfb-token 落进 cookie
     * 若信任 cookie 有效,GET 会被 CAS 直接 302 放行(无登录表单),自动跳过 POST
     * 成功返回 token,失败抛 [LoginException]
     */
    @Throws(IOException::class)
    fun login(username: String, password: String): String {
        // STEP0 预置信任设备 cookie(固件 COOKIE_INPUT 等价)
        preloadWebCookies()

        val loginUrl = "$SSO_BASE/cas/login?service=$SERVICE"

        // STEP1 获取登录页(信任 cookie 有效时,CAS 直接 302 到 menjin,OkHttp 自动跟随)
        val page = get(loginUrl)
        val lt = extract(page, "name=\"lt\" value=\"", "\"")
        val execution = extract(page, "name=\"execution\" value=\"", "\"")
        Log.i(TAG, "STEP1 GET 登录页: len=${page.length} lt=${if (lt.isEmpty()) "无" else "有"} exec=${if (execution.isEmpty()) "无" else "有"}")

        if (lt.isNotEmpty() && execution.isNotEmpty()) {
            // STEP2 加密
            val rsa = DesCipher.strEnc(username + password + lt)
            Log.i(TAG, "STEP2 rsa 已生成(len=${rsa.length})")

            // STEP3 POST 登录
            val body = FormBody.Builder()
                .add("rsa", rsa)
                .add("ul", username.length.toString())
                .add("pl", password.length.toString())
                .add("sl", "0")
                .add("lt", lt)
                .add("execution", execution)
                .add("_eventId", "submit")
                .build()

            val resp = client.newCall(
                Request.Builder()
                    .url(loginUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", SSO_BASE)
                    .header("Referer", "$SSO_BASE/cas/login")
                    .post(body)
                    .build()
            ).execute()
            resp.use {
                // 登录成功会 302 到 menjin;失败则停留在 sso(200 错误页)
                Log.i(
                    TAG,
                    "STEP3 POST 登录结果: code=${it.code} 最终host=${it.request.url.host} cookies=${cookieSummary()}",
                )
                if (!it.isSuccessful || it.request.url.host != "menjin.dlut.edu.cn") {
                    throw LoginException("账号或密码错误,或需要二次认证(可到设置页用网页登录)")
                }
            }
        }
        // 无 lt/execution:信任设备 cookie 已让 CAS 直接放行,无需表单登录

        // STEP4 访问门禁首页,确保 cookie 落盘
        get(indexUrl())
        Log.i(TAG, "STEP4 门禁首页已访问, cookies=${cookieSummary()}")

        // STEP5 提取 token
        val token = currentToken()
        if (token.isNullOrEmpty()) {
            throw LoginException("登录后未获得 shfb-token(建议到设置页用网页登录完成二次认证)")
        }
        persistCookies()
        Log.i(TAG, "STEP5 拿到 token(len=${token.length})")
        return token
    }

    /** 把网页登录保存的 cookie 预置进 jar(固件 login() 开头的 cookieJar=COOKIE_INPUT) */
    private fun preloadWebCookies() {
        val sso = settings.webCookieSso
        val menjin = settings.webCookieMenjin
        if (sso.isNotEmpty()) {
            // 跳过 JSESSIONIDCAS:CAS 会话 cookie 以 CASTGC 为准,
            // 预置旧会话号会与服务端新下发的会话号冲突(同名双 cookie 服务端读旧值)
            loadCookieString(sso, "sso.dlut.edu.cn", skipNames = setOf("JSESSIONIDCAS"))
            Log.i(TAG, "STEP0 预置 sso 信任 cookie: ${namesOnly(sso)}")
        }
        if (menjin.isNotEmpty()) {
            loadCookieString(menjin, "menjin.dlut.edu.cn")
            Log.i(TAG, "STEP0 预置 menjin 信任 cookie: ${namesOnly(menjin)}")
        }
    }

    /** 只记录 cookie 名字,不落值 */
    private fun namesOnly(header: String): String =
        header.split(";").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            .map { it.substringBefore("=") }.joinToString(",")

    /** jar 里当前各 cookie 摘要(名字@域) */
    private fun cookieSummary(): String =
        cookieJar.listAll().joinToString("; ") { "${it.name}@${it.domain}" }

    private fun loadCookieString(header: String, host: String, skipNames: Set<String> = emptySet()) {
        for (pair in header.split(";")) {
            val idx = pair.indexOf("=")
            if (idx <= 0) continue
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name.isEmpty() || name in skipNames) continue
            cookieJar.addRaw(host, name, value)
        }
    }

    // ==================== 开门 ====================

    /**
     * 开门请求(与 ESP 固件 executeOpenDoor() 相同):
     * sign = md5小写(ts + SIGN_SECRET + rand) + rand,rand 为 4 位 62 字符集随机串
     */
    @Throws(IOException::class)
    fun openDoor(token: String, deviceCode: String, personId: String): OpenResult {
        // 时间戳:手机端本地时间(不走网络时间同步)
        val ts = System.currentTimeMillis().toString()
        // rand:与 ESP randomString(4) 完全一致(62 字符集均匀随机,必须符合该生成逻辑)
        val rand = randomString(4)
        val sign = md5(ts + SIGN_SECRET + rand) + rand

        val body = FormBody.Builder()
            .add("commandCode", "OPEN")
            .add("conditions", """{"personId":"$personId","delStatus":"0"}""")
            .add("deviceCode", deviceCode)
            .add("isCommon", "yes")
            .add("pageSize", "-1")
            .add("projectCd", PROJECT_CD)
            .add("token", token)
            .build()

        val resp = client.newCall(
            Request.Builder()
                .url(openUrl())
                .header("User-Agent", USER_AGENT)
                .header("AUTH-SIGN", sign)
                .header("AUTH-TIMESTAMP", ts)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(body)
                .build()
        ).execute()
        resp.use {
            val text = it.body?.string() ?: ""
            Log.i(TAG, "开门请求: code=${it.code} body=${text.take(300)}")
            return OpenResult(text.contains("\"success\":true"), it.code, text)
        }
    }

    // ==================== 设备列表(自动获取门锁编号) ====================

    /** 拉取门锁编号列表:与网页端 mine.html 的调用完全一致(POST + 签名头);解析失败抛 IOException */
    @Throws(IOException::class)
    fun fetchDeviceCodes(token: String, personId: String): List<String> {
        // conditions 与浏览器抓包一致(personId 用账号,与开门接口相同)
        val conditions =
            """{"deviceType":"","deviceCode":"","deviceStatus":"10","sendStatus":"10","mediumType":"","personId":"$personId","orderColumn":"send_status_time","isAsc":false,"attrResults":"doorModel,isOpen,inRair,onLine,doorLock","delStatus":"0","keyword":"","regionCds":""}"""

        // menjin 的 /cser/** 接口都校验 AUTH-SIGN/AUTH-TIMESTAMP 签名头(与开门接口一致)
        val ts = System.currentTimeMillis().toString()
        val rand = randomString(4)
        val sign = md5(ts + SIGN_SECRET + rand) + rand

        val form = FormBody.Builder()
            .add("conditions", conditions)
            .add("currentPage", "1")
            .add("isTop", "true")
            .add("pageSize", "-1")
            .add("projectCd", PROJECT_CD)
            .add("token", token)
            .build()

        var text: String? = null
        var code = -1
        var finalUrl = ""
        var lastError: IOException? = null
        try {
            client.newCall(
                Request.Builder()
                    .url(listUrl())
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("AUTH-SIGN", sign)
                    .header("AUTH-TIMESTAMP", ts)
                    .header("Origin", MENJIN_BASE)
                    .header("Referer", "$MENJIN_BASE/cser/static/menjin/mine.html")
                    .post(form)
                    .build()
            ).execute().use { resp ->
                code = resp.code
                finalUrl = resp.request.url.toString()
                text = resp.body?.string()
                // 会话过期时服务器会 302 到 CAS(最终 URL 不再是 menjin),这里记录下来便于排查
                Log.i(TAG, "设备列表响应: code=$code 最终URL=$finalUrl body=${text?.take(200)}")
            }
        } catch (e: IOException) {
            lastError = e
        }

        val bodyText = text
        if (bodyText.isNullOrEmpty()) {
            throw IOException("设备列表接口无响应(code=$code):${lastError?.message ?: ""}(URL: ${listUrl()})")
        }
        val codes = parseDeviceCodes(bodyText)
        if (codes.isEmpty()) {
            throw IOException("接口返回中未解析出设备编号(code=$code, 最终URL=$finalUrl),请改用手动输入")
        }
        return codes
    }

    // ==================== 工具 ====================

    /** MD5 小写 hex(与 ESP MD5Builder.toString() 一致) */
    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    /** 与 ESP randomString(4) 相同:4 位,从 62 字符集均匀随机取(生成逻辑必须一致) */
    fun randomString(length: Int): String =
        (1..length).map { RAND_CHARS[secureRandom.nextInt(RAND_CHARS.length)] }
            .joinToString("")

    private fun get(url: String): String {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} @ $url")
                return resp.body?.string() ?: ""
            }
    }

    private fun extract(html: String, left: String, right: String): String {
        val s = html.indexOf(left)
        if (s == -1) return ""
        val start = s + left.length
        val e = html.indexOf(right, start)
        return if (e == -1) "" else html.substring(start, e)
    }
}
