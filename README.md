# 手机文件服务器 (FileServerApp)

<div align="center">

一个**零依赖**的 Android 局域网文件服务器。  
把手机变成一个可通过浏览器访问的文件管理站：浏览、上传、下载、重命名、删除、新建文件夹，并支持图片 / 视频 / 音频在线预览、Markdown 渲染、代码语法高亮与行号。

纯 Java 手写 HTTP 服务器 + 单文件 Web 前端，**不引入任何第三方网络/UI 库**，APK 体积极小。

</div>

---

## ✨ 特性

- 🖥️ **浏览器即开即用**：手机与电脑连同一 Wi-Fi，浏览器输入 `http://<手机IP>:<端口>` 即可管理手机文件，无需在电脑安装任何软件。
- 📂 **完整文件管理**：目录浏览、文件上传（多文件）、下载、重命名、删除、新建文件夹。
- 🖼️ **在线预览 / 播放**：
  - 图片灯箱预览（jpg/png/gif/webp/bmp/svg）
  - 视频在线播放，支持**进度条拖动**（HTTP `Range` 分段，mp4/webm/mov/m4v）
  - 音频内嵌播放（mp3/wav/ogg/m4a/aac/flac）
- 📝 **文本增强预览**：
  - **编码自动识别**：UTF-8 / GBK/GB2312（GB18030 回退）/ UTF-16，解决中文乱码
  - **Markdown 渲染**为格式化 HTML（标题、列表、引用、表格、代码块、链接等），可一键切换「源码 / 渲染」
  - **代码语法高亮 + 行号**（java/js/ts/py/c/cpp/go/sql/json/yaml/html/xml/css 等，零依赖自研分词器）
  - 大文件保护：≤ 1MB 自动全量加载，> 1MB 提供「加载全文」按钮
- 📱 **移动端自适应**：针对手机 / 平板浏览器优化布局，操作按钮图标化，文件名超长省略。
- 🚀 **大目录性能优化**：`Os.lstat` 单次取元信息 + 大目录并行 stat + 前端分页渲染，万级目录也不卡。
- 🔌 **端口可配置**：默认 `8080`，被占用时自动在 `8080–8089` 重试；可在界面自定义并持久化。
- 🔔 **前台服务保活**：以 `dataSync` 前台服务运行，Android 8/13/14 的通知与权限均已适配。

## 🧱 技术栈

| 层       | 说明                                                |
| ------- | ------------------------------------------------- |
| 语言      | Java 17（后端与界面），无 Kotlin                           |
| 框架      | AndroidX（仅 `appcompat`、`core`）                    |
| HTTP 服务 | 基于 `ServerSocket` 的纯 Java 手写实现（`FileServer.java`） |
| 服务生命周期  | `ServerService` 前台服务                              |
| Web 前端  | 单文件 `index.html`（内联 CSS + 原生 JS），作为 Asset 由服务器返回  |
| 构建      | Gradle + Android Gradle Plugin 8.12.0（已配置国内镜像）    |

## 📊 架构概览

```
浏览器 (任意设备)  ──HTTP──►  FileServer (ServerSocket, 端口 8080~8089)
                                 │
                                 ├── /            返回 index.html（文件管理前端）
                                 └── /api/*       JSON / 文件流接口
                                       │
        前台服务 ServerService ◄───────┘   （持有并守护 FileServer，提供通知与保活）
                │
        MainActivity（权限申请、端口设置、启停、状态显示）
                │
        通过进程内静态回调 StatusCallback 与服务通信（兼容 MIUI 等对广播限制严格 ROM）
```

## 🔌 Web API

所有接口以 `path` 参数表示相对于外置存储根目录（`/storage/emulated/0`）的路径，服务器做了路径穿越防护。

| 方法   | 路径                        | 说明                                |
| ---- | ------------------------- | --------------------------------- |
| GET  | `/`                       | 返回内置的 Web 文件管理前端                  |
| GET  | `/api/list?path=`         | 列出目录内容（名称/类型/大小/修改时间）             |
| GET  | `/api/download?path=`     | 以附件形式下载文件                         |
| GET  | `/api/view?path=`         | 内联预览，支持 `Range`（`206` 分段），用于音视频拖动 |
| POST | `/api/upload?path=&name=` | 上传单个文件（请求体为文件字节流）                 |
| POST | `/api/delete?path=`       | 删除文件或目录（递归）                       |
| POST | `/api/mkdir?path=&name=`  | 新建文件夹                             |
| POST | `/api/rename?path=&name=` | 重命名文件或目录                          |

