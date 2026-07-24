# Input Bridge

Input Bridge 是一个通过 USB ADB 将 Android 手机上的临时文本推送到 Android Studio / IntelliJ IDEA 插件的本地文本桥接工具。

Android App 负责输入和保存当前文本；IntelliJ Platform Plugin 负责接收实时更新、展示文本并写入系统剪贴板。用户最后在目标程序中手动粘贴。

共享 TCP 协议版本为 `3`。

## 当前架构

```text
Android App TextRepository
        ↓
Foreground Service TCP Server
        ↓
ADB forward tcp:18080 tcp:18080
        ↓
Plugin TCP Client
        ↓
Tool Window live text
        ↓
Copy / Copy & Clear
```

固定连接地址：

```text
127.0.0.1:18080
```

协议版本为 `3`，共享模型位于 `protocol/`。完整消息定义见
[`docs/tcp-protocol.md`](docs/tcp-protocol.md)。

## 项目结构

```text
.
├── app/                    # Android App、Repository、Service、TCP Server
├── protocol/               # 纯 Kotlin/JVM 共享协议模型
├── core/framing/           # 纯 Kotlin/JVM 长度前缀 framing
├── core/connection-client/ # 纯 Kotlin/JVM TCP client connection
├── core/connection-server/ # 纯 Kotlin/JVM TCP server connection
├── android-studio-plugin/  # Android Studio / IntelliJ IDEA Plugin 和 Tool Window
├── docs/                   # 需求、协议和开发规范
└── .github/workflows/      # CI 与发布工作流
```

三个模块属于同一个根 Gradle 项目，但保持各自的平台依赖和构建任务边界。

## 构建和测试

从仓库根目录运行：

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :protocol:test
```

Plugin 默认使用 IntelliJ IDEA 2026.1.1 和 Android plugin 261.23567.138 构建及测试：

```bash
./gradlew :android-studio-plugin:test
./gradlew :android-studio-plugin:buildPlugin
./gradlew :android-studio-plugin:verifyPlugin
```

Plugin distribution 位于：

```text
android-studio-plugin/build/distributions/
```

CI 在 Pull Request 和推送到 `main` 时运行完整验证矩阵。发布通过推送
`v<major>.<minor>.<patch>` Tag 触发，实际构建版本由根目录
`gradle.properties` 中的 `bridgeVersion` 决定。

Tag 只是发布工作流的触发器，工作流不会强制比较 Tag 和 `bridgeVersion`；发布前应由维护者手动保持两者一致。Release 会生成 Android debug APK 和 Plugin distribution ZIP。

## 真机运行

1. 在 Android 手机上启动 App，并保持 Foreground Service 运行。
2. 连接 USB 并完成 ADB 授权。
3. 手动验证 forward：

   ```bash
   adb devices
   adb forward tcp:18080 tcp:18080
   ```

4. 在 Android Studio 中打开 `View → Tool Windows → Input Bridge`。
5. 点击 Reconnect 建立 ADB forward 和 TCP 连接。
6. 在手机输入文本，Plugin 会通过 TCP 实时更新。

## 功能边界

当前实现只包含 Android → Plugin 的文本桥接、展示和复制：

- 不自动向其他程序输入文本。
- 不模拟键盘、粘贴、Enter 或全局快捷键。
- 不读取系统剪贴板、IDE 编辑器或电脑文件。
- 不执行后台自动重连；用户点击 Reconnect 时会重建 ADB forward 一次并重试 TCP。
- 不使用云服务或局域网通信。

## Future Possibilities

- An Android-side button could explicitly ask the Plugin to fill the current text at the user's active cursor position. This is not implemented and requires a separate interaction and safety design.
- Wi-Fi ADB could be supported as an alternative transport. This would require allowing the Android App to access the local network and evaluating the Wi-Fi and local-network permissions required by Android 17; the current implementation remains USB ADB with loopback-only server access.

## 开发文档

- [完整需求说明](docs/requirements.md)
- [TCP 协议](docs/tcp-protocol.md)
- [Git 提交规范](docs/git-commit-convention.md)
- [Changelog](CHANGELOG.md)

实现工作使用 `.worktrees/` 下的隔离 worktree。提交前运行对应模块测试、构建和必要的 Plugin verifier。
