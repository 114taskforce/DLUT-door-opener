/**
 * ESP32-S3 校园网登录 + 门禁开门
 *
 * 启动流程:
 *   1. 连接 DLUT-LingShui WiFi (开放网络)
 *   2. Portal 认证 (RSA 加密密码, POST 登录, 获取互联网访问)
 *   3. 苏宁时间同步
 *   4. CAS SSO 登录 → 获取 shfb-token
 *   5. 待命: 按 BOOT 键开门
 *
 * 每 30 秒检测网络连通性
 * 每小时自动刷新 CAS token
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <MD5Builder.h>
#include <mbedtls/bignum.h>
#include "des.h"                         // strEnc() — CAS RSA 加密
#include "Freenove_WS2812_Lib_for_ESP32.h"

// ==================== 跳过二次认证cookie（必须填） ====================
#define COOKIE_INPUT ""
// ==================== 校园网认证账号（必须填） ====================
const char* PORTAL_USERNAME = "2024";
const char* PORTAL_PASSWORD = "dut";
// ==================== CAS / 门禁账号 &门锁编号（必须填） ====================
const char* CAS_USERNAME = "";
const char* CAS_PASSWORD = "";
const char* DeviceCode = "DL-LY-114514";

// ==================== WiFi ====================
const char* WIFI_SSID     = "DLUT-LingShui";
const char* WIFI_PASSWORD = "";

// ==================== RSA 公钥 ====================
const char* RSA_E_HEX = "010001";
const char* RSA_M_HEX = "94dd2a8675fb779e6b9f7103698634cd400f27a154afa67af6166a43fc2641722"
                        "2a79506d34cacc7641946abda1785b7acf9910ad6a0978c91ec84d40b71d28913"
                        "79af19ffb333e7517e390bd26ac312fe940c340466b4a5d4af1d65c3b5944078f9"
                        "6a1a51a5a53e4bc302818b7c9f63c4a1b07bd7d874cef1c3d4b2f5eb7871";

// ==================== 时间 / Token ====================
const char* TIME_API_URL    = "http://f.m.suning.com/api/ct.do";
const char* PORTAL_INDEX    = "http://123.123.123.123/";
#define TOKEN_UPDATE_INTERVAL  3600000UL   // 1 小时

// ==================== 硬件引脚 ====================
#define BOOT_BUTTON_PIN   0     // BOOT 键
#define DOOR_SENSOR_PIN   4     // 门磁传感器
#define LED_PIN           2     // 板载 LED
#define WS2812_PIN       48     // WS2812 灯带
#define WS2812_COUNT      3     // 3 颗灯珠
#define DEBOUNCE_DELAY    200
#define SENSOR_DELAY_TIME 2400

Freenove_ESP32_WS2812 leds = Freenove_ESP32_WS2812(WS2812_COUNT, WS2812_PIN, 0, TYPE_GRB);

// ==================== LED 状态机 ====================
enum LedState {
    LED_OFF,
    LED_WIFI_CONN,       // 🔵 蓝闪 500ms
    LED_PORTAL_AUTH,     // 🟡 黄闪 200ms
    LED_CAS_LOGIN,       // 🟣 紫闪 200ms
    LED_READY,           // 🟢 绿常亮
    LED_DOOR_OPENING,    // 🔵 蓝快闪 150ms
    LED_SUCCESS,         // 🟢 绿快闪 100ms
    LED_ERROR,           // 🔴 红快闪 250ms
    LED_NET_DOWN,        // 🔴 红慢闪 1000ms
};

struct LedConfig { uint8_t r, g, b; int interval; };  // interval=0 表示常亮

LedConfig ledCfg(LedState s) {
    switch (s) {
        case LED_WIFI_CONN:    return {0,   0,   255, 500};
        case LED_PORTAL_AUTH:  return {255, 255, 0,   200};
        case LED_CAS_LOGIN:    return {128, 0,   255, 200};
        case LED_READY:        return {0,   255, 0,   0};    // 0 = 常亮
        case LED_DOOR_OPENING: return {0,   0,   255, 150};
        case LED_SUCCESS:      return {0,   255, 0,   100};
        case LED_ERROR:        return {255, 0,   0,   250};
        case LED_NET_DOWN:     return {255, 0,   0,   1000};
        default:               return {0,   0,   0,   0};
    }
}

LedState  ledState   = LED_OFF;
unsigned long ledTimer = 0;
bool          ledOn    = false;

void applyLed(uint8_t r, uint8_t g, uint8_t b) {
    leds.setAllLedsColor(r, g, b);
}

void setLed(LedState s) {
    if (ledState == s) return;
    ledState = s;
    ledTimer = millis();
    LedConfig c = ledCfg(s);
    if (c.interval == 0) {
        // 常亮态
        ledOn = true;
        applyLed(c.r, c.g, c.b);
    } else {
        // 闪烁态 — 先亮
        ledOn = true;
        applyLed(c.r, c.g, c.b);
    }
    Serial.printf("[LED] %d -> %d  (%s)\n", (int)ledState, (int)s,
                  c.interval ? "blink" : "solid");
}

void updateLed() {
    LedConfig c = ledCfg(ledState);
    if (c.interval == 0) return;  // 常亮

    if (millis() - ledTimer >= (unsigned long)c.interval) {
        ledTimer = millis();
        ledOn = !ledOn;
        applyLed(ledOn ? c.r : 0, ledOn ? c.g : 0, ledOn ? c.b : 0);
    }
}
/** 带 LED 刷新的 delay */
void delayLed(int ms) {
    unsigned long start = millis();
    while (millis() - start < (unsigned long)ms) {
        updateLed();
        delay(10);
    }
}



