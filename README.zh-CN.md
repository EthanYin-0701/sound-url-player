# IDE Radio（中文版）

**简体中文** | [English](README.md)

直接在 IDE 中播放 YouTube / Bilibili 的音频，并在专属 Tool Window 中提供实时的
波形 / 频谱可视化。

> 状态：**预览版 / 积极开发中**。M1 + M2 里程碑已实现（设置页、解析、播放管线、
> 可视化 Tool Window）。权威设计见 [plans/plugin_design.md](plans/plugin_design.md)。

---

## 功能

- **播放 YouTube / Bilibili 音频**（仅音频，不做视频渲染）。
- **设置页**：`Settings → Tools → IDE Radio`，提供 YouTube 与 Bilibili 两个独立栏位。
  粘贴链接 → 点 **解析预览** 拉取标题与时长 → 点 **Apply / OK** 保存。
- **播放源互斥（方案 A）**：同一时间只有一个栏位是激活源；未激活栏的播放/暂停/停止
  呈灰色。切换请点目标栏的 **设为当前源**（切换不会自动开播；若原栏在播会先停止）。
- **播放控制**（播放 / 暂停 / 停止）：设置页与 Tool Window 都有，操作的是**同一个
  播放会话**，状态同步。
- **循环播放**：默认开启（当前曲目播完自动重播，单曲循环）。
- **可视化 Tool Window**（底部「IDE Radio」）：实时音频可视化，**单击画布**在
  **波形图**与**频谱柱状图**之间切换。
- Tool Window 按项目窗口各有一份，但都反映同一个 application 级播放会话
  （多窗口场景状态一致）。

---

## 环境要求

- **IntelliJ IDEA 2025.3.x**（任意版本，`com.intellij.modules.platform`）。
- **yt-dlp** —— 把链接解析成音频流（标题、时长、直链）。
- **ffmpeg** —— 把远程音频流解码为 PCM 播放。

> IDE Radio **不会安装或内置** yt-dlp / ffmpeg。请按下文自行安装和升级；设置页会自动
> 检测两者并填入可执行文件路径，自动检测失败时也可用文件选择器指定。

---

## 安装 yt-dlp 与 ffmpeg

