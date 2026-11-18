# 学迹

面向 Android 的学习练习应用，界面采用 Jetpack Compose 和 Material 3。项目包含练习记录与计时、资料读取与导出、本地存储，以及可配置的模型接口。

## 当前进度

页面仍在开发中。首页、答题、练习结果、统计等页面的入口已经接入导航，对应的界面实现尚未加入。旧版 Word（DOC）导出所用的空白模板也尚未加入。

在 Windows 上已验证 Gradle Wrapper 能启动 Gradle 9.1.0，项目依赖可解析。实际运行 `:app:assembleDebug` 时，构建在 `:app:compileDebugKotlin` 失败。编译器报告缺少页面实现，以及 `StudyData.kt` 中的 `AtomicFile.exists()` 和 `StudyFiles.kt` 中的 `PdfDocument().use` 调用不兼容。当前没有生成 APK。

## 构建环境

- JDK 17
- Android SDK Platform 35（Android 15）
- Android SDK Build-Tools 36.0.0
- Gradle 9.1.0，由 Gradle Wrapper 下载并校验
- 需要向手机或模拟器安装时，安装 Android SDK Platform-Tools。应用最低支持 Android 8.0（API 26）。

可以通过 Android Studio 的 **SDK Manager** 安装 SDK 组件。第一次构建需要联网下载 Gradle 和依赖，依赖仓库包括 Google Maven、Maven Central 和 Gradle Plugin Portal。

## 在 Windows 上构建

将仓库克隆到本机，在 PowerShell 中进入项目根目录。

```powershell
git clone https://github.com/HadesScience/xueji-android.git
Set-Location xueji-android
```

如果本机还未设置 `JAVA_HOME` 和 `ANDROID_HOME`，请在当前 PowerShell 窗口设置它们。将示例路径替换为本机的实际路径。

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug
```

也可以在 Android Studio 中打开仓库根目录，将 **Gradle JDK** 设为 JDK 17，再同步项目并构建 **debug** 变体。

## 安装调试包

构建成功后，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。连接已开启 USB 调试的安卓设备，或启动模拟器，然后执行以下命令。

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

调试包使用 Android 构建工具生成的调试签名。模型 API 密钥由使用者在应用内填写。

- [Android 命令行构建说明](https://developer.android.com/build/building-cmdline)
- [Android Gradle Plugin 9.0 的版本要求](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
