# Changelog

本项目从 B1 起采用「版本号 + 变更分类」记录；更早版本为回溯整理。

## [B3] - 2026-11-07

第三个 beta：品牌更名为 Code PR，包名与仓库地址全面切换，并把排除规则彻底交给用户。

### Added

- **排除规则完全可编辑**：内置默认规则首次启动时自动并入列表，之后可像自定义规则一样自由添加、删除；设置页新增「恢复默认」一键找回误删规则
- 默认软排除规则现在真正参与扫描（此前只展示不生效）

### Changed

- **品牌更名**：应用名、首页标题与「关于」统一为 **Code PR**
- **包名 / applicationId**：`com.godot.tub.gd` → `com.wihee.code.pr`
- **仓库地址**统一为 `https://github.com/WIheee/Code-pr`（关于页跳转与公告拉取）
- 原生库名 `tubgd` → `codepr`，JNI 符号同步更新
- 内置提示词档位「TUB 同款」更名为「Code PR 同款」，内部 id 迁移为 `CODE_PR_STYLE`
- 设置存储键迁移为 `CODE_PR_SETTINGS`
- 版本号升级为 `B3`（versionCode 185）

### Fixed

- 修复删除内置默认规则后无法重新添加的问题（重复校验不再参照已删除的默认集合）

## [B2] - 2026-11-07

第二个 beta：提示词前移并可自定义，加入基于 GitHub 的应用内公告。

### Added

- **公告系统**：应用启动时静默拉取仓库根目录的 `Announcement.md`，公告 ID 大于本机已读 ID 时弹窗展示；`action: update` 的公告提供「前往更新」按钮，跳转 GitHub Releases
- 首页右上角新增公告入口（喇叭图标），可随时手动查看
- `Announcement.md`：公告数据源，含格式说明与模板，提交到 `main` 即生效
- **自定义提示词**：任意内置提示词可「复制并修改」，也可新建；支持 `{{version}}` 占位符，导出时替换为识别到的 Godot 版本

### Changed

- 「关于」中的仓库地址统一为 `https://github.com/WIheee/Code-pr`
- 新增 `INTERNET` 权限（仅用于拉取公告）
- **提示词移到文档最前面**：先给 AI 任务约束，再给目录树与源码；相应把文案里的 "above" 改成 "follow/below"
- 设置页「AI 提示词」改为管理器：内置列表 + 我的提示词，选中项高亮，支持编辑与删除
- 版本号升级为 `B2`（versionCode 184）

## [B1] - 2026-11-07

首个公开 beta。重点是把扫描、导出和界面整体重做了一遍。

### Added

- **C++ 原生扫描**：新增 JNI 扫描器（NDK + CMake），一级子目录多线程遍历，回传文件大小，避免 Kotlin 侧重复 `stat`
- **流式导出**：边生成边写盘，先写 `.part` 再原子改名，可开关
- **实验性零拷贝写入**：流式模式下文件内容直接写原始 UTF-8 字节，跳过 decode/encode，可单独开关
- **有界并行读流水线**：关闭零拷贝时启用，窗口 6 文件 / 8MB
- **底部 Tab 导航**：Material 3 `NavigationBar` + `SegmentedButton`
- **动态取色**：Android 12+ 跟随壁纸，低版本使用官方 baseline 色板
- **全英文 AI 提示词**：无 emoji，按 Godot 4.4 / 4.6 / 4.7 注入差异化规则
- **发布文档**：`CHANGELOG.md`、`docs/RELEASING.md`、`docs/releases/`
- 追加内置排除列表：依赖目录、前端缓存、字体、二进制、Office、数据库等

### Changed

- 版本号升级为 `B1`（versionCode 183）
- UI 全面改为 Material 3 Expressive：纯色、大圆角、弹簧动效、页面转场
- 目录树输出改为纯文本（`dir/` 形式），不再使用 emoji
- 生成文档标题与说明全部英文化
- 历史记录改为后台加载，流式结果仅保存预览
- 依赖升级：Compose BOM 2026.01.01、Material3 1.4.0、Kotlin 2.3.0
- 构建环境：JDK 21、compileSdk 37、Build Tools 37.0.0、NDK 30、CMake 4.1.2

### Fixed

- 修复 `viewBinding` 无用生成、`material`/`appcompat` 冗余资源导致的资源合并缓慢
- 修复大项目下目录树构建 O(n²)、排序重复小写化、行数统计逐字符循环
- 修复历史记录在主线程同步解析大 JSON 造成的启动卡顿

### Performance

- 目录遍历：C++ 多线程
- 文件读取：并行流水线 + 64KB 缓冲
- 输出：流式写盘 / 零拷贝（实验）

## [1.8.2] - 2026-01

- 底层重构，修复 19 项痛点
- 独立的多文件合并与提示词系统

## [1.1.1] - 2025

- 增加 AI 提示词工作流与 Godot 4.x 版本识别

## [1.0.0]

- 初始版本
