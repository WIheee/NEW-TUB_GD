# 发版流程

本文档描述 TUB_GD 的版本号规则、发布前检查清单，以及 GitHub Release 说明的模板。

---

## 版本号规则

- 正式版：`1.9.0`、`2.0.0`（语义化版本）
- 测试版：`B1`、`B2`…（当前为 beta 阶段）
- `versionCode` 每次发布必须递增，不能回退，否则无法覆盖安装
- 版本号写在 `app/build.gradle` 的 `defaultConfig` 中：

```groovy
versionCode 184
versionName 'B2'
```

> 应用内「关于」页读取的是系统 `PackageInfo.versionName`，无需手动同步。

---

## 发布前检查清单

- [ ] 更新 `app/build.gradle` 的 `versionCode` / `versionName`
- [ ] 更新 `CHANGELOG.md`，把「未发布」整理到对应版本
- [ ] 在 `docs/releases/<版本>.md` 写好发布说明
- [ ] 同步 `README.md` 中的版本号、构建环境、功能描述
- [ ] 本地构建通过：

```bash
./gradlew :app:assembleRelease
```

- [ ] 安装到真机，走一遍核心路径：
  - 文件夹扫描（完整导出 / TUB 提示词）
  - 多文件合并
  - 流式写入开关切换
  - 零拷贝开关切换（实验性）
  - 复制内容、分享文件
  - 历史记录读取
- [ ] 确认 APK 体积、启动速度无明显回退
- [ ] 打 tag 并推送：

```bash
git tag -a B2 -m "TUB_GD B2"
git push origin main --tags
```

- [ ] 在 GitHub Releases 创建版本，粘贴 `docs/releases/<版本>.md` 内容，上传 APK

---

## Release Notes 模板

```markdown
## TUB_GD <版本>

<一句话定位：这个版本主要解决什么问题>

### 新增
- …

### 改进
- …

### 修复
- …

### 性能
- …

### 构建信息
- JDK 21 / Gradle 9.0 / AGP 8.13
- compileSdk 37 / targetSdk 34 / minSdk 28
- NDK 30.0.15729638 / CMake 4.1.2

### 已知限制
- 零拷贝写入为实验特性，可在设置中关闭
- XML 导出仍走文本路径（CDATA 转义需要）
- 当前只打包 arm64-v8a

### 升级说明
- 直接覆盖安装即可；历史记录与设置会保留
```

---

## 产物与签名

- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 发布包：`app/build/outputs/apk/release/app-release.apk`
- 对外发布建议使用固定签名，并把 keystore 放在仓库外；不要把密码提交进版本库
- 若启用 R8，请在发版前验证提示词生成、文件读写、JNI 扫描均正常

---

## 已知注意事项

- `compileSdk 37` 是 ACSIDE 注入的 `acslogger.aar` 要求，AGP 8.13 会提示「未测试」，属正常
- 修改 `settings.gradle` 的仓库顺序会影响国内下载速度，发版前不要误删镜像配置
- `.cxx/`、`.kotlin/`、`build/`、`构建日志.log` 均为本地产物，不提交
