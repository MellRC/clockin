# 工力全开

一个 Android 自动打卡助手 Demo。

## 功能

- 公司位置范围打卡。
- 上班、午休、下班自动打开企业微信。
- 定时任务和位置触发。
- 大小周、单休、双休排班。
- 实时工资估算。
- 锁屏提醒和权限分步引导。
- WebView Demo 与 APK 使用同一份页面资源。

## 项目结构

- `app/` Android 工程和原生能力。
- `demo.html` 可直接预览的界面 Demo。
- `app/src/main/assets/demo.html` APK 内置页面。
- `output/` 本地构建产物和验证文件。

## 构建

```powershell
./.gradle-toolchain/gradle-8.9/bin/gradle.bat --offline --no-daemon :app:assembleDebug
```

生成文件：`app/build/outputs/apk/debug/app-debug.apk`

## 安装

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次使用需要按引导授予定位、通知、精确闹钟、无障碍和后台运行权限。

## 环境

- Android SDK 35。
- Android Gradle Plugin 8.7.3。
- Gradle 8.9。
- JDK 17。
- 最低 Android 版本：26。

## 说明

这是个人项目和界面 Demo。自动打卡功能依赖系统权限以及企业微信客户端状态。