// ==================== 全局状态 ====================
unsigned long long apiCurrentTime   = 0;
unsigned long      apiMillisAtUpdate = 0;
bool               timeSynced        = false;
String             currentToken;
unsigned long      lastTokenUpdate   = 0;
String             cookieJar;

// ==================== HTTP 工具 ====================

/** 读取 HTTP 响应体 */
String readResponse(WiFiClient &client) {
    String res;
    while (client.connected() || client.available()) {
        if (client.available()) res += (char)client.read();
    }
    return res;
}

String readResponse(WiFiClientSecure &client) {
    String res;
    while (client.connected() || client.available()) {
        if (client.available()) res += (char)client.read();
    }
    return res;
}

/** 提取 HTTP header */
String getHeader(const String &res, const String &name) {
    int p = res.indexOf(name + ":");
    if (p == -1) return "";
    p += name.length() + 1;
    int e = res.indexOf("\r\n", p);
    return res.substring(p, e);
}

/** 提取 HTML 字段 */
String extract(const String &html, const String &l, const String &r) {
    int s = html.indexOf(l);
    if (s == -1) return "";
    s += l.length();
    int e = html.indexOf(r, s);
    return html.substring(s, e);
}

/** 提取 HTTP body（跳过 header）*/
String extractBody(const String &res) {
    int idx = res.indexOf("\r\n\r\n");
    return (idx == -1) ? res : res.substring(idx + 4);
}

/** 保存 Cookie */
void saveCookie(const String &res) {
    int p = 0;
    while (true) {
        p = res.indexOf("Set-Cookie:", p);
        if (p == -1) break;
        p += 11;
        while (p < (int)res.length() && res.charAt(p) == ' ') p++;
        int e = res.indexOf(";", p);
        if (e == -1) e = res.length();
        String kv = res.substring(p, e);
        int eq = kv.indexOf("=");
        if (eq == -1) continue;
        String k = kv.substring(0, eq); k.trim();
        // 替换同名 cookie
        int old = cookieJar.indexOf(k + "=");
        if (old != -1) {
            int oldEnd = cookieJar.indexOf(";", old);
            if (oldEnd == -1) oldEnd = cookieJar.length() - 1;
            cookieJar = cookieJar.substring(0, old) + kv + cookieJar.substring(oldEnd);
        } else {
            if (cookieJar.length() > 0 && !cookieJar.endsWith(" ")) cookieJar += " ";
            cookieJar += kv + ";";
        }
    }
}

