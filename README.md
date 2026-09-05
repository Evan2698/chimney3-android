# chimney3-android

基于 Chimney3 核心的 Android VPN 客户端。项目使用 Kotlin 编写 Android 界面和 VPN 服务，通过本地 `app/libs/vpn.aar` 调用 Go 实现的 Chimney 核心，引用了 `chimney3-go` 项目。

> 当前项目主要用于个人学习和测试。请确认你有权使用所配置的代理服务，并遵守所在地区的法律法规和网络服务条款。

## 功能

- 配置 TCP Proxy、UDP Proxy 和 DNS 地址。
- 支持填写代理服务的用户名和密码。
- 使用 Android `VpnService` 接管设备的 IPv4 流量，并将流量交给 Chimney 核心处理。
- 保存一份配置，应用重启后自动恢复。
- 显示连接中、已连接、断开中、已断开和错误等状态。

## 项目结构

```text
app/
├── libs/vpn.aar                         # Chimney Go 核心的 Android AAR
└── src/main/java/com/example/chimneyandroid/
	├── MainActivity.kt                  # 应用入口
	├── VPNFragment.kt                    # 配置页面和连接控制
	├── MyVpnService.kt                   # Android VPN 服务
	├── VpnConfig*.kt                     # 配置模型和本地 SQLite 存储
	└── IVpnService*.aidl                 # UI 与 VPN 服务的进程间通信接口
```

## 环境要求

- Android Studio（建议使用支持当前 Android Gradle Plugin 的版本）。
- JDK 11。
- Android SDK，至少安装 API 30；项目编译使用 API 36。
- Android 设备或模拟器，Android 11（API 30）及以上。
- 仓库中的 `app/libs/vpn.aar`。没有该文件时无法编译或运行核心功能。

## 构建与安装

在项目根目录执行：

```bash
./gradlew assembleDebug
```

生成的调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备：

```bash
./gradlew installDebug
```

也可以使用 Android Studio 打开项目根目录，等待 Gradle 同步完成后运行 `app` 配置。

## 使用方法

1. 安装并打开应用，进入 `VPN SETTING` 页面。
2. 填写以下配置：
   - `TCP Proxy URL`：TCP 代理地址，例如 `127.0.0.1:4521`。
   - `UDP Proxy URL`：UDP 代理地址，例如 `127.0.0.1:4521`。
   - `DNS Address`：VPN 使用的 DNS 地址，例如 `1.1.1.1`。
   - `User`：代理服务要求认证时填写用户名，否则可留空。
   - `Password`：代理服务要求认证时填写密码，否则可留空。
3. 点击 `Save Config` 保存配置。TCP Proxy、UDP Proxy 和 DNS Address 不能为空。
4. 点击 `Connect VPN`。
5. 第一次连接时，在 Android 系统弹出的 VPN 授权窗口中确认授权。
6. 状态显示 `Connected` 后，设备流量会通过该 VPN 处理。
7. 不再使用时点击 `Disconnect VPN`，等待状态变为已断开。

配置保存后，应用只保留最后一次保存的配置；再次点击 `Save Config` 会覆盖旧配置。

## 配置示例

```text
TCP Proxy URL: 127.0.0.1:4521
UDP Proxy URL: 127.0.0.1:4521
DNS Address:   1.1.1.1
User:          your-user
Password:      your-password
```

地址格式应与 `vpn.aar` 核心支持的格式一致。README 中的地址仅为示例，不能保证在所有环境中都可用。

## 工作原理

点击连接后，应用会：

1. 检查本地是否存在完整配置。
2. 请求 Android VPN 权限。
3. 创建 `VpnService` 虚拟网卡（地址 `10.8.0.2/24`，MTU 1500）。
4. 添加默认 IPv4 路由和配置的 DNS 地址。
5. 将虚拟网卡文件描述符、代理地址及认证信息传给 Go 核心。
6. 通过服务回调把运行状态同步到界面。

## 常见问题

### 点击连接提示配置不完整

请确认 TCP Proxy URL、UDP Proxy URL 和 DNS Address 均已填写并点击 `Save Config`。用户名和密码可以为空。

### 没有出现 VPN 授权窗口

请到系统设置中检查该应用的 VPN 权限。如果设备上已有其他 VPN 应用运行，先断开其他 VPN，再重试。

### 状态变为错误或立即断开

检查代理地址和端口是否可达、认证信息是否正确，并确认 `vpn.aar` 与当前设备 ABI 和 Android 版本兼容。可通过 Android Studio 的 Logcat 查看 `MyVpnService` 日志。

### 修改配置后连接仍使用旧参数

修改字段后必须先点击 `Save Config`，再断开并重新连接 VPN。

## 安全与限制

- 配置保存在应用私有 SQLite 数据库中，当前实现没有对用户名和密码做额外加密；请勿在不可信设备上保存敏感凭据。
- 当前只实现单配置，不支持配置列表、导入导出或自动测速。
- VPN 服务使用默认 IPv4 路由；IPv6、分应用代理和网络分流规则未在当前界面中提供。
- `vpn.aar` 是运行时核心，核心协议能力和兼容性由该 AAR 决定。

## 许可证

当前仓库未提供明确的许可证文件。如需分发或二次开发，请先确认本项目及 `vpn.aar` 依赖的许可证和使用条件。
