# AGENTS.md

本项目的工作规则、技术栈与目录约定。供 AI/Agent 与开发者协作时遵循。

## 项目定位

**IDE Radio** —— IntelliJ Platform 插件：在 IDE 中播放 YouTube / Bilibili 的音频，
含波形/柱状可视化 Tool Window。仓库 `sound-url-player`，插件 ID 沿用
`e.y.sound-url-player`（FQN，发布后不可变）。

权威设计规格：`plans/plugin_design.md`（当前 v0.6，第四轮评审通过、可开工）。
按里程碑 M1（骨架+解析播放闭环）→ M2（可视化 Tool Window）→ M3（打磨）推进。

## 技术栈

- 语言：Kotlin（Kotlin JVM 插件 2.3.20）
- 构建：Gradle（Wrapper），`org.jetbrains.intellij.platform`（IPGP 2.18.1）
- 目标 IDE：IntelliJ IDEA `2025.3.5`；基础依赖 `com.intellij.modules.platform`
- 生产依赖：`kotlinx-serialization-json` 1.8.1（+ `org.jetbrains.kotlin.plugin.serialization`
  2.3.20 编译器插件）——解析 yt-dlp JSON
- 测试依赖：`junit:junit:4.13.2`
- 配置：`org.gradle.configuration-cache=true`、`org.gradle.caching=true`、
  `kotlin.stdlib.default.dependency=false`
- 版本：`1.0.0-SNAPSHOT`（group=`e.y`）

## 常用命令

- 构建：`./gradlew build`；增量编译：`./gradlew compileKotlin`
- 运行插件 IDE：`./gradlew runIde`；测试：`./gradlew check`
- 验证：`./gradlew verifyPlugin`；发布：`./gradlew publishPlugin`
- `.run/` 提供 Run IDE with Plugin / Run Tests / Run Verifications

## 目录约定

```
plans/plugin_design.md          权威设计规格（含 ADR/里程碑/待验证清单）
src/main/kotlin/e/y/ideradio/
  settings/        RadioSettingsState/Configurable/Panel、SourceSlotPanel
  resolve/         ResolveManager、TrackResolver、ytdlp/(DTO+Mapper)、
                   youtube/、bilibili/、external/(Locator+ProcessRunner)
  player/          PlaybackService、PlaybackState、FfmpegPcmSource、
                   AudioOutput、PcmBuffer
  viz/             VisualizerPanel、Waveform/SpectrumRenderer、Fft
  toolwindow/      IdeRadioToolWindowFactory
src/main/resources/META-INF/plugin.xml   插件清单（服务/设置页/工具窗注册处）
gradle/libs.versions.toml                版本目录（依赖版本唯一来源）
```

## 关键架构决策（详见设计 §2 / §6 / §7）

- 播放链路：yt-dlp（解析直链/标题/时长）→ ffmpeg 子进程（STREAM 模式解码 PCM）→
  SourceDataLine。
- 外部二进制**不随插件打包，也不由插件安装或升级**。Settings 自动检测 yt-dlp 与
  ffmpeg（已保存路径 → PATH →可信常见位置），找到后填入路径并随 Apply/OK 保存；
  未找到则提示参考 README 自行安装/升级，文件选择器作为检测失败的兜底。
- `ExternalProcessRunner` 双模式：CAPTURE（yt-dlp，排空/上限/超时）/ STREAM（ffmpeg，
  stdout 归调用方 consumer 独占）。
- 持久化用 bean（可变+默认值），运行时流信息（直链/请求头）仅内存、脱敏。
- YouTube / Bilibili 为互斥激活源（方案 A）：`selectedPlatform` 只读，变更走原子
  `selectPlatform()`/`play()`（单线程 executor 串行 + generation 丢弃旧回调）。
- 可视化：application 级 `PcmBuffer`（latest-only），面板 Swing Timer 拉取快照。

## 工作规则

1. 不要臆测文件内容，先读取/确认再修改；改动前不越界读取本工作目录之外的内容。
2. 生产源码写入 `src/main/kotlin`，包根 `e.y.ideradio`；改动需与设计文档保持一致；
   规格冲突时先更新设计（升版本号并加修订记录）再改代码。
3. 插件所有能力（服务、Configurable、Tool Window、Action 等）必须在 plugin.xml 声明。
4. 依赖版本统一维护在 `gradle/libs.versions.toml`，不在构建脚本硬编码。
5. 需求/行为变更同步更新 `CHANGELOG.md`（Keep a Changelog，追加 `[Unreleased]`）。
6. plugin.xml 的 `<name>`/`<vendor>`/`<description>` 仍为向导占位，正式对外前替换。
7. 涉及架构/重要决策同步更新 `AGENTS.md` 与 `.zhente/memory.md`。
8. 提交/里程碑收尾前运行 `./gradlew verifyPlugin` 与相关测试。
