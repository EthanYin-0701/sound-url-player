# IDE Radio

[简体中文](README.zh-CN.md) | English

Play YouTube / Bilibili audio right inside your IDE, with a live waveform / spectrum
visualizer in a dedicated Tool Window.

> Status: **pre-release / under active development**. The M1 + M2 milestones are
> implemented (Settings, parsing, playback pipeline, visualizer Tool Window).
> See [plans/plugin_design.md](plans/plugin_design.md) for the authoritative design.

---

## Features

- **Play YouTube / Bilibili audio** from a URL (audio-only, no video rendering).
- **Settings page** at `Settings → Tools → IDE Radio` with two independent source slots
  (YouTube and Bilibili). Paste a link, click **Resolve Preview** to fetch the title and
  duration, then **Apply / OK** to persist.
- **Mutually exclusive sources (Plan A)**: only one slot can be the active source at a
  time. The inactive slot's transport controls are greyed out; use **Set as current
  source** to switch (playback is *not* auto-started on switch).
- **Transport controls** (play / pause / stop) both on the Settings page and in the
  Tool Window — both control the same playback session.
- **Loop playback** enabled by default (current track repeats; single-track loop).
- **Visualizer Tool Window** ("IDE Radio", bottom tool window): real-time audio
  visualization. Click the canvas to toggle between **waveform** and **spectrum bars**.
- The Tool Window exists per project window but reflects the single application-level
  playback session (multi-window aware).

---

## Requirements

- **IntelliJ IDEA 2025.3.x** (any edition, `com.intellij.modules.platform`).
- **yt-dlp** — used to resolve a URL into an audio stream (title, duration, stream URL).
- **ffmpeg** — used to decode the remote audio stream into PCM for playback.

> IDE Radio does **not** install or bundle yt-dlp / ffmpeg. Install and upgrade both tools
> yourself using the instructions below. The Settings page detects them automatically and
> fills the discovered executable paths; a file picker is available as a fallback.

---

## Installing yt-dlp & ffmpeg

