# TUB_GD — Godot 源码智能整合工具

把整个 Godot 项目打包成一份结构清晰、对 AI 友好的上下文文档（Markdown / TXT / XML），并附带可直接使用的英文任务提示词。

> 当前版本：**B2**（beta 2）

---

## 它解决什么问题

把几十个 `.gd`、`.tscn`、`project.godot` 一个个复制给网页版 AI，既慢又容易丢上下文。TUB_GD 扫描项目目录，自动过滤构建产物、依赖和二进制资源，把真正有用的源码整理成一份长文档。配合免费网页版 DeepSeek 等模型，就能让 AI 读懂整个项目并生成可直接运行的修改脚本。

---

## 核心能力

### 双引擎导出

- **文件夹扫描**：选择项目根目录，自动识别 Godot 版本、过滤缓存与依赖目录
- **多文件合并**：单独挑选散文件，忽略大小上限，适合零散脚本

### 三档导出深度

- **仅目录树**：只看结构，适合架构建议
- **仅项目配置**：只提取 `project.godot`、`.cfg` 等核心配置
- **完整导出**：目录树 + 全部文本源码

### 过滤与保护

- **软排除**：保留路径，省略内容
- **重排除**：连路径都不显示
- 内置排除列表覆盖常见生态：
  - 目录：VCS / IDE、构建产物（`build`、`dist`、`out`、`target`、`obj`）、依赖（`node_modules`、`vendor`、`Pods`、`.venv`）、前端缓存（`.next`、`.turbo`、`.parcel-cache`）、原生与游戏引擎（`CMakeFiles`、`Library`、`Temp`）、IaC（`.terraform`）等
  - 扩展名：字体、图片、音视频、3D 模型、压缩包、二进制与库、Office 文档、数据库、`.map` / `.log` 噪声文本
- 文件大小上限与防 OOM 处理

### 性能设计

- **C++ 原生目录遍历**（JNI + NDK），一级子目录多线程并行
- 文件大小由 C++ 回传，避免 Kotlin 侧重复 `stat`
- **流式导出**：边生成边写盘，先写 `.part` 再原子改名
- **实验性零拷贝**：流式模式下直接写入原始 UTF-8 字节，跳过 decode/encode
- 关闭零拷贝时启用**有界并行读流水线**（窗口 6 文件 / 8MB）
- 目录树构建 O(n)，排序键预计算

### 输出格式

- **Markdown**：代码高亮，推荐
- **TXT**：纯文本
- **XML**：CDATA 转义，带 `language` 属性

### AI 提示词

- **放在文档最前面**：AI 先读到任务约束，再读目录与源码，长上下文里不容易「忘掉」要求
- 内置四档：不注入 / 通用开发 / 进阶开发 / TUB 同款
- 全英文、无 emoji，按 Godot 4.4 / 4.6 / 4.7 注入差异化红线（`.uid`、`unique_id`、Jolt、`is_class`、`RichTextLabel`、`PackedArray`、类型化返回值）
- **可自定义**：任意内置提示词都能「复制并修改」，也可以从零新建；支持 `{{version}}` 占位符，导出时替换为识别到的版本
- TUB 模式强制输出可一键运行的 Python 补丁脚本

### 公告与升级提示

- 公告数据源是仓库根目录的 [Announcement.md](Announcement.md)，改文件提交到 `main` 即生效，不用发版
- 每个公告有单调递增的 ID；应用只在远程 ID 大于本机已读 ID 时弹窗，其余时候完全静默
- `action: update` 的公告会显示「前往更新」，跳转 GitHub Releases
- 首页右上角喇叭图标可随时手动查看

### 界面

Material 3 Expressive 风格：底部悬浮 `NavigationBar`、`SegmentedButton`、动态取色（Android 12+）、纯色主题、页面与列表动效。

---

## 使用

1. 安装 APK
2. 首次启动授予「所有文件访问权限」
3. 选择项目目录或散文件
4. 设置格式、深度、提示词
5. 导出后复制内容或分享文件

### 推荐工作流（DeepSeek）

1. 导出项目，提示词选 **TUB 同款**
2. 把生成的 `.md` 内容发给 DeepSeek
3. 描述需求，例如「实现一个武器系统，按提示词要求输出 Python 脚本」
4. 把脚本放到项目根目录运行，一键改项目

---

## 从源码构建

| 项 | 版本 |
| --- | --- |
| JDK | 21 |
| Gradle | 9.0.0 |
| Android Gradle Plugin | 8.13.0 |
| Kotlin | 2.3.0 |
| Compose BOM | 2026.01.01（Material3 1.4.0） |
| compileSdk / targetSdk / minSdk | 37 / 34 / 28 |
| Build Tools | 37.0.0 |
| NDK | 30.0.15729638 |
| CMake | 4.1.2 |

```bash
git clone https://github.com/WIheee/NEW-TUB_GD.git
cd TUB_GD
./gradlew :app:assembleDebug
```

> `compileSdk 37` 用于兼容 ACSIDE 注入的 `acslogger.aar`；AGP 8.13 的未测试提示可通过 `android.suppressUnsupportedCompileSdk` 处理。

### 项目结构

```
app/src/main/kotlin/com/godot/tub/gd/
├── MainActivity.kt          # 入口与导航
├── MainViewModel.kt         # 状态、设置、导出
├── ui/screens/              # 首页 / 合并 / 结果 / 设置 / 公告
├── ui/theme/                # M3 Expressive 主题
└── utils/
    ├── SourceMergeUtils.kt  # 扫描、过滤、格式化、提示词
    ├── AnnouncementUtils.kt # 远程公告拉取与解析
    └── NativeScanner.kt     # JNI 封装
app/src/main/cpp/            # C++ 多线程遍历
```

---

## 文档

- [AGENTS.md](AGENTS.md)：给 AI 编码代理的项目说明、架构地图与改动约定
- [Announcement.md](Announcement.md)：应用内公告数据源（格式说明见文件顶部）
- [CHANGELOG.md](CHANGELOG.md)：版本变更
- [docs/RELEASING.md](docs/RELEASING.md)：发版流程与 Release Notes 模板
- [docs/releases/B2.md](docs/releases/B2.md)：当前版本发布说明

---

## 开源协议

MIT。可自由使用、修改、分发与商用。
