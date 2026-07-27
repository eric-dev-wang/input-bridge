# Input Bridge

Input Bridge 是一个通过 USB ADB 将 Android 手机上的临时文本推送到 Android Studio / IntelliJ IDEA 插件的本地文本桥接工具。

Android App 负责输入当前文本；`core:data` 提供 Repository，`core:datastore` 负责 DataStore 持久化；IntelliJ Platform Plugin 负责接收实时更新、展示文本并写入系统剪贴板。用户最后在目标程序中手动粘贴。

业务 TCP 协议版本为 `3`，传输协议版本为 `1`。

## 当前架构

```text
Android App UI
        ↓
core:data TextRepository ← core:datastore persistence
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

业务协议版本为 `3`，共享模型位于 `protocol/`；加密传输协议版本为 `1`。完整消息定义见
[`docs/tcp-protocol.md`](docs/tcp-protocol.md)。

## 项目结构

```text
.
├── app/                    # Android App UI、Foreground Service、TCP Server
├── build-logic/convention/ # Android、Kotlin/JVM、Android Studio、Koin、Detekt Convention Plugins
├── protocol/               # 纯 Kotlin/JVM 共享协议模型
├── core/datastore/         # Android DataStore 实现和 TextDataSource
├── core/data/              # TextRepository 和数据层状态模型
├── core/designsystem/      # Input Bridge Compose Theme
├── core/framing/           # 纯 Kotlin/JVM 长度前缀 framing
├── core/crypto/            # 纯 Kotlin/JVM 共享密钥握手和加密传输
├── core/connection-client/ # 纯 Kotlin/JVM TCP client connection
├── core/connection-server/ # 纯 Kotlin/JVM TCP server connection
├── android-studio-plugin/  # Android Studio / IntelliJ IDEA Plugin 和 Tool Window
├── docs/                   # 需求、协议和开发规范
└── .github/workflows/      # CI 与发布工作流
```

所有模块属于同一个根 Gradle 项目，但保持各自的平台依赖和构建任务边界。App 通过 `core:data` 使用 Repository，不直接依赖 `core:datastore`。

## 构建和测试

从仓库根目录运行：

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :protocol:test
./gradlew detektAll
```

Detekt 默认会启用 auto-correct，适合本地整理代码。`detektAll` 只运行轻量的 main/test/androidTest 源码检查；
Release workflow 会单独执行带类型解析的 `detektRelease`，该任务会先依赖对应的 release Kotlin 编译：

```bash
./gradlew detektAll
./gradlew -PdetektAutoCorrect=false detektRelease
```

CI 和 Release 会显式关闭 auto-correct，并在发现问题时使构建失败：

```bash
./gradlew -PdetektAutoCorrect=false detektAll
```

CI 使用 Gradle Actions 管理 Gradle build cache，以减少重复的依赖、编译和 Detekt 检查开销。

规则配置位于 [`config/detekt/detekt.yml`](config/detekt/detekt.yml)。Detekt 扫描各模块的
`main`、`test` 和 `androidTest` Kotlin 源码，不使用 baseline；本地运行前请检查工作树，因为
auto-correct 可能修改源文件。项目不单独运行 ktlint；Detekt 仅通过 ktlint wrapper 启用
`Indentation` 规则，并将缩进宽度设为 4 个空格，不启用其它 ktlint 或 Compose 专用规则。

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

### 共享密钥构建参数

TCP 传输使用 App 和 Plugin 共同约定的共享密钥。构建 App 和 Plugin 时，必须传入相同的 `inputBridgeSharedSecret` Gradle 参数：

```bash
./gradlew \
  -PinputBridgeSharedSecret="my-local-input-bridge-secret" \
  :app:assembleDebug \
  :android-studio-plugin:buildPlugin
```

如果没有提供该参数，构建会使用开发默认值
`input-bridge-development-default-secret`。这个默认值只用于本地开发，发布生产版本时应显式提供自己的值，并且不要将共享密钥提交到 Git 仓库。

共享密钥不是直接作为 AES 密钥使用，而是在连接建立时用于握手和派生传输密钥。App 和 Plugin 的值不一致时，TCP 连接会认证失败。

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

- [TCP 协议](docs/tcp-protocol.md)
- [Git 提交规范](docs/git-commit-convention.md)
- [Changelog](CHANGELOG.md)

实现工作使用 `.worktrees/` 下的隔离 worktree。提交前运行对应模块测试、构建和必要的 Plugin verifier。