/** HTTP GET */
String httpGET(const char *host, const String &url) {
    WiFiClient client;
    if (!client.connect(host, 80)) { Serial.printf("[E] connect %s:80 fail\n", host); return ""; }
    client.print("GET " + url + " HTTP/1.1\r\nHost: " + host +
                 "\r\nUser-Agent: Mozilla/5.0\r\nCookie: " + cookieJar +
                 "\r\nConnection: close\r\n\r\n");
    return readResponse(client);
}

/** HTTPS GET */
String httpsGET(const char *host, const String &url) {
    WiFiClientSecure client;
    client.setInsecure();
    if (!client.connect(host, 443)) { Serial.printf("[E] connect %s:443 fail\n", host); return ""; }
    client.print("GET " + url + " HTTP/1.1\r\nHost: " + host +
                 "\r\nUser-Agent: Mozilla/5.0\r\nCookie: " + cookieJar +
                 "\r\nConnection: close\r\n\r\n");
    return readResponse(client);
}

/** HTTPS POST */
String httpsPOST(const char *host, const String &url, const String &body) {
    WiFiClientSecure client;
    client.setInsecure();
    if (!client.connect(host, 443)) { Serial.printf("[E] connect %s:443 fail\n", host); return ""; }
    client.print("POST " + url + " HTTP/1.1\r\nHost: " + host +
                 "\r\nUser-Agent: Mozilla/5.0\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                 "Content-Length: " + body.length() +
                 "\r\nOrigin: https://sso.dlut.edu.cn\r\nReferer: https://sso.dlut.edu.cn/cas/login\r\n" +
                 "Cookie: " + cookieJar + "\r\nConnection: close\r\n\r\n" + body);
    return readResponse(client);
}

// ==================== Portal 校园网 RSA 加密 ====================

/** 固定 256 位 hex 输出 */
String rsaEncrypt(const String &plaintext) {
    mbedtls_mpi E, M, P, C;
    mbedtls_mpi_init(&E); mbedtls_mpi_init(&M);
    mbedtls_mpi_init(&P); mbedtls_mpi_init(&C);

    mbedtls_mpi_read_string(&E, 16, RSA_E_HEX);
    mbedtls_mpi_read_string(&M, 16, RSA_M_HEX);
    mbedtls_mpi_read_binary(&P, (const unsigned char *)plaintext.c_str(), plaintext.length());
    mbedtls_mpi_exp_mod(&C, &P, &E, &M, nullptr);

    size_t keyLen = (mbedtls_mpi_bitlen(&M) + 7) / 8;
    unsigned char *buf = new unsigned char[keyLen];
    mbedtls_mpi_write_binary(&C, buf, keyLen);

    String result; result.reserve(keyLen * 2);
    for (size_t i = 0; i < keyLen; i++) {
        char h[3]; snprintf(h, sizeof(h), "%02x", buf[i]); result += h;
    }
    delete[] buf;
    mbedtls_mpi_free(&E); mbedtls_mpi_free(&M);
    mbedtls_mpi_free(&P); mbedtls_mpi_free(&C);
    return result;
}

// ==================== Portal 登录 ====================

/**
 * 校园网 Portal 认证
 * 流程: GET http://123.123.123.123/ → 提取重定向 URL → 提取 mac
 *       → plaintext = password + ">" + mac → RSA 加密 → POST 登录
 * @return true = 认证成功
 */
