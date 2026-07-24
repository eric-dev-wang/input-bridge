# Input Bridge 需求说明

> 文档状态：TCP 协议迁移已经完成。本文件描述产品约束、边界和验收范围；具体运行行为以源码和测试为准，TCP 协议定义以 [`docs/tcp-protocol.md`](tcp-protocol.md) 为准。

## 1. 产品定义

Input Bridge 是一个通过 USB ADB 将 Android 手机上的临时文本推送到 Android Studio 插件，并在 Tool Window 中展示和复制的本地文本桥接工具。

Android 是文本输入端；Android Studio 插件是文本查看和复制端。用户通过正常的系统复制粘贴操作，将文本手动粘贴到目标位置。

当前实现不自动向其他应用输入文本，不模拟键盘，也不操作电脑当前焦点窗口。

## 2. 项目目标

### 2.1 核心链路

```text
Android 输入文本
        ↓
Android App 内存状态和异步持久化
        ↓
Android Foreground Service 中的 TCP Server
        ↓
ADB forward tcp:18080 tcp:18080
        ↓
Android Studio Plugin TCP Client
        ↓
Tool Window 实时展示文本
        ↓
用户点击 Copy
        ↓
文本进入系统剪贴板
        ↓
用户手动粘贴
```

### 2.2 用户目标

用户应能够：

1. 在 Android 手机上打开 App。
2. 使用任意输入法输入中文、英文、Emoji、多行文本或代码。
3. 在 Android Studio 中打开 Input Bridge Tool Window。
4. 看到手机端文本的初始快照和后续实时更新。
5. 点击 Copy，将当前显示文本写入系统剪贴板。
6. 点击 Copy & Clear，在复制成功且版本未冲突时清空手机文本。
7. 通过 Reconnect 恢复断开的设备或 TCP 连接。

### 2.3 技术目标

- 只通过 USB ADB 通信，不依赖局域网。
- Android Server 只监听 `127.0.0.1:18080`。
- 使用持久 TCP 连接推送文本变化，不使用轮询。
- 支持 Unicode、中文、英文、Emoji、换行、制表符和代码片段。
- 所有 ADB、Socket 和 Clipboard 阻塞操作都不运行在 IntelliJ EDT。
- 所有连接和命令操作都使用明确的超时。

## 3. 模块边界

根 Gradle 项目包含两个产品模块、一个共享业务协议模块和三个纯 Kotlin/JVM 连接模块：

```text
app/
protocol/
core/framing/
core/connection-client/
core/connection-server/
android-studio-plugin/
```

### 3.1 `app/`

负责文本输入、当前状态、DataStore 异步持久化、Foreground Service 和 TCP Server。

### 3.2 `protocol/`

负责版本化业务 TCP wire models、消息类型、序列化配置和业务协议版本。该模块为纯 Kotlin/JVM，不依赖 Android、Ktor、DataStore、Koin 或 IntelliJ Platform。

### 3.3 `core/framing/`

只负责长度前缀字节 framing，不依赖 `protocol`，最大 payload 为 `512 KiB`。

### 3.4 `core/connection-client/` 与 `core/connection-server/`

分别负责 TCP client/server socket 生命周期、framing、读写线程、单连接限制和 Ping/Pong。它们依赖 `protocol` 与 `core/framing`；业务握手、快照和清空逻辑仍由产品模块负责。

### 3.3 `android-studio-plugin/`

负责 Tool Window、ADB 定位与设备处理、端口转发、TCP Client、连接状态、Clipboard 写入和 Copy & Clear。

## 4. 非目标和硬边界

当前实现不得包含：

- 自动向当前焦点输入框输入文字。
- 模拟 `Ctrl+V`、键盘事件、Enter、`SendInput` 或 UI Automation。
- Global Hotkey、全局键盘 Hook 或后台快捷键。
- 读取系统剪贴板、IDE 编辑器、项目源代码或电脑文件。
- Windows 独立客户端、云服务、账户系统或局域网同步。
- Android → Windows 之外的数据方向。
- 后台自动重连；连接恢复由用户点击 Reconnect 触发，并最多重建一次 ADB forward。
- 可配置端口和设置持久化。

