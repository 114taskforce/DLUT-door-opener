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

    fun clearWebCookies() {
        settings.clearWebCookies()
        _uiState.update { it.copy(webCookieRecorded = false, status = "已清除信任 Cookie") }
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
                it.copy(status = "请先在设置中填写账号、密码和门锁编号", statusKind = StatusKind.FAIL)
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(status = "正在开门…", statusKind = StatusKind.BUSY) }
            val r = withContext(Dispatchers.IO) {
                openDoorProcessInternal(st.account, st.password, st.deviceCode)
            }
            _uiState.update {
                it.copy(status = r.first, statusKind = if (r.second) StatusKind.SUCCESS else StatusKind.FAIL)
            }
        }
    }

    private fun openDoorProcessInternal(
        account: String,
        password: String,
        deviceCode: String,
    ): Pair<String, Boolean> {
        try {
            // 复用已有 token,没有则先登录
            var token = client.currentToken()
            if (token.isNullOrEmpty()) {
                token = client.login(account, password)
            }

            var r = client.openDoor(token, deviceCode, account)
            if (r.success) return "开门成功" to true

            // 第一次失败:重新登录后重试一次
            token = client.login(account, password)
            r = client.openDoor(token, deviceCode, account)
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
                try {
                    var token = client.currentToken()
                    if (token.isNullOrEmpty()) token = client.login(st.account, st.password)
                    client.fetchDeviceCodes(token, st.account) to null
                } catch (e: Exception) {
                    // 设备列表接口校验会话(JSESSIONID),缓存的 token 可能对应已过期会话:
                    // 重新登录拿到新会话后重试一次(与开门流程一致)
                    try {
                        val t2 = client.login(st.account, st.password)
                        client.fetchDeviceCodes(t2, st.account) to null
                    } catch (e2: Exception) {
                        emptyList<String>() to e2.message
                    }
                }
            }
            _uiState.update {
                if (r.second != null) {
                    it.copy(fetchingDevices = false, status = "获取失败:${r.second}")
                } else if (r.first.isEmpty()) {
                    it.copy(fetchingDevices = false, status = "未获取到设备编号,请手动输入")
                } else {
                    it.copy(fetchingDevices = false, deviceCandidates = r.first)
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
