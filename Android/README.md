# 宿舍门(大工宿舍门禁 App)

从 ESP32 固件(`main.cpp` / `des.cpp`)移植到 Android 的门禁客户端:Kotlin + Jetpack Compose + OkHttp,协议与固件保持一致(CAS 登录、AUTH-SIGN 签名、DES 加密)。

## 功能

- 一键开门(大圆形按钮 + 状态提示)
- CAS 账号密码登录;支持「网页登录」解决二次认证(信任 Cookie 预置,等价固件 `COOKIE_INPUT`)
- 自动获取门锁编号(也可手动输入)
- 自动开门:打开 APP 时触发,30 秒内去重
- 保留后台 / 退出进程开关
- Cookie 持久化,Token 失效自动重新登录并重试

## 构建

需要 JDK 17 + Android SDK(compileSdk 36),或用 Android Studio 打开。

```bash
./gradlew assembleDebug    # 调试版
./gradlew assembleRelease  # 发布版
```

> release 签名密钥在本机生成、不随仓库分发。构建发布版前先生成自己的密钥:
>
> ```bash
> keytool -genkeypair -v -keystore release.keystore -alias doorapp \
>   -keyalg RSA -keysize 2048 -validity 10000
> ```
>
> 并在项目根目录创建 `keystore.properties`(内容见 [app/build.gradle.kts](app/build.gradle.kts) 顶部注释)。

## 下载

见 [Releases](../../releases) 页面,下载 `app-release.apk` 直接安装(需允许「安装未知来源应用」)。

## 说明

- 仅供个人学习与自用,请勿用于影响公共设施正常运行等用途。
- 密码以明文存于应用私有 SharedPreferences,个人工具够用;介意可换 EncryptedSharedPreferences。