两个工具必须是 **IDE 进程本身**能访问到的可执行文件——「终端里能跑」并不等于
「IDE 能找到」（见[设置页检测不到时怎么办](#设置页检测不到时怎么办)）。

### macOS（Homebrew）

```bash
brew install yt-dlp ffmpeg
```

Homebrew 会装到 `/opt/homebrew/bin`（Apple 芯片）或 `/usr/local/bin`（Intel），
这两个目录都在插件的自动探测范围内。

### Windows

```powershell
# yt-dlp
winget install yt-dlp.yt-dlp
# 或：choco install yt-dlp   /   scoop install yt-dlp

# ffmpeg
winget install Gyan.FFmpeg
# 或：choco install ffmpeg   /   scoop install ffmpeg
```

> 用 `winget` / `choco` / `scoop` 安装后，请**重启 IDE / 终端**，让更新后的 `PATH`
> 生效。
>
> **ffmpeg 必须带 HTTPS 协议支持。** 播放时交给 ffmpeg 的是远程 `https://` 直链，
> 不含网络协议的精简/静态构建会报 “Protocol not found”。上面的 winget 包
> （gyan.dev *essentials*）以及 <https://ffmpeg.org/download.html> 的
> *essentials* / *full* 压缩包都可以。只需要 `ffmpeg` 本身，插件不使用 `ffprobe`。
>
> **yt-dlp 优先用真正的 `.exe`。** `PATH` 自动查找只匹配原名与 `.exe`，
> `pip` / `scoop` 生成的 `yt-dlp.cmd` / `yt-dlp.bat` 包装脚本可能检测不到。
> 建议用 `winget`、或官方 releases 的独立 `yt-dlp.exe`，或在设置页用
> **浏览…** 直接指定该包装脚本。

### Linux

```bash
# Debian / Ubuntu
sudo apt update && sudo apt install -y yt-dlp ffmpeg
# Fedora（ffmpeg 需先启用 RPM Fusion）
sudo dnf install yt-dlp ffmpeg
# Arch
sudo pacman -S yt-dlp ffmpeg
```

其它发行版：用各自的包管理器安装两者。

> 发行版自带的 yt-dlp 可能较旧。若解析报错（“unsupported URL”“yt-dlp 过旧”等），
> 建议安装最新版：

```bash
# 推荐用 pipx；或 pip
pipx install yt-dlp        # 或：pip3 install -U yt-dlp
```

> `pipx` 与 `pip --user` 会装到 **`~/.local/bin`**，该目录**不在**插件的探测列表里，
> 而且图形界面启动的 IDE 往往拿不到它。请把该目录加入 IDE 继承的 `PATH`，
> 或在设置页用 **浏览…** 明确指定 `~/.local/bin/yt-dlp`。
>
> yt-dlp 独立二进制不需要 Python；走 `pip` / `pipx` 则需要 Python 3.9+。

### 验证安装

```bash
# bash / zsh
yt-dlp --version
ffmpeg -version | head -n 1
```

```powershell
# PowerShell
yt-dlp --version
ffmpeg -version | Select-Object -First 1
```

两条都能打印版本号说明安装成功。注意这只验证了**你所在 shell 的** `PATH`，
IDE 仍可能找不到——见下一节。

### 保持更新

```bash
# macOS
brew upgrade yt-dlp ffmpeg
# Linux（pipx）
pipx upgrade yt-dlp
# 或（pip 安装）
python3 -m pip install -U yt-dlp
```

```powershell
# Windows
winget upgrade yt-dlp.yt-dlp Gyan.FFmpeg
# 或：choco upgrade yt-dlp ffmpeg   /   scoop update yt-dlp ffmpeg
```

> YouTube / Bilibili 会频繁变动播放接口，**请定期升级 yt-dlp**——遇到“解析失败 /
> unsupported URL”通常升级 yt-dlp 即可解决。

---

## 插件如何找到工具

插件会在**每次打开 `Settings → Tools → IDE Radio` 时检测** yt-dlp / ffmpeg 可用性，
并在 **外部工具** 区显示检测结果。

查找顺序（按设计）：

1. **已保存路径** —— 之前自动检测或 **浏览…** 选定的路径。若该路径后来失效
   （工具被删除、移动、失去可执行权限），设置页会红字提示，并且**不会静默回退**
   到 `PATH`；此时请点 **重新自动检测** 清除旧路径并重新查找。
2. **系统 `PATH`** —— 指 *IDE 进程* 的 `PATH`，不是你登录 shell 的 `PATH`。
3. **可信的常见安装位置** —— macOS：`/opt/homebrew/bin`、`/usr/local/bin`、`/usr/bin`；
   Linux：`/usr/bin`、`/usr/local/bin`；Windows：`where.exe` 的结果加上
   `%LOCALAPPDATA%\Microsoft\WinGet\Links`。

检测成功后，绝对路径会填入 Settings 的待保存状态，并在点击 **Apply / OK** 后持久化。
未检测到时，设置页会提示参考本文的手动安装与升级说明。

若解析/播放时仍缺工具，插件会给出可读错误并引导回到本设置页。

### 设置页检测不到时怎么办

现象：终端里 `yt-dlp --version` 正常，但**外部工具**区仍显示「未检测到」。
原因：从 Dock / Finder / 开始菜单启动的 IDE 不会读取你的 shell 配置，
所以它的 `PATH` 里没有 Homebrew、`~/.local/bin` 或刚装的工具。按可靠性排序：

1. 点 **浏览…**，选择 `which yt-dlp`（macOS/Linux）或 `where.exe yt-dlp`（Windows）
   打印出的绝对路径；
2. 从终端用 CLI 启动器（如 `idea`）启动 IDE，这样会继承你的 shell 环境；
3. 把工具装到上面列出的可探测位置里。

### 代理

yt-dlp 与 ffmpeg 是**子进程**，只继承 IDE 进程的环境变量——IDE 自身的
*HTTP Proxy* 设置**不会**传给它们，插件也没有单独的代理配置项。若需要代理
（例如访问 YouTube），请在启动 IDE 的环境里设置 `HTTPS_PROXY` / `HTTP_PROXY`
后重启 IDE（macOS 上可从终端启动 IDE，或使用 `launchctl setenv HTTPS_PROXY …`）。

### 高级设置与 Bilibili Cookie 支持

在 **Settings → Tools → IDE Radio** 页面底部，提供了一个可折叠的**高级设置**面板，包含：
- **解析选项（Cookie 配置）**：为缓解 Bilibili 412 风控 / 限流问题，可配置 yt-dlp 使用浏览器 Cookie 或文件：
  - **从浏览器读取 Cookie**：在下拉框中选择主流浏览器（如 `chrome`、`firefox`、`edge`），对应 `--cookies-from-browser`。
  - **Cookie 文件路径**：或指定导出的 `cookies.txt` 文件路径，对应 `--cookies <file>`。
  （若两者均填写，优先使用浏览器 Cookie）。
- **外部工具路径**：查看或手动指定 `yt-dlp` 与 `ffmpeg` 的可执行文件路径。

### 自动重试与错误处理
- 若解析因网络异常、412、429 或 5xx 服务端错误失败，插件会自动进行**至多 3 次**指数退避重试。
- 若仍然失败，错误提示行会显示清晰的中文原因以及 **[重试]** 按钮，方便你在调整设置或网络后一键重新解析。

---

## 快速上手

1. 按上文自行安装 **yt-dlp 和 ffmpeg**。
2. 打开 **Settings → Tools → IDE Radio**（外部工具区会显示 yt-dlp / ffmpeg 是否找到）。
3. 在 **YouTube**（或 **Bilibili**）栏粘贴链接，例如
   `https://www.youtube.com/watch?v=…` 或 `https://www.bilibili.com/video/BV…`。
4. 点 **解析预览** —— 下方显示标题与时长（仅为预览，尚未保存）。
5. 点 **Apply / OK** 保存该栏并执行正式解析。
6. 在该栏点 **▶ 播放**，开始出声；底部 **IDE Radio** Tool Window 实时显示波形
   （单击画布可切换为频谱柱状图，再点切回）。
7. 播放/暂停/停止在设置页或 Tool Window 里都能操作——两者驱动同一会话。

> 源互斥说明：一个栏位处于激活时，另一栏的播放/暂停/停止为禁用状态；切换请点目标栏
> 的 **设为当前源**（正在播放的曲目会先停止，新源等待你点 **播放**）。

---

## 隐私与限制

- 临时音频直链与请求头（可能含签名 token）**只保存在内存**：不落盘、不进日志、不进
  错误弹窗（界面展示统一脱敏：仅显示 host + 路径）。
- 仅播放音频，不渲染、不下载视频。
- 支持内容：可匿名流式访问的公开视频；需要登录 / 年龄验证 / 受地域限制 / DRM 的内容
  不支持，会给出可读错误。
- 链接必须含可播放的音频轨；若媒体没有纯音频格式，会提示“不支持的媒体格式”
  （绝不回退去播视频轨）。
- yt-dlp 请求从你本机发出，请自行遵守目标站点服务条款与当地法律。

---

## 开发

```bash
./gradlew build         # 编译 + 测试 + 打包
./gradlew runIde        # 启动带插件的沙箱 IDE
./gradlew verifyPlugin  # 插件与目标 IDE 兼容性校验
```

- 语言：Kotlin 2.3.20 + IntelliJ Platform Gradle Plugin，目标 IDEA 2025.3.5。
- 解析 yt-dlp 输出使用 `kotlinx-serialization`。
- 权威设计与决策：见 [plans/plugin_design.md](plans/plugin_design.md)
  （含架构、里程碑与待“真机验证”清单）。
- 项目/Agent 记忆：`AGENTS.md`、`.zhente/memory.md`。

---

## 许可证

MIT —— 详见 [LICENSE](LICENSE)。