## 🚀 快速开始

### 环境要求

- Android Studio（Narwhal 2025.1.2 或更新）
- JDK 17
- Android SDK：`compileSdk 34` / `minSdk 21` / `targetSdk 34`

### 构建 APK

```bash
# Linux / macOS
./gradlew :app:assembleDebug

# Windows（PowerShell）
.\gradlew.bat :app:assembleDebug
```

产物位于：`app/build/outputs/apk/debug/app-debug.apk`

> 仓库根目录另附便捷脚本：`buildapk.bat`（构建）、`install.bat`（安装到已连接设备）、`clean.bat`（清理）。

### 安装与使用

1. 将 APK 安装到手机（`install.bat` 或手动安装）。
2. 首次启动按提示授予**所有文件访问权限**（Android 11+ 会跳转到系统设置页）和**通知权限**（Android 13+）。
3. 点击「启动服务器」，界面显示访问地址，例如 `http://192.168.x.x:8080`。
4. 同一 Wi-Fi 下的电脑 / 平板浏览器打开该地址即可管理文件。

## 🔑 权限说明

| 权限                             | 用途                            | 起始版本                  |
| ------------------------------ | ----------------------------- | --------------------- |
| `INTERNET`                     | 监听端口，提供 HTTP 服务               | —                     |
| `ACCESS_WIFI_STATE`            | 获取本机局域网 IP 用于展示访问地址           | —                     |
| `MANAGE_EXTERNAL_STORAGE`      | 读写全部文件（Android 11+ 需在设置页单独授权） | 11                    |
| `READ/WRITE_EXTERNAL_STORAGE`  | 旧版本存储读写                       | 6（`maxSdkVersion 32`） |
| `FOREGROUND_SERVICE`           | 运行前台服务                        | 9                     |
| `FOREGROUND_SERVICE_DATA_SYNC` | 声明前台服务类型为数据同步                 | 14                    |
| `POST_NOTIFICATIONS`           | 显示运行中的常驻通知                    | 13                    |

## 📁 项目结构

```
FileServerApp/
├── app/src/main/
│   ├── assets/index.html              # 单文件 Web 前端（含预览/高亮/Markdown 渲染）
│   ├── java/com/gwdb/fileserver/
│   │   ├── FileServer.java            # 纯 Java HTTP 服务器 + REST API
│   │   ├── ServerService.java         # 前台服务，守护服务器并处理端口重试/回调
│   │   └── MainActivity.java          # 权限申请、端口设置、启停与状态显示
│   ├── res/layout/activity_main.xml   # 主界面布局
│   └── AndroidManifest.xml            # 权限与服务声明
├── build.gradle / settings.gradle     # Gradle 配置（含国内镜像）
└── app/build.gradle                   # 模块构建配置
```

## ⚡ 性能与设计要点

- **目录列取**：`listFiles()` 仅做一次 readdir；每个条目用**单次** `Os.lstat` 同时取类型/大小/修改时间（旧实现每个文件 3 次 `stat`）。在 FUSE 存储上元数据操作是跨进程 IPC，大目录（≥128 项）用线程池**并行 stat**，显著提速。
- **服务↔界面通信**：早期用 `BroadcastReceiver`，但在 MIUI 等 ROM 上动态广播可能不投递，改为**进程内静态回调 + WeakReference**（`StatusCallback`），共享主线程无需额外 Handler，杜绝内存泄漏。
- **大文件列表前端分页**：每批 300 行 +「加载更多」，避免一次生成上千 DOM 节点造成卡顿。

## ⚠️ 安全提示

- **本应用不含任何身份验证**，任何能访问该端口的人都能读写你的手机文件。**请仅在可信的局域网 / 家庭网络下临时使用，用毕及时停止服务**，切勿将其暴露到公网或不受信的公共 Wi-Fi。
- 默认服务根目录为外置存储根，含路径穿越防护；前端渲染用户文件内容时统一做了 HTML 转义以规避注入。
- 本项目按「现状」提供，作者不对因使用造成的数据泄露或损失负责。

## 🐞 常见问题

- **打不开网页？** 确认手机与电脑在同一 Wi-Fi；检查防火墙是否拦截端口；核对界面显示的 IP 与端口。
- **端口被占用？** 启动时会自动尝试 `8080–8089`；也可在界面手动指定端口。
- **文本预览乱码？** 已自动识别 UTF-8 / GBK / UTF-16；若为其它罕见编码可反馈补充。
- **无法访问全部文件？** Android 11+ 需在系统设置中手动授予「所有文件访问权限」。

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。

---