## 5. 总体架构

### 5.1 地址和端口

Android Server 只绑定：

```text
127.0.0.1:18080
```

电脑端执行：

```bash
adb forward tcp:18080 tcp:18080
```

Plugin 连接：

```text
127.0.0.1:18080
```

手机端口和 ADB forward 端口固定为 `18080`，端口值集中定义在 `:protocol` 和各自的网络配置中，不得散落硬编码。

### 5.2 状态来源

Android `TextRepository.state` 的内存状态是 UI 和 Server 的唯一可信来源。DataStore 只负责异步持久化和启动恢复，不得阻塞输入或 TCP 推送。

Plugin 以当前连接收到的初始快照和 `text_changed` 事件更新 Tool Window。不得通过定时任务补偿 TCP 事件。

## 6. Android App 需求

### 6.1 输入和状态

输入框必须支持多行、中文、英文、Emoji、代码、换行和制表符，不自动发送或修改内容。

最大长度为 `8,000` 个字符。超过限制时阻止继续输入，不得静默截断。

状态模型：

```kotlin
data class TextState(
    val text: String,
    val version: Long,
    val updatedAt: Long,
)
```

实际文本变化时版本号递增；相同文本不递增。用户 Clear 和服务端成功 Clear 使用同一个 Repository 状态源。

### 6.2 持久化

- 使用 App 私有目录中的 DataStore。
- 只保存当前状态，不保存文本历史。
- 进程被普通回收后重新打开可以恢复未清空文本。
- 输入路径不等待 DataStore 写入完成。
- 持久化失败通过 Repository 结果和 UI 状态反馈。

### 6.3 Foreground Service 和 Server

- App 打开时自动启动 Foreground Service。
- 不提供用户停止入口。
- Service 启动 TCP Server，停止时关闭 Server 和活动会话。
- Server 重复启动不得重复绑定端口。
- 启动失败、停止和重试状态必须可观察。
- Server 只监听 `127.0.0.1`。
- 单条 TCP 消息最大为 `512 KiB`。
- Server 使用 15 秒 ping 周期和 30 秒空闲超时。

## 7. TCP 协议

协议版本为 `3`，完整定义见 [`docs/tcp-protocol.md`](tcp-protocol.md)。

### 7.1 Handshake

客户端连接后必须首先发送 `hello`，包含 `protocolVersion` 和 `requestId`。Server 回复 `hello_ack`，然后发送不带 request ID 的初始 `text_snapshot`。

版本不兼容、首条消息错误、无效 JSON、非法长度、无效 UTF-8 和非法消息顺序都必须返回明确错误，并在协议错误后关闭会话。

### 7.2 Server events

- `text_snapshot`：初始快照或响应明确快照请求。
- `text_changed`：当前 Repository 状态变化时主动推送。

快速连续更新可以在单个会话内合并，但最终必须提供最新状态，不得依赖轮询。

### 7.3 Client commands

- `hello`：建立连接并协商协议版本。
- `get_snapshot`：请求当前快照。
- `clear`：携带 `expectedVersion` 请求版本安全清空。

需要响应的命令必须使用唯一 `requestId`，响应必须回传相同 ID。Clear 成功返回 `clear_succeeded`；版本冲突返回 `version_conflict`，不得清空更新后的文本。

### 7.4 Error messages

错误使用统一 `error` 消息结构：

```json
{
  "type": "error",
  "code": "ERROR_CODE",
  "message": "Human-readable message",
  "details": {},
  "requestId": "request-1"
}
```

至少覆盖 `MALFORMED_MESSAGE`、`INVALID_HANDSHAKE`、`UNSUPPORTED_PROTOCOL_VERSION`、`UNEXPECTED_MESSAGE`、`INVALID_EXPECTED_VERSION`、`INITIAL_SNAPSHOT_TIMEOUT` 和 `TEXT_CLEAR_FAILED`。

## 8. Plugin Tool Window

### 8.1 UI 内容