bool portalLogin() {
    Serial.println("\n[Portal] 开始校园网认证...");

    // Step 1: 获取 Portal 索引页
    Serial.print("[Portal] GET "); Serial.println(PORTAL_INDEX);
    WiFiClient client;
    if (!client.connect("123.123.123.123", 80)) {
        Serial.println("[Portal] ❌ 无法连接 123.123.123.123");
        return false;
    }
    client.print("GET / HTTP/1.1\r\nHost: 123.123.123.123\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n");
    String res;
    unsigned long to = millis() + 5000;
    while (millis() < to) {
        while (client.available()) { res += (char)client.read(); to = millis() + 1000; }
        if (!client.connected() && !client.available()) break;
        delayLed(10);
    }
    client.stop();

    if (res.length() == 0) {
        Serial.println("[Portal] ❌ 索引页无响应");
        return false;
    }

    // Step 2: 提取重定向 URL
    String body   = extractBody(res);
    String script = extract(body, "<script>", "</script>");
    String redirectUrl = extract(script, "'", "'");
    if (redirectUrl.length() == 0) redirectUrl = extract(script, "\"", "\"");
    Serial.printf("[Portal] 重定向: %s\n", redirectUrl.c_str());

    // Step 3: 解析 query string, 提取 mac
    int qm = redirectUrl.indexOf('?');
    String qs = (qm != -1) ? redirectUrl.substring(qm + 1) : "";
    String mac;
    int mi = qs.indexOf("mac=");
    if (mi != -1) {
        int me = qs.indexOf('&', mi);
        mac = qs.substring(mi + 4, (me == -1) ? qs.length() : me);
    }
    if (mac.length() == 0) mac = "111111111";
    Serial.printf("[Portal] mac = %s\n", mac.c_str());

    // Step 4: 明文 + RSA 加密
    String plaintext = String(PORTAL_PASSWORD) + ">" + mac;
    String encrypted = rsaEncrypt(plaintext);
    Serial.printf("[Portal] 密文 (%d chars)\n", encrypted.length());

    // Step 5: 构建 POST
    String loginUrl = redirectUrl.substring(0, redirectUrl.indexOf('?'));
    loginUrl.replace("index.jsp", "InterFace.do?method=login");

    String encQS = qs;
    encQS.replace("&", "%26");
    encQS.replace("=", "%3D");

    String postBody;
    postBody += "userId=" + String(PORTAL_USERNAME);
    postBody += "&password=" + encrypted;
    postBody += "&service=";
    postBody += "&queryString=" + encQS;
    postBody += "&operatorPwd=";
    postBody += "&operatorUserId=";
    postBody += "&validcode=";
    postBody += "&passwordEncrypt=true";

    // Step 6: 发送 POST 登录
    String host = loginUrl;
    host.replace("http://", "");
    int slash = host.indexOf('/');
    String path = (slash != -1) ? host.substring(slash) : "/";
    host = host.substring(0, slash);

    Serial.printf("[Portal] POST %s%s\n", host.c_str(), path.c_str());
    WiFiClient pClient;
    if (!pClient.connect(host.c_str(), 80)) {
        Serial.println("[Portal] ❌ 登录服务器不可达");
        return false;
    }
    pClient.print("POST " + path + " HTTP/1.1\r\nHost: " + host +
                  "\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                  "Content-Length: " + postBody.length() +
                  "\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n" + postBody);

    String loginRes;
    to = millis() + 5000;
    while (millis() < to) {
        while (pClient.available()) { loginRes += (char)pClient.read(); to = millis() + 1000; }
        if (!pClient.connected() && !pClient.available()) break;
        delayLed(10);
    }
    pClient.stop();

    // 检查响应 — 200 或 302 都算成功
    if (loginRes.indexOf("200 OK") != -1 || loginRes.indexOf("302") != -1 || loginRes.indexOf("success") != -1) {
        Serial.println("[Portal] ✅ 认证成功");
        return true;
    }
    Serial.printf("[Portal] ❌ 响应异常: %s\n", loginRes.substring(0, 200).c_str());
    return false;
}