Both tools must be executables that **the IDE process itself** can reach — a working
terminal command is not always enough (see
[If the Settings page can't find them](#if-the-settings-page-cant-find-them)).

### macOS (Homebrew)

```bash
brew install yt-dlp ffmpeg
```

Homebrew installs into `/opt/homebrew/bin` (Apple silicon) or `/usr/local/bin` (Intel);
both are among the locations the plugin probes automatically.

### Windows

```powershell
# yt-dlp
winget install yt-dlp.yt-dlp
# or: choco install yt-dlp   /   scoop install yt-dlp

# ffmpeg
winget install Gyan.FFmpeg
# or: choco install ffmpeg   /   scoop install ffmpeg
```

> After `winget`/`choco`/`scoop` installs, **restart your IDE/terminal** so the updated
> `PATH` takes effect.
>
> **Pick an ffmpeg build with HTTPS support.** Playback feeds ffmpeg a remote `https://`
> stream URL, so a minimal/static build without network protocols fails with
> "Protocol not found". The winget package above (gyan.dev *essentials*) and the
> *essentials* / *full* archives from <https://ffmpeg.org/download.html> are fine.
> Only `ffmpeg` itself is required — `ffprobe` is not used.
>
> **Prefer a real `.exe` for yt-dlp.** Auto-discovery on `PATH` only looks for the plain
> name and `.exe`, so a `pip`/`scoop` wrapper named `yt-dlp.cmd` / `yt-dlp.bat` may not be
> detected. Use `winget`, or the standalone `yt-dlp.exe` from the official releases, or
> point the Settings **Browse…** picker directly at the wrapper.

### Linux

```bash
# Debian / Ubuntu
sudo apt update && sudo apt install -y yt-dlp ffmpeg
# Fedora (ffmpeg needs RPM Fusion enabled)
sudo dnf install yt-dlp ffmpeg
# Arch
sudo pacman -S yt-dlp ffmpeg
```

Other distributions: install both from your package manager.

> Older distro packages may lag behind. If extraction fails ("unsupported URL", "outdated
> yt-dlp"), install the latest release instead:

```bash
# via pipx (recommended) or pip
pipx install yt-dlp        # or: pip3 install -U yt-dlp
```

> `pipx` and `pip --user` install into **`~/.local/bin`**, which is *not* one of the
> locations the plugin probes and is often missing from the PATH a GUI-launched IDE
> inherits. Either add that directory to the IDE's `PATH`, or use **Browse…** in Settings
> to select `~/.local/bin/yt-dlp` explicitly.
>
> The standalone yt-dlp binary needs no Python; the `pip`/`pipx` route needs Python 3.9+.

### Verify the installation

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

If both print a version, the tools are installed. Note that this checks **your shell's**
`PATH` — the IDE may still not find them; see the next section.

### Keeping them up to date

```bash
# macOS
brew upgrade yt-dlp ffmpeg
# Linux (pipx)
pipx upgrade yt-dlp
# or, if installed via pip
python3 -m pip install -U yt-dlp
```

```powershell
# Windows
winget upgrade yt-dlp.yt-dlp Gyan.FFmpeg
# or: choco upgrade yt-dlp ffmpeg   /   scoop update yt-dlp ffmpeg
```

> YouTube / Bilibili change their player endpoints often. **Update yt-dlp regularly** —
> "解析失败 / unsupported URL" is usually fixed by upgrading yt-dlp.

---

## How IDE Radio finds the tools

The plugin checks availability **whenever you open `Settings → Tools → IDE Radio`** and
shows the result in the **External tools** section.

Lookup order (per design):

1. **Saved path** — from an earlier detection or a **Browse…** selection. If that path
   later becomes invalid (tool removed, moved, no longer executable), the Settings page
   shows it in red and **does not silently fall back** to `PATH` — click
   **Re-detect** to clear the saved path and search again.
2. **System `PATH`** — the `PATH` of the *IDE process*, not of your login shell.
3. **Trusted common locations** — macOS: `/opt/homebrew/bin`, `/usr/local/bin`,
   `/usr/bin`; Linux: `/usr/bin`, `/usr/local/bin`; Windows: the `where.exe` result plus
   `%LOCALAPPDATA%\Microsoft\WinGet\Links`.

When found, the absolute path is filled into the pending Settings state and persisted by
**Apply / OK**. When missing, the Settings page points back to these manual instructions.

If a tool is still missing when you resolve/play, the plugin shows a readable error and
points you back to this Settings page.

### If the Settings page can't find them

Symptom: `yt-dlp --version` works in your terminal, but the **External tools** section
still says "not found". Cause: an IDE started from the Dock / Finder / Start menu does not
read your shell profile, so its `PATH` lacks Homebrew, `~/.local/bin`, or a freshly
installed tool. Fixes, in order of reliability:

1. Click **Browse…** and select the absolute path printed by `which yt-dlp` (macOS/Linux)
   or `where.exe yt-dlp` (Windows);
2. Start the IDE from a terminal via its CLI launcher (e.g. `idea`), which inherits your
   shell environment;
3. Install into one of the probed locations listed above.

### Proxies

yt-dlp and ffmpeg run as **child processes** and only inherit the environment of the IDE
process — the IDE's own *HTTP Proxy* settings are **not** passed to them, and the plugin
has no proxy field of its own. If you need a proxy (for example to reach YouTube), export
`HTTPS_PROXY` / `HTTP_PROXY` in the environment the IDE is started from and restart it
(on macOS: start the IDE from a terminal, or use `launchctl setenv HTTPS_PROXY …`).

### Advanced Settings & Bilibili Cookie Support

At the bottom of **Settings → Tools → IDE Radio**, there is an **Advanced Settings** collapsible panel containing:
- **Parsing Options (Cookies)**: To mitigate Bilibili 412 rate-limiting / wind-control errors, you can configure yt-dlp to use cookies:
  - **Browser cookies**: Select a browser (e.g. `chrome`, `firefox`, `edge`) from the dropdown (`--cookies-from-browser`).
  - **Cookies file**: Or specify the path to a `cookies.txt` file (`--cookies <file>`).
  (If both are specified, browser cookies take precedence).
- **External Tools Paths**: View or override detected paths for `yt-dlp` and `ffmpeg`.

### Retries & Error Handling
- If resolution fails due to network issues, 412, 429, or 5xx errors, the plugin automatically retries up to 3 times with exponential backoff.
- If it still fails, a readable error message and a **[Retry]** button are displayed next to the slot so you can retry manually after adjusting settings or network.

---

## Quick start

1. **Install yt-dlp and ffmpeg yourself** (see above).
2. Open **Settings → Tools → IDE Radio** (the External tools section shows whether
   yt-dlp / ffmpeg were found).
3. In the **YouTube** (or **Bilibili**) slot paste a link, e.g.
   `https://www.youtube.com/watch?v=…` or `https://www.bilibili.com/video/BV…`.
4. Click **Resolve Preview** — the title & duration appear (preview only, not yet saved).
5. Click **Apply / OK** to persist the slot and commit the resolution.
6. Click **▶ Play** in that slot. Audio starts; the **IDE Radio** Tool Window shows the
   live waveform (click the canvas to switch to spectrum bars and back).
7. Use play / pause / stop in either the Settings page or the Tool Window — both drive the
   same session.

> Mutually-exclusive sources: while one slot is active, the other slot's
> play/pause/stop are disabled. To switch, click **Set as current source** on the other
> slot (the running track is stopped first; the new source waits for **Play**).

---

## Privacy & limitations

- Stream URLs and HTTP headers (which may contain signed tokens) are **kept in memory
  only** — never persisted to disk and never written to logs/error dialogs (redacted
  display shows host + path only).
- Playback is audio-only; no video is rendered or downloaded.
- Supported content: publicly accessible videos that can be streamed anonymously.
  Content requiring a login / age verification, geo-restricted content, or DRM is not
  supported and will surface a readable error.
- The URL must contain a playable audio track; if the media has no pure-audio format the
  plugin reports "unsupported media format" (it never falls back to a video track).
- yt-dlp requests happen from your machine/network; you are responsible for complying with
  the target site's Terms of Service and your local laws.

---

## Development

```bash
./gradlew build        # compile + tests + assemble
./gradlew runIde       # launch a sandbox IDE with the plugin
./gradlew verifyPlugin # plugin-vs-IDE compatibility check
```

- Language: Kotlin 2.3.20, IntelliJ Platform Gradle Plugin, target IDEA 2025.3.5.
- JSON parsing (yt-dlp output) uses `kotlinx-serialization`.
- Authoritative design & decisions: [plans/plugin_design.md](plans/plugin_design.md)
  (includes architecture, milestones and the open "verify-on-device" checklist).
- Project agent/memory notes: `AGENTS.md`, `.zhente/memory.md`.

---

## License

MIT — see [LICENSE](LICENSE).