Tool Window 至少显示：

- 连接指示器和状态。
- 当前设备显示名称。
- 最后同步时间。
- 可选择的只读多行文本区域。
- Length、Version 和 Last synced 元数据。
- Copy、Copy & Clear、Reconnect 操作。

状态必须区分连接中、已连接、无文本、离线和错误。离线或错误时保留最近一次文本，并突出 Reconnect。首次连接前显示等待提示，空文本显示无文本提示。

操作按钮必须在窄窗口中自动换行。Copy 和 Copy & Clear 不得把等待提示或空文本当作用户文本复制。

### 8.2 操作顺序

- Copy 只复制 Tool Window 当前快照，不发送清空命令。
- Copy & Clear 使用开始操作时的 text/version 快照。
- 必须先确认 Clipboard 写入成功，再发送 Clear。
- Clipboard 失败时不得清空。
- 版本冲突时保留已复制内容，刷新当前快照并显示明确提示。
- Reconnect 由用户触发，重新处理 ADB、forward 和 TCP；不执行后台自动重连。

## 9. ADB 和线程模型

ADB 查找顺序：Android Studio SDK 配置、`ANDROID_SDK_ROOT`、`ANDROID_HOME`、系统 `PATH`。

设备处理必须覆盖无设备、单设备、多设备、unauthorized、offline 和设备断开。多设备时允许用户从下拉列表选择；没有当前选择时使用选择器策略提供默认设备。

ADB 子进程必须在后台执行，捕获 stdout、stderr、退出码并使用 5 秒超时。TCP 连接超时为 2 秒，命令请求超时为 4 秒。不得无限重试；连接失败最多重建一次 forward。

所有状态回调必须安全地切回 IntelliJ EDT 更新 UI。Project 或 Tool Window 销毁后，后台结果不得继续触碰已销毁 UI。

## 10. Clipboard 和日志

Plugin 只允许写入系统 Clipboard，不读取、监控、保存或恢复 Clipboard 内容。

允许记录设备 serial、端口、TCP 状态、错误类型、文本长度和版本号。禁止记录完整文本、文本片段、消息正文或 Clipboard 内容。

示例：

```text
Fetched text successfully: version=17, length=123
```

## 11. 测试要求

### 11.1 Android 和协议测试

覆盖协议序列化、Handshake、初始快照、实时推送、多个会话、Clear 成功、版本冲突、持久化失败、非法消息、二进制帧、最大消息长度、错误响应、ping/timeout 配置、会话关闭和 Server 生命周期。

### 11.2 Plugin 测试

覆盖 TCP transport 注入、请求 ID 关联、Handshake 失败、超时、关闭、错误回调、Reconnect 生命周期、旧会话晚到回调、无轮询实时更新、Copy-before-Clear、Clipboard 失败和版本冲突。

### 11.3 Tool Window UI 测试

覆盖等待、空文本、实时文本、离线、错误、旧文本保留、Reconnect 强调、按钮禁用、窄窗口换行和 Panel 销毁。

### 11.4 真机集成测试

通过 ADB 完成：

1. 设备授权和 forward。
2. TCP 初始连接和快照。
3. 快速输入中文、英文、Emoji、多行文本和代码。
4. 不需要手动操作的实时更新。
5. Copy 与 Copy & Clear。
6. Clear 期间制造版本冲突。
7. USB 断开、重连和 Reconnect。
8. App/Service 停止、启动和会话恢复。

## 12. 构建、CI 和交付

本地验证命令：

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest :protocol:test
./gradlew :android-studio-plugin:test :android-studio-plugin:buildPlugin \
  :android-studio-plugin:verifyPlugin
```

GitHub Actions 在 Pull Request 和推送到 `main` 时运行完整验证矩阵，包括 Android lint、Android 单元测试、协议测试、Plugin 测试、Plugin 构建和 Plugin verifier。

交付物包括：

- 可构建的 Android App 和 Plugin 工程。
- TCP 协议文档和真机集成清单。
- 自动化测试及 CI 验证。
- 不包含生成目录、外部密钥或未声明依赖。