// ==================== 网络检测 ====================

int pingTest() {
    WiFiClient c;
    c.setTimeout(3000);
    unsigned long t0 = millis();
    bool ok = c.connect("www.baidu.com", 80);
    unsigned long t1 = millis();
    c.stop();
    return ok ? (int)(t1 - t0) : -1;
}

// ==================== 时间同步 ====================

bool syncTime(int maxRetries = 5) {
    Serial.println("\n[Time] 同步网络时间...");
    for (int i = 0; i < maxRetries; i++) {
        HTTPClient http;
        http.setTimeout(10000);
        http.begin(TIME_API_URL);
        if (http.GET() == 200) {
            String payload = http.getString();
            http.end();
            int idx = payload.indexOf("\"currentTime\":");
            if (idx != -1) {
                int s = idx + 14;
                int e = payload.indexOf(",", s);
                if (e == -1) e = payload.indexOf("}", s);
                String ts = payload.substring(s, e); ts.trim();
                apiCurrentTime   = strtoull(ts.c_str(), NULL, 10);
                apiMillisAtUpdate = millis();
                timeSynced = true;
                Serial.printf("[Time] %llu\n", apiCurrentTime);
                return true;
            }
        }
        http.end();
        delayLed(1000 * (i + 1));
    }
    return false;
}

String getTimestamp() {
    if (!timeSynced) return "0";
    return String(apiCurrentTime + (millis() - apiMillisAtUpdate));
}

// ==================== 随机数 / MD5 ====================

