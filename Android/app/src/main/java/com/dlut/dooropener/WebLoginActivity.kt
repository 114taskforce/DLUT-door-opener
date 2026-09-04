package com.dlut.dooropener

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dlut.dooropener.data.SettingsStore
import com.dlut.dooropener.ui.DoorAppTheme

/**
 * 网页登录页:在 WebView 中完成 CAS 登录(含二次认证),
 * 登录成功跳转到 menjin 后自动抓取两站 cookie 并保存(固件 COOKIE_INPUT 等价)。
 */
class WebLoginActivity : ComponentActivity() {

    private companion object { const val TAG = "DoorClient" }

    private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingHarvest: Runnable? = null
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DoorAppTheme {
                WebLoginScreen(
                    onBack = { finish() },
                    // 注意:登录页 URL 本身带 service=http://menjin... 参数,
                    // 必须按 host 判断是否已真正跳转到门禁站,不能用 contains
                    onPageFinished = { url ->
                        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
                        if (host == "menjin.dlut.edu.cn") {
                            // 已到门禁站说明登录链完成、cookie 已下发;
                            // 优先等 mine.html 门禁面板渲染完成再抓取(用户能看到页面,避免白屏),
                            // 若停留在 index.html 等中间页,2.5 秒后也照常抓取
                            val delay =
                                if (url.contains("/cser/static/menjin/mine.html")) 600L else 2500L
                            scheduleHarvest(delay)
                        }
                    },
                    onWebViewCreated = { webView = it },
                )
            }
        }
    }

    /** 延迟抓取:先让页面渲染出来再关闭,避免「白屏+直接弹成功」;多次触发以最后一次为准 */
    private fun scheduleHarvest(delayMs: Long) {
        if (finished) return
        pendingHarvest?.let { handler.removeCallbacks(it) }
        val r = Runnable { harvestAndFinish() }
        pendingHarvest = r
        handler.postDelayed(r, delayMs)
    }

    /** 抓取 sso / menjin 两站 cookie 并保存(等价于固件 COOKIE_INPUT) */
    private fun harvestAndFinish() {
        if (finished) return
        finished = true
        pendingHarvest?.let { handler.removeCallbacks(it) }
        val cm = CookieManager.getInstance()
        cm.flush()
        // 注意:getCookie 只返回路径匹配的 cookie;CASTGC 的 Path=/cas,
        // 必须用 /cas/login 路径的 URL 才能拿到,根路径 URL 拿不到
        val sso = mergeCookies(
            cm.getCookie("https://sso.dlut.edu.cn/cas/login"),
            cm.getCookie("https://sso.dlut.edu.cn/"),
        )
        val menjin = mergeCookies(
            cm.getCookie("http://menjin.dlut.edu.cn/cser/static/menjin/index.html"),
            cm.getCookie("http://menjin.dlut.edu.cn/"),
        )
        val store = SettingsStore(this)
        store.webCookieSso = sso
        store.webCookieMenjin = menjin
        Log.i(TAG, "网页登录抓取: sso含CASTGC=${sso.contains("CASTGC")} menjin含token=${menjin.contains("shfb-token")}")
        if (sso.isEmpty() && menjin.isEmpty()) {
            Toast.makeText(this, "未获取到 Cookie,请确认已登录成功", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                if (sso.contains("CASTGC")) "登录成功,信任 Cookie 已记录"
                else "Cookie 已记录(未含 CASTGC,请确认登录完成)",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }

    /** 用户手动返回时兜底:若已登录拿到 CASTGC/shfb-token 但没触发门禁页抓取,也保存 */
    override fun onDestroy() {
        super.onDestroy()
        // 退出网页登录页时清空 WebView 页面缓存(应用体积大头;Cookie 不受影响)
        webView?.clearCache(true)
        clearWebViewMetricsFiles()
        pendingHarvest?.let { handler.removeCallbacks(it) }
        if (finished) return
        val cm = CookieManager.getInstance()
        cm.flush()
        val sso = mergeCookies(
            cm.getCookie("https://sso.dlut.edu.cn/cas/login"),
            cm.getCookie("https://sso.dlut.edu.cn/"),
        )
        val menjin = mergeCookies(
            cm.getCookie("http://menjin.dlut.edu.cn/cser/static/menjin/index.html"),
            cm.getCookie("http://menjin.dlut.edu.cn/"),
        )
        if (sso.contains("CASTGC") || menjin.contains("shfb-token")) {
            val store = SettingsStore(this)
            store.webCookieSso = sso
            store.webCookieMenjin = menjin
            Log.i(TAG, "退出兜底: Cookie 已保存 sso含CASTGC=${sso.contains("CASTGC")} menjin含token=${menjin.contains("shfb-token")}")
        } else {
            Log.w(TAG, "退出兜底: 未发现 CASTGC/shfb-token,登录可能未完成,不保存")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 「保留后台」关闭时,网页登录页按 Home 键同样彻底退出
        if (!SettingsStore(this).keepBackground) {
            webView?.clearCache(true)
            clearWebViewMetricsFiles()
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
        }
    }

    /**
     * 删除系统 WebView 组件写进应用目录的统计文件(BrowserMetrics-*.pma)。
     * 该文件由 WebView 提供方进程维护,无公开 API 可阻止其生成,
     * 只能在 WebView 用完后删掉,不让它常驻占用空间。
     */
    private fun clearWebViewMetricsFiles() {
        val targets = listOfNotNull(
            noBackupFilesDir,
            filesDir,
            filesDir?.parentFile?.let { java.io.File(it, "app_webview") },
        )
        for (dir in targets) {
            try {
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("BrowserMetrics")) f.delete()
                }
            } catch (e: Exception) {
                // 文件被 WebView 进程占用时忽略,下次退出再清
            }
        }
    }

    /** 合并多条 cookie 串(去重,保序) */
    private fun mergeCookies(vararg parts: String?): String {
        val map = LinkedHashMap<String, String>()
        for (p in parts) {
            if (p.isNullOrEmpty()) continue
            for (pair in p.split(";")) {
                val idx = pair.indexOf("=")
                if (idx <= 0) continue
                map[pair.substring(0, idx).trim()] = pair.substring(idx + 1).trim()
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebLoginScreen(
    onBack: () -> Unit,
    onPageFinished: (String) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("网页登录", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "请在网页中完成登录(含二次认证)。登录时请勾选「信任此设备」,之后 App 自动登录不再需要二次认证;登录成功跳转到门禁页后自动记录 Cookie",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    createWebView(context, onPageFinished).also(onWebViewCreated)
                },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    onPageFinished: (String) -> Unit,
): WebView {
    val startUrl =
        "https://sso.dlut.edu.cn/cas/login?service=http://menjin.dlut.edu.cn/cser/static/menjin/index.html"
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let(onPageFinished)
            }
        }
        loadUrl(startUrl)
    }
}
