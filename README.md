# GzTv 红米电视 HOME 启动器

为小米/红米电视（CN 固件）打造的极简自研 HOME 启动器（`com.ts.launcher`）：开机直达广州台直播，按遥控器就能用。单文件 Java 源码（`HomeActivity.java`，约 900 行）、零依赖、无布局 XML、无 Gradle，APK 仅约 20KB。

> 开发与验证环境：红米 75" Pro 电视（MiTV-ASTR0/blueplanet，Android 11 / SDK 30，CN 固件 OS2.0.5.0.RSTAATV，32 位 armeabi-v7a）。其他小米电视理论上可用，未验证。

## 功能

- **开机直达直播**：HOME 即播放器，启动即播广州综合频道（多源轮换、断流自动重连）
- **双频道**：OK 键呼出频道列表切换广州综合 / 广州新闻
- **应用面板**：BACK 键呼出全屏图标应用列表（因为本应用就是桌面，"退出"即选择其他应用）；长按确认排序，顺序持久化
- **定时切台**：每天 18:58 自动切广州新闻，切换前左下角 10 秒倒计时（AlarmManager）
- **息屏停流**：SCREEN_OFF 时释放播放器，避免后台空耗
- **应用列表实时**：每次打开面板重新扫描（manifest 带 `<queries>`，解决 targetSdk 30+ 包可见性限制）

## 前置准备（构建机）

需要 JDK 17 与 Android SDK（build-tools 34.0.0、platform android-33）。macOS / Linux 示例：

```sh
# 1) JDK 17（Temurin）
#    https://adoptium.net/temurin/releases/?version=17 下载解压，或：
brew install --cask temurin@17

# 2) Android SDK 命令行工具
#    https://developer.android.com/studio#command-line-tools-only 下载解压后：
cd ~/android-sdk/cmdline-tools/latest/bin
./sdkmanager "build-tools;34.0.0" "platforms;android-33" "platform-tools"

# 3) 之后把 SDK 放到 ~/android/android-sdk（或用环境变量 SDK= 指向你的 SDK 目录）
```

构建只需以上两样；往电视安装还需要 adb（platform-tools 已含）。

## 构建

```sh
./build.sh            # 产出 GzTv.apk
```

关键点：

- `aapt2 link` 必须带 `--min-sdk-version 21 --target-sdk-version 33` 同级的显式版本参数（本工程为 `--min-sdk-version 21 --target-sdk-version 30`，否则部分设备安装报 -26）
- **签名**：脚本优先使用仓库本地 `release.keystore`（+ `release.keystore.pass`，均已 git-ignore），不存在则回退 debug 密钥；可用 `KS` / `KSPASS` / `KSALIAS` 环境变量覆盖
- 无 res / 无 assets：整个应用只有一个 Activity + manifest

## 安装到电视

```sh
# 1) 电视端开 ADB：设置 → 账号与安全 → ADB 调试（CN 固件每次重启后需重开）
adb connect <电视IP>:5555

# 2) 安装
adb install -r GzTv.apk

# 3) 设为默认桌面（利用 CN 固件 HOME 白名单身份 com.ts.launcher）
adb shell cmd package set-home-activity com.ts.launcher/.HomeActivity
```

重启电视即开机直达直播。`adb shell am start -n com.ts.launcher/.HomeActivity` 可先行试用。

## 卸载 / 恢复原桌面

```sh
# 先把桌面切回 PatchWall（避免卸载正在使用的 HOME）
adb shell pm enable com.mitv.tvhome
adb shell cmd package set-home-activity com.mitv.tvhome/.home.MiTvHome
adb uninstall com.ts.launcher
```

## 直播源说明

频道源内置在 `HomeActivity.java` 的 `CHANNEL_URLS`（广州综合/新闻各配主源+备用源）。部分 URL 带时效性令牌，过期失效属正常现象——失效时替换为你自己的可用直播源（m3u8），改完重跑 `./build.sh` 即可。

## 源码结构

| 文件 | 说明 |
|---|---|
| `HomeActivity.java` | 全部逻辑：直播播放（SurfaceView + MediaPlayer 多源轮换）、频道列表、应用面板（GridView + 排序持久化）、AlarmManager 定时切台 + 倒计时、息屏停流 |
| `AndroidManifest.xml` | HOME/LEANBACK_LAUNCHER intent-filter、`<queries>` 包可见性、INTERNET 权限 |

## 许可

源码按 MIT 许可发布（见 LICENSE）。
