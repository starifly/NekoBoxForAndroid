# NekoBoxForAndroid (nb4a) 项目架构与开发指南

本项目是一个基于 SagerNet 框架的 Android 代理客户端，底层核心是 `libcore`（基于 Go 语言的 `sing-box` 核心）。项目采用 `gomobile` 技术，将 Go 语言编写的底层核心编译为 Android AAR 库，供 Android 端的 Java/Kotlin 代码调用。

---

## 1. 项目目录结构 (Repository Schema)

### 1.1 顶层目录
- `app/`: Android 应用程序模块，包含所有的 UI、后台服务、数据库和配置解析逻辑。
- `libcore/`: Go 语言编写的底层核心，基于 `sing-box`，负责底层的网络代理、路由和协议实现。
- `buildScript/`: 包含用于初始化环境、编译底层 Go 核心、打包 AAR 等构建辅助 shell 脚本。
- `buildSrc/`: Gradle 构建配置，包含一些 Kotlin 编写的构建辅助工具。
- `gradle/`: Gradle Wrapper 目录。
- `.github/`: GitHub 配置目录，包含 GitHub Actions 工作流定义。

---

### 1.2 Android 应用程序模块 (`app/`)
Android 端的代码主要分为两个核心包：

#### 1.2.1 `io.nekohasekai.sagernet` (SagerNet 核心框架)
这是项目的核心框架，继承自 SagerNet：
- [`io/nekohasekai/sagernet/bg/`](app/src/main/java/io/nekohasekai/sagernet/bg): 后台服务模块。
  - `VpnService.kt`: Android VPN 服务的核心实现，负责拦截流量并传递给底层核心。
  - `ProxyService.kt`: 代理服务。
  - `BaseService.kt`: 基础服务。
- [`io/nekohasekai/sagernet/database/`](app/src/main/java/io/nekohasekai/sagernet/database): 数据库与偏好设置模块。
  - `SagerDatabase.kt`: Room 数据库定义，存储代理配置、分组、规则等。
  - `ProfileManager.kt` / `GroupManager.kt`: 配置和分组管理器。
- [`io/nekohasekai/sagernet/fmt/`](app/src/main/java/io/nekohasekai/sagernet/fmt): 各种代理协议的配置格式化与解析。
  - 支持 Shadowsocks, VMess, Trojan, Hysteria, Juicity, Naive, WireGuard 等协议的配置解析与转换。
- [`io/nekohasekai/sagernet/ui/`](app/src/main/java/io/nekohasekai/sagernet/ui): 各种 Activity 和 Fragment 界面。
  - `MainActivity.kt`: 应用主界面。
  - `SettingsFragment.kt`: 设置界面。
- [`io/nekohasekai/sagernet/widget/`](app/src/main/java/io/nekohasekai/sagernet/widget): 自定义 UI 控件。

#### 1.2.2 `moe.matsuri.nb4a` (NekoBox 专属扩展)
这是 NekoBox 专属的扩展和定制代码：
- [`moe/matsuri/nb4a/NativeInterface.kt`](app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt): 与底层 Go 核心 (`libcore`) 交互的 JNI 接口，实现了 `libcore.BoxPlatformInterface` 和 `libcore.NB4AInterface`。
- [`moe/matsuri/nb4a/SingBoxOptions.java`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java) / [`SingBoxOptionsUtil.kt`](app/src/main/java/moe/matsuri/nb4a/SingBoxOptionsUtil.kt): Sing-box 相关的配置选项和工具类。
- [`moe/matsuri/nb4a/proxy/`](app/src/main/java/moe/matsuri/nb4a/proxy): 额外的代理协议（如 AnyTLS, ShadowTLS, NekoBean 等）和配置绑定。
- [`moe/matsuri/nb4a/ui/`](app/src/main/java/moe/matsuri/nb4a/ui) / [`utils/`](app/src/main/java/moe/matsuri/nb4a/utils): 专属的 UI 控件和工具类。

#### 1.2.3 资源文件 (`app/src/main/res/`)
- `layout/`: 界面布局 XML 文件。
- `menu/`: 菜单 XML 文件。
- `xml/`: 偏好设置 XML 文件（如 `global_preferences.xml`, `shadowsocks_preferences.xml` 等）。
- `values-zh-rCN/`: 简体中文本地化字符串。

---

### 1.3 底层核心模块 (`libcore/`)
Go 语言编写的底层核心，负责高性能的网络处理：
- [`libcore/nb4a.go`](libcore/nb4a.go): Go 核心的入口，导出 `InitCore` 等函数，供 Android 端通过 JNI 调用。
- [`libcore/box.go`](libcore/box.go) / [`box_include.go`](libcore/box_include.go): 与 `sing-box` 核心的集成与初始化。
- [`libcore/build.sh`](libcore/build.sh): 编译 Go 核心的本地脚本。
- [`libcore/device/`](libcore/device/), [`ech/`](libcore/ech/), [`procfs/`](libcore/procfs/), [`protocol/`](libcore/protocol/), [`stun/`](libcore/stun/): Go 核心的子模块，处理设备、ECH、进程文件系统、自定义协议和 STUN 测试。

---

## 2. 构建流程 (Build Process)

项目的构建分为两步：
1. **编译底层 Go 核心**：
   - 使用 `gomobile` 工具，运行 `buildScript/lib/core/build.sh` 或 `libcore/build.sh`。
   - 编译生成 `app/libs/libcore.aar` 库。
2. **编译 Android 应用程序**：
   - 使用 Gradle 编译 Android 应用。
   - 运行 `./gradlew app:assembleOssRelease` 编译生成 OSS 版本的 APK，该步骤会自动将 `libcore.aar` 打包进 APK 中。

---

## 3. 开发备忘与协作规范 (Developer Notes)

### 3.1 GitHub Actions 小步快跑模式
- **背景**：项目所有者个人不做安卓开发，因此本项目的开发采用 **GitHub Actions 小步快跑** 的模式。
- **CI/CD 流程**：
  - 核心构建工作流定义在 [`.github/workflows/build.yml`](.github/workflows/build.yml) 中。
  - 每次向 `main` 分支提交代码或手动触发（`workflow_dispatch`）时，GitHub Actions 会自动运行构建。
  - 工作流会先检查并缓存 `libcore.aar`（基于 `libcore` 目录和构建脚本的哈希值），避免重复编译 Go 核心以节省时间。
  - 随后，工作流会使用 Gradle 编译生成 OSS 版本的 APK，并将生成的 APK 上传为 Artifact（命名为 `NekoBoxs`）。
- **开发建议**：
  - 开发者在修改代码时，应尽量保持**小步快跑**，每次完成一个微小的、自洽的修改后即提交代码。
  - 提交后，通过 GitHub Actions 自动验证编译是否通过，并下载生成的测试 APK 进行真机测试。

### 3.2 文档同步规范
- **核心要求**：每次对项目结构、关键模块、新增协议、构建流程或配置进行修改时，**必须**同步更新本文件 (`REPO_SCHEMA.md`)。
- **检查清单**：在每次提交前，请务必对照 `ROO_TODO.example.md` 中的检查清单，确保文档与代码保持 100% 一致。
