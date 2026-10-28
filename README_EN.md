# Yijuan · 一卷

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/yejian_icon_art.png" width="96" alt="Yijuan app icon">
</p>

<p align="center">
  <b>Your local library, your reading preferences.</b><br>
  Android e-book reader · Kotlin / Jetpack Compose · GPL-3.0
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b> ·
  <a href="https://github.com/yizisha/yijuan/releases">Downloads</a> ·
  <a href="https://github.com/yizisha/yijuan/issues">Issues</a>
</p>

Yijuan focuses on local books, continuous reading and personal notes. Its main navigation is **Library / Statistics / Notes / Settings**. Import your own books; no account registration is required.

This project builds on FlowReader's open-source code and is maintained independently in this repository, with its own name, versions and releases. Attribution is provided below.

## Download

Current release: **0.3.13** (versionCode **5710**).

| File | Purpose |
| --- | --- |
| [Android APK](https://github.com/yizisha/yijuan/releases/download/v0.3.13/yijuan-0.3.13-arm64.apk) | ARM64, approximately 29.36 MiB; Android 8.0 or later |
| [Release source](https://github.com/yizisha/yijuan/releases/download/v0.3.13/yijuan-0.3.13-source.zip) | Source for the 0.3.13 release commit, including the local speech runtime dependency |
| [SHA-256 checksums](https://github.com/yizisha/yijuan/releases/download/v0.3.13/SHA256SUMS.txt) | Verify the APK and source archive |

See [Releases](https://github.com/yizisha/yijuan/releases) for release notes. Books and speech models are not bundled with the app.

## Features

| Area | Current implementation |
| --- | --- |
| Library | Grid and list layouts, groups, unread / reading / finished filters, sorting and reading history; favorites work independently of groups |
| Book series | Combined series entries, manual volume order and a next-volume prompt at the end |
| Reading | Primarily EPUB, TXT and PDF; horizontal pagination, vertical scrolling, contents navigation, saved progress and bookmarks |
| Appearance | Imported fonts, text size, line and paragraph spacing, margins, gestures and page-turn settings; two visual styles and dark mode |
| Notes and search | Long-press selection, copying, underlines or soft highlights, notes and return-to-text; in-book and library-wide text search |
| Statistics | Weekly, monthly and yearly views, trends, reading metrics, heatmaps, goals and reminders |
| Files and data | Local import, folder scans, ZIP batch import and LAN transfer; file size and source location in book details; backup, restore, export and app lock |
| Optional read-aloud | Disabled by default; enable in Settings → App. Supports system speech and offline models downloaded or imported separately |

## Usage

- **Groups and favorites:** tap the library's group selector to switch groups. Reading-status filters follow the selected group; Favorites always uses the full library.
- **Series:** assign books through their menus, then manage members and volume order in the series view.
- **Reading and notes:** tap to show the reader toolbar. Settings provides font, typography, theme and page-turn controls. Long-press text to copy or add a note.
- **Read-aloud:** enable it in app settings, then use the reader's headphone control. Offline models can be downloaded, imported, selected and previewed inside the app, and use additional storage.
- **File location:** view or copy the recorded source in book details. Directory navigation depends on access provided by the originating app. The private reading copy can be saved to a folder of your choice.

## Release status

Version 0.3.13 prevents callbacks from outgoing reader pages from overwriting a newly requested chapter, and requires the requested chapter to be acknowledged before neighboring pages can take over.

**42 targeted regression tests**, the release build and signature checks passed. Verification against the reported EPUB on the original phone remains pending. These local results do not establish compatibility with every EPUB, device or speech model.

- [Yijuan changelog](CHANGELOG.md)
- [0.3.13 fix and validation report](docs/content-engine/reports/0.3.13-report-navigation-generation.md) (Chinese)
- [Settings structure](docs/SETTINGS_TREE.md) (Chinese)

Report issues in [this repository](https://github.com/yizisha/yijuan/issues), with the app version, phone model, file format and reproduction steps. You do not need to publish entire books or personal backups.

## Build from source

The locally validated environment is **JDK 21 and Android SDK 35**. The repository includes the Gradle Wrapper and sherpa-onnx 1.13.8 AAR. Speech models are not required to build the app.

```bash
git clone https://github.com/yizisha/yijuan.git
cd yijuan
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

On Windows, use `./gradlew.bat` for the same tasks. The first build requires network access for Gradle and other dependencies. Configure the Android SDK through your local environment or `local.properties`. The build script currently uses the debug signing configuration, so locally built packages may not match the signature of the published APK.

| Module | Responsibility |
| --- | --- |
| `app` | Application wiring, UI, repository implementations, importing and playback integration |
| `core` | Shared components, themes, typography and utilities |
| `domain` | Domain models, content locators and repository interfaces |
| `data` | Room database, DAOs, entities and incremental migrations |
| `feature/reader` | Pagination, reading positions, navigation and session timing |
| `plugin-api`, `plugin/tts` | Speech interfaces and the system TTS implementation bundled with the app |

## Origin and license

Yijuan builds on the open-source [HuZaiGong / FlowReader](https://github.com/HuZaiGong/flowreader) codebase. Thanks to the original author and upstream contributors for that foundation.

**Yijuan and FlowReader are maintained as separate projects.** This repository's branding, UI changes, feature development and `0.x` versions belong to Yijuan. Upstream `v56.x` history and contributions are identified separately in the changelog and are not presented as Yijuan releases.

The project continues under [GNU GPL-3.0](LICENSE), retaining upstream copyright and license notices. Speech libraries, fonts and other third-party resources retain their respective licenses. See [speech sources and notices](app/src/main/assets/speech-licenses/SOURCES.txt) and [font licenses](core/src/main/assets/font_licenses). Downloaded speech models have their own license terms.
