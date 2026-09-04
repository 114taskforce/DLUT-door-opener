package com.dlut.dooropener.data

import android.content.Context

/**
 * 设置与状态持久化(SharedPreferences)。
 * 注:密码为明文存储,个人工具应用够用;如需更高安全可换 EncryptedSharedPreferences。
 */
class SettingsStore(context: Context) {

    private val sp = context.getSharedPreferences("door_settings", Context.MODE_PRIVATE)

    var account: String
        get() = sp.getString(KEY_ACCOUNT, "") ?: ""
        set(v) = sp.edit().putString(KEY_ACCOUNT, v).apply()

    var password: String
        get() = sp.getString(KEY_PASSWORD, "") ?: ""
        set(v) = sp.edit().putString(KEY_PASSWORD, v).apply()

    var deviceCode: String
        get() = sp.getString(KEY_DEVICE_CODE, "") ?: ""
        set(v) = sp.edit().putString(KEY_DEVICE_CODE, v).apply()

    var autoOpen: Boolean
        get() = sp.getBoolean(KEY_AUTO_OPEN, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_OPEN, v).apply()

    /** 保留后台:开=按返回键退回后台;关=退出时彻底结束进程,不在后台运行 */
    var keepBackground: Boolean
        get() = sp.getBoolean(KEY_KEEP_BACKGROUND, true)
        set(v) = sp.edit().putBoolean(KEY_KEEP_BACKGROUND, v).apply()

    /** 上次自动开门时间戳(毫秒),用于 30 秒去重 */
    var lastAutoOpenAt: Long
        get() = sp.getLong(KEY_LAST_AUTO_OPEN, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_AUTO_OPEN, v).apply()

    /** 最近一次登录尝试时间戳(毫秒),token 自动刷新的去重与冷却依据 */
    var lastLoginAt: Long
        get() = sp.getLong(KEY_LAST_LOGIN, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_LOGIN, v).apply()

    /** 持久化 cookie jar(JSON 字符串,含 shfb-token) */
    fun saveCookieJar(json: String) = sp.edit().putString(KEY_COOKIES, json).apply()

    fun loadCookieJar(): String? = sp.getString(KEY_COOKIES, null)

    fun clearCookieJar() = sp.edit().remove(KEY_COOKIES).apply()

    /** 网页登录获得的信任设备 cookie(等价于固件 COOKIE_INPUT,用于二次认证) */
    var webCookieSso: String
        get() = sp.getString(KEY_WEB_COOKIE_SSO, "") ?: ""
        set(v) = sp.edit().putString(KEY_WEB_COOKIE_SSO, v).apply()

    var webCookieMenjin: String
        get() = sp.getString(KEY_WEB_COOKIE_MENJIN, "") ?: ""
        set(v) = sp.edit().putString(KEY_WEB_COOKIE_MENJIN, v).apply()

    fun hasWebCookie(): Boolean = webCookieSso.isNotEmpty() || webCookieMenjin.isNotEmpty()

    fun clearWebCookies() {
        sp.edit().remove(KEY_WEB_COOKIE_SSO).remove(KEY_WEB_COOKIE_MENJIN).apply()
    }

    /** 凭据齐全 = 有账号密码即可(门锁编号会在开门流程自动获取补全) */
    fun hasCredentials(): Boolean = account.isNotBlank() && password.isNotBlank()

    private companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_PASSWORD = "password"
        const val KEY_DEVICE_CODE = "device_code"
        const val KEY_AUTO_OPEN = "auto_open"
        const val KEY_KEEP_BACKGROUND = "keep_background"
        const val KEY_LAST_AUTO_OPEN = "last_auto_open_at"
        const val KEY_LAST_LOGIN = "last_login_at"
        const val KEY_COOKIES = "cookies"
        const val KEY_WEB_COOKIE_SSO = "web_cookie_sso"
        const val KEY_WEB_COOKIE_MENJIN = "web_cookie_menjin"
    }
}