String randomString(int n) {
    const char t[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    String r;
    for (int i = 0; i < n; i++) r += t[esp_random() % (sizeof(t) - 1)];
    return r;
}

String md5Hash(const String &in) {
    MD5Builder md5;
    md5.begin(); md5.add(in); md5.calculate();
    return md5.toString();
}

// ==================== CAS SSO 登录 → token ====================

String casLogin() {
    Serial.println("\n[CAS] 开始 SSO 登录...");
    cookieJar = COOKIE_INPUT;

    // Step 1: 获取 CAS 登录页
    String res = httpsGET("sso.dlut.edu.cn",
        "/cas/login?service=http://menjin.dlut.edu.cn/cser/static/menjin/index.html");
    saveCookie(res);

    String lt        = extract(res, "name=\"lt\" value=\"", "\"");
    String execution = extract(res, "name=\"execution\" value=\"", "\"");
    Serial.printf("[CAS] lt=%s\n", lt.c_str());
    Serial.printf("[CAS] execution=%s\n", execution.c_str());

    // Step 2: RSA 加密
    String data = String(CAS_USERNAME) + CAS_PASSWORD + lt;
    char rsa[512];
    strEnc(data.c_str(), "1", "2", "3", rsa);

    // Step 3: POST 登录
    String body;
    body += "rsa=" + String(rsa);
    body += "&ul=" + String(strlen(CAS_USERNAME));
    body += "&pl=" + String(strlen(CAS_PASSWORD));
    body += "&sl=0&lt=" + lt;
    body += "&execution=" + execution;
    body += "&_eventId=submit";

    res = httpsPOST("sso.dlut.edu.cn",
        "/cas/login?service=http://menjin.dlut.edu.cn/cser/static/menjin/index.html", body);
    saveCookie(res);

    String location = getHeader(res, "Location");
    Serial.printf("[CAS] redirect: %s\n", location.c_str());

    // Step 4: 跟随 ticket 重定向
    int p = location.indexOf("http://menjin.dlut.edu.cn");
    if (p == -1) { Serial.println("[CAS] ❌ 无 ticket 重定向"); return ""; }
    location = location.substring(p);
    res = httpGET("menjin.dlut.edu.cn", location);
    saveCookie(res);

    // Step 5: 加载 index 页获取 token
    res = httpGET("menjin.dlut.edu.cn", "/cser/static/menjin/index.html");
    saveCookie(res);

    // Step 6: 提取 shfb-token
    int t = cookieJar.indexOf("shfb-token=");
    if (t == -1) { Serial.println("[CAS] ❌ 未找到 token"); return ""; }
    int e = cookieJar.indexOf(";", t);
    String token = cookieJar.substring(t + 11, e);
    Serial.printf("[CAS] ✅ token: %s...\n", token.substring(0, 20).c_str());
    return token;
}

bool updateToken() {
    Serial.println("\n[Token] 更新中...");
    String tk = casLogin();
    if (tk.length() > 0) { currentToken = tk; lastTokenUpdate = millis(); return true; }
    Serial.println("[Token] ❌ 更新失败");
    return false;
}

void checkToken() {
    if (millis() - lastTokenUpdate >= TOKEN_UPDATE_INTERVAL) {
        Serial.println("[Token] 周期到了, 刷新...");
        updateToken();
    }
}

// ==================== 开门 ====================

bool executeOpenDoor(const String &token) {
    String ts   = getTimestamp();
    String rand = randomString(4);
    String sign = md5Hash(ts + "#2323dsfadfewrasa3434#" + rand) + rand;

    Serial.println("\n[开门] 发送请求...");
    Serial.printf("  ts=%s  rand=%s  sign=%s\n", ts.c_str(), rand.c_str(), sign.c_str());

    String postData;
    postData += "commandCode=OPEN";
    postData += "&conditions=%7B%22personId%22%3A%22";
    postData += CAS_USERNAME;
    postData += "%22%2C%22delStatus%22%3A%220%22%7D";
    postData += "&deviceCode=";
    postData += DeviceCode;
    postData += "&isCommon=yes";
    postData += "&pageSize=-1";
    postData += "&projectCd=DA_LIAN_LI_GONG_MENJIN";
    postData += "&token=" + token;

    HTTPClient http;
    http.begin("http://menjin.dlut.edu.cn/cser/device/info/command/sendRoomBatch");
    http.setTimeout(10000);
    http.addHeader("AUTH-SIGN", sign);
    http.addHeader("AUTH-TIMESTAMP", ts);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");

    int    code = http.POST(postData);
    String resp = http.getString();
    http.end();

    Serial.printf("[开门] HTTP %d  %s\n", code, resp.c_str());
    return (resp.indexOf("\"success\":true") != -1);
}

void openDoorProcess() {
    Serial.println("\n══════════ 开门流程 ══════════");
    setLed(LED_DOOR_OPENING);
    delayLed(400);  // 确保蓝色快闪可见

    bool ok = executeOpenDoor(currentToken);
    if (ok) {
        Serial.println("[开门] ✅ 成功!");
        setLed(LED_SUCCESS);
        delayLed(800);
        setLed(LED_READY);
        return;
    }

    // 失败 → 刷新 token → 重试
    Serial.println("[开门] 首次失败, 刷新 token 重试...");
    setLed(LED_CAS_LOGIN);
    if (!updateToken()) {
        Serial.println("[开门] ❌ Token 刷新失败");
        setLed(LED_ERROR);
        return;
    }

    setLed(LED_DOOR_OPENING);
    delayLed(400);  // 确保蓝色快闪可见
    ok = executeOpenDoor(currentToken);
    if (ok) {
        Serial.println("[开门] ✅ 重试成功!");
        setLed(LED_SUCCESS);
        delayLed(800);
        setLed(LED_READY);
    } else {
        Serial.println("[开门] ❌ 重试仍失败");
        setLed(LED_ERROR);
    }
}

// ==================== setup / loop ====================

void setup() {
    Serial.begin(115200);
    delay(1000);
    pinMode(LED_PIN, OUTPUT);
    pinMode(BOOT_BUTTON_PIN, INPUT_PULLUP);
    pinMode(DOOR_SENSOR_PIN, INPUT_PULLDOWN);

    // WS2812 初始化
    leds.begin();
    leds.setBrightness(20);

    Serial.println("\n╔══════════════════════════════╗");
    Serial.println(  "║  ESP32-S3 校园网 + 门禁开门  ║");
    Serial.println(  "╚══════════════════════════════╝");

    // ---- 1. WiFi ----
    setLed(LED_WIFI_CONN);
    Serial.printf("\n[WiFi] 连接 %s ...", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED) { delayLed(500); Serial.print("."); }
    Serial.printf("\n[WiFi] IP: %s\n", WiFi.localIP().toString().c_str());
    Serial.printf("[WiFi] 网关: %s\n", WiFi.gatewayIP().toString().c_str());
    Serial.printf("[WiFi] DNS: %s\n", WiFi.dnsIP().toString().c_str());
    Serial.printf("[WiFi] 子网掩码: %s\n", WiFi.subnetMask().toString().c_str());

    // ---- 2. Portal 认证 ----
    setLed(LED_PORTAL_AUTH);
    delayLed(500);  // 确保黄色闪烁可见后再进入阻塞操作
    bool portalOk = portalLogin();

    // ---- 3. 诊断: 直接看能否从苏宁获取时间来判断网络是否通 ----
    if (!portalOk) {
        Serial.println("[诊断] Portal 未成功, 尝试直接获取网络时间...");
    }
    if (!syncTime()) {
        Serial.println("[诊断] ❌ 苏宁时间获取失败 — 网络不通!");
    } else {
        Serial.println("[诊断] ✅ 苏宁时间获取成功 — 网络已通");
    }

    // ---- 4. CAS 获取 token ----
    setLed(LED_CAS_LOGIN);
    delayLed(500);  // 确保紫色闪烁可见后再进入阻塞操作
    if (!updateToken()) {
        Serial.println("[Init] ❌ 初始 Token 获取失败!");
        setLed(LED_ERROR);
    } else {
        setLed(LED_READY);
    }

    Serial.println("\n[系统] 就绪 — 按 BOOT 键开门");
}

void loop() {
    static unsigned long lastNetCheck  = 0;
    static unsigned long lastButton    = 0;
    static bool          lastBtnState  = HIGH;
    static bool          lastSensorState = LOW;
    static bool          wasNetDown     = false;

    unsigned long now = millis();

    // LED 动画更新
    updateLed();

    // Token 周期刷新
    checkToken();

    // 每 30 秒检测网络
    if (now - lastNetCheck >= 30000) {
        lastNetCheck = now;
        int ms = pingTest();
        if (ms >= 0) {
            Serial.printf("[NET] ✅ %d ms\n", ms);
            if (wasNetDown && ledState == LED_NET_DOWN) setLed(LED_READY);
            wasNetDown = false;
        } else {
            Serial.printf("[NET] ❌ 断网 (WiFi=%d)\n", WiFi.status());
            if (!wasNetDown && ledState == LED_READY) setLed(LED_NET_DOWN);
            wasNetDown = true;
        }
    }

    // BOOT 键 (下降沿)
    bool btn = digitalRead(BOOT_BUTTON_PIN);
    if (lastBtnState == HIGH && btn == LOW && (now - lastButton > DEBOUNCE_DELAY)) {
        lastButton = now;
        Serial.println("\n[BTN] BOOT 键按下 → 开门");
        openDoorProcess();
    }
    lastBtnState = btn;

    // 门磁传感器 (上升沿)
    bool sensor = digitalRead(DOOR_SENSOR_PIN);
    if (lastSensorState == LOW && sensor == HIGH && (now - lastButton > SENSOR_DELAY_TIME)) {
        lastButton = now;
        Serial.println("\n[BTN] 门磁触发 → 开门");
        openDoorProcess();
    }
    lastSensorState = sensor;

    delay(10);
}
