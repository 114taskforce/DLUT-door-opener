package com.dlut.dooropener

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlut.dooropener.data.SettingsStore
import com.dlut.dooropener.net.DoorClient
import com.dlut.dooropener.net.LoginException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class StatusKind { IDLE, BUSY, SUCCESS, FAIL }

data class UiState(
    // 设置页字段
    val account: String = "",
    val password: String = "",
    val deviceCode: String = "",
    val autoOpen: Boolean = false,
    val keepBackground: Boolean = true,
    // 状态
    val status: String = "就绪",
    val statusKind: StatusKind = StatusKind.IDLE,
    val showSettings: Boolean = false,
    val fetchingDevices: Boolean = false,
    val deviceCandidates: List<String>? = null,
    // 网页登录获得的信任设备 cookie 是否已记录
    val webCookieRecorded: Boolean = false,
    // 信任 cookie 编辑器
    val showCookieEditor: Boolean = false,
    val cookieDraftSso: String = "",
    val cookieDraftMenjin: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val client = DoorClient(settings)

    private val _uiState = MutableStateFlow(
        UiState(
            account = settings.account,
            password = settings.password,
            deviceCode = settings.deviceCode,
            autoOpen = settings.autoOpen,
            keepBackground = settings.keepBackground,
            webCookieRecorded = settings.hasWebCookie(),
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ==================== 设置 ====================

    fun onAccountChange(v: String) { settings.account = v; _uiState.update { it.copy(account = v) } }
    fun onPasswordChange(v: String) { settings.password = v; _uiState.update { it.copy(password = v) } }
    fun onDeviceCodeChange(v: String) { settings.deviceCode = v; _uiState.update { it.copy(deviceCode = v) } }
    fun onAutoOpenChange(v: Boolean) { settings.autoOpen = v; _uiState.update { it.copy(autoOpen = v) } }

    fun onKeepBackgroundChange(v: Boolean) { settings.keepBackground = v; _uiState.update { it.copy(keepBackground = v) } }

    /** 供 Activity 在返回键/退出时读取(不进 UiState 的时机也可用) */
    fun keepBackgroundEnabled(): Boolean = settings.keepBackground

    fun toggleSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show, deviceCandidates = null) }
    }

    /** 从网页登录页返回后刷新信任 cookie 状态 */
    fun refreshWebCookieStatus() {
        _uiState.update { it.copy(webCookieRecorded = settings.hasWebCookie()) }
    }

    /**
     * 网页登录页返回后调用:清标记并立即强制静默补登录。
     * 信任 cookie(CASTGC)生效时 CAS 全程透明 302、无需二次认证,直接换到新 token;
     * 网页登录拿到的新 menjin cookie 也会在此被预置进请求 jar。
     */
    fun onWebLoginDone() {
        if (!settings.hasCredentials()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loginFresh(settings.account, settings.password, force = true)
                }
                _uiState.update {
                    it.copy(status = "自动登录成功,Token 已刷新", statusKind = StatusKind.SUCCESS)
                }
            } catch (e: LoginException) {
                // 认证不完整(如未勾选信任设备)时提示,由用户自行决定是否重试网页登录
                _uiState.update {
                    it.copy(status = "登录后仍失败:${e.message}", statusKind = StatusKind.FAIL)
                }
                Log.w("DoorVM", "网页登录后补登录失败:${e.message}")
            } catch (e: Exception) {
                Log.w("DoorVM", "网页登录后补登录异常:${e.message}")
            }
        }
    }

    /** 清除信任 Cookie + 活动会话 + WebView 登录态(完整登出:下次操作真正从零验证) */
    fun clearWebCookies() {
        settings.clearWebCookies()
        client.clearAllCookies()
        settings.lastLoginAt = 0L
        // 连 WebView 的 SSO 登录态一起清:否则「清除」后仍能借浏览器信任会话
        // 免密免短信透传回 Cookie,密码错误也拦不住
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (e: Exception) {
            Log.w("DoorVM", "清 WebView Cookie 失败:${e.message}")
        }
        _uiState.update {
            it.copy(webCookieRecorded = false, status = "已完整登出,下次操作将重新登录")
        }
    }

    // ==================== 信任 Cookie 编辑 ====================

    /** 打开编辑器,载入当前已记录的 cookie 到草稿 */
    fun openCookieEditor() {
        _uiState.update {
            it.copy(
                showCookieEditor = true,
                cookieDraftSso = settings.webCookieSso,
                cookieDraftMenjin = settings.webCookieMenjin,
            )
        }
    }

    fun onCookieDraftSsoChange(v: String) = _uiState.update { it.copy(cookieDraftSso = v) }

    fun onCookieDraftMenjinChange(v: String) = _uiState.update { it.copy(cookieDraftMenjin = v) }

    /** 保存草稿(与固件手动填 COOKIE_INPUT 等价) */
    fun saveCookieEditor() {
        settings.webCookieSso = _uiState.value.cookieDraftSso.trim()
        settings.webCookieMenjin = _uiState.value.cookieDraftMenjin.trim()
        _uiState.update {
            it.copy(
                showCookieEditor = false,
                webCookieRecorded = settings.hasWebCookie(),
                status = "信任 Cookie 已保存",
            )
        }
    }

    fun dismissCookieEditor() = _uiState.update { it.copy(showCookieEditor = false) }

    // ==================== token 自动维护 ====================

    /** 登录互斥:静默刷新与开门/取设备流程并发登录时只执行一次 */
    private val loginMutex = Mutex()

    /** token 视为新鲜的有效期:距获取 15 分钟内则不重复登录 */
    private val TOKEN_FRESH_MS = 15 * 60_000L

    /** 无 token 时静默登录的冷却期:失败后不反复锤登录接口 */
    private val NO_TOKEN_RETRY_MS = 60_000L

    /**
     * 静默确保 token 新鲜(启动与回到前台时调用):
     * 无凭据、最近登录过(成功或失败)则跳过;否则后台登录,不打扰用户。
     */
    fun ensureTokenFresh() {
        if (!settings.hasCredentials()) return
        val age = System.currentTimeMillis() - settings.lastLoginAt
        val hasToken = !client.currentToken().isNullOrEmpty()
        if (hasToken && age < TOKEN_FRESH_MS) return        // 最近登录过,视为仍有效
        if (!hasToken && age < NO_TOKEN_RETRY_MS) return    // 失败冷却期内不重试
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loginFresh(settings.account, settings.password)
                }
            } catch (e: Exception) {
                // 静默失败:不弹错误,等回到前台或开门时再试
                Log.w("DoorVM", "静默登录失败:${e.message}")
            }
        }
    }

    /**
     * 单飞登录:并发调用只执行一次真实登录;非 force 且 token 还新鲜时直接复用。
     * 无论成败都记录尝试时间,供 ensureTokenFresh 去重/冷却。
     */
    private suspend fun loginFresh(
        account: String,
        password: String,
        force: Boolean = false,
        previousToken: String? = null,
    ): String = loginMutex.withLock {
        val cur = client.currentToken()
        if (!force) {
            val age = System.currentTimeMillis() - settings.lastLoginAt
            if (!cur.isNullOrEmpty() && age < TOKEN_FRESH_MS) return cur
        } else if (previousToken != null && !cur.isNullOrEmpty() && cur != previousToken) {
            // 等锁期间静默刷新已换过 token,直接复用,避免二次登录
            return cur
        }
        try {
            client.login(account, password)
        } finally {
            settings.lastLoginAt = System.currentTimeMillis()
        }
    }

    // ==================== 开门 ====================

    /** 打开 APP 时若开关打开,自动触发一次开门(30 秒内去重,防旋转/快速重开重复触发) */
    fun autoOpenIfNeeded() {
        if (!settings.autoOpen) return
        if (!settings.hasCredentials()) return
        if (System.currentTimeMillis() - settings.lastAutoOpenAt < 30_000) return
        settings.lastAutoOpenAt = System.currentTimeMillis()
        openDoor()
    }

    /** 完整开门流程:失败 → 重新登录 → 重试一次(对应 ESP openDoorProcess) */
    fun openDoor() {
        if (_uiState.value.statusKind == StatusKind.BUSY) return
        val st = _uiState.value
        if (!settings.hasCredentials()) {
            _uiState.update {
                it.copy(status = "请先在设置中填写账号和密码", statusKind = StatusKind.FAIL)
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(status = "正在开门…", statusKind = StatusKind.BUSY) }
            val r = withContext(Dispatchers.IO) {
                openDoorProcessInternal(st.account, st.password, st.deviceCode)
            }
            _uiState.update {
                it.copy(
                    status = r.first,
                    statusKind = if (r.second) StatusKind.SUCCESS else StatusKind.FAIL,
                )
            }
        }
    }

    /**
     * 完整开门流程 —— 匹配固件 GPIO0 按下逻辑(openDoorProcess):
     * 先用现有 currentToken 直接尝试开门 → 失败才 updateToken(强制重登)再试一次。
     * token 的定期更新不掺进按钮路径(对应固件 loop 里每小时的 checkAndUpdateToken,
     * App 侧由启动/回前台的 ensureTokenFresh 以 15 分钟窗口承担)。
     */
    private suspend fun openDoorProcessInternal(
        account: String,
        password: String,
        deviceCode: String,
    ): Pair<String, Boolean> {
        try {
            // 固件按按钮用的就是现成的 currentToken;为空则等同 setup 阶段的 updateToken
            var token = client.currentToken()
            if (token.isNullOrEmpty()) token = loginFresh(account, password)

            // 门锁编号未填时自动拉取设备列表补全(固件是写死编号,App 跟随账号)
            var code = deviceCode
            if (code.isBlank()) {
                val codes = client.fetchDeviceCodes(token, account)
                code = codes.firstOrNull().orEmpty()
                if (code.isBlank()) return "开门失败:未能自动获取门锁编号" to false
                settings.deviceCode = code
                _uiState.update { it.copy(deviceCode = code) }
                Log.i("DoorVM", "门锁编号已自动补全:$code")
            }

            var r = client.openDoor(token, code, account)
            if (r.success) return "开门成功" to true

            // 第一次失败:updateToken(强制重登)后重试一次
            token = loginFresh(account, password, force = true, previousToken = token)
            r = client.openDoor(token, code, account)
            if (r.success) return "开门成功(重试后)" to true
            return "开门失败:${shortMessage(r.body)}" to false
        } catch (e: LoginException) {
            Log.e("DoorVM", "开门流程登录失败", e)
            return "登录失败:${e.message}" to false
        } catch (e: Exception) {
            Log.e("DoorVM", "开门流程异常", e)
            return "网络错误:${e.message}" to false
        }
    }

    // ==================== 自动获取门锁编号 ====================

    fun fetchDevices() {
        if (_uiState.value.fetchingDevices) return
        val st = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(fetchingDevices = true, deviceCandidates = null) }
            val r = withContext(Dispatchers.IO) {
                val result = try {
                    // 点击「自动获取」每次都强制重新登录换新 token(带时间戳),再取编号
                    val token = loginFresh(
                        st.account, st.password,
                        force = true, previousToken = client.currentToken(),
                    )
                    client.fetchDeviceCodes(token, st.account) to null
                } catch (e: LoginException) {
                    // 登录本身失败(密码错/服务端限制):立即再登一次只会加重风控,直接报错
                    emptyList<String>() to e.message
                } catch (e: Exception) {
                    // 设备列表接口校验会话(JSESSIONID),缓存的 token 可能对应已过期会话:
                    // 重新登录拿到新会话后重试一次(与开门流程一致)
                    try {
                        val t2 = loginFresh(st.account, st.password, force = true)
                        client.fetchDeviceCodes(t2, st.account) to null
                    } catch (e2: Exception) {
                        emptyList<String>() to e2.message
                    }
                }
                result
            }
            _uiState.update {
                val base = it.copy(fetchingDevices = false)
                if (r.second != null) {
                    base.copy(status = "获取失败:${r.second}")
                } else if (r.first.isEmpty()) {
                    base.copy(status = "未获取到设备编号,请手动输入")
                } else {
                    base.copy(deviceCandidates = r.first)
                }
            }
        }
    }

    fun selectDevice(code: String) {
        settings.deviceCode = code
        _uiState.update {
            it.copy(deviceCode = code, deviceCandidates = null, status = "门锁编号已更新")
        }
    }

    fun dismissCandidates() {
        _uiState.update { it.copy(deviceCandidates = null) }
    }

    // ==================== 工具 ====================

    /** 从失败响应 JSON 里取 message 字段,便于显示失败原因 */
    private fun shortMessage(body: String): String {
        return try {
            val o = JSONObject(body)
            val m = o.optString("message").ifBlank { o.optString("msg") }
            if (m.isNotBlank()) m else body.take(200)
        } catch (e: Exception) {
            body.take(200)
        }
    }
}
