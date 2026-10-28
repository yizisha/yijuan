# Yijuan · 一卷

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/yejian_icon_art.png" width="96" alt="Yijuan">
</p>

<p align="center"><b>Organize your library. Enjoy your reading.</b></p>
<p align="center">
  <a href="https://github.com/yizisha/yijuan/releases/latest">Download</a> ·
  <a href="https://github.com/yizisha/yijuan/issues">Report an issue</a> ·
  <a href="README.md">简体中文</a>
</p>

Yijuan is a local e-book reader for Android. Import your books, organize groups and series, adjust typography to your preferences, and keep excerpts and notes. No account registration is required.

## Download

Get the APK from [Releases](https://github.com/yizisha/yijuan/releases/latest). The current version is **0.3.13**, requires **Android 8.0 or later**, and provides an **ARM64** package of approximately **29.36 MiB**.

Each release includes a source archive and SHA-256 checksums. Books and offline speech models are not bundled with the app.

## Supported formats

- **EPUB and TXT:** text reading with adjustable fonts and typography.
- **PDF:** original page layout, zoom, page navigation and saved reading position.

## Features

### Library

- Grid and list layouts, search, sorting and reading history.
- Custom groups and unread, reading and finished filters; favorites remain independent of groups.
- Combined series entries, manual volume order and a next-volume prompt.
- Local import, folder scans, ZIP batch import and LAN transfer.
- File size and source information in book details, with location copying, source association and saving a copy.

### Reading

- Horizontal pagination and vertical scrolling, contents, bookmarks and progress navigation.
- Adjustable fonts, text size, line and paragraph spacing, margins, gestures and page-turn behavior.
- Two visual styles, with light, dark and system appearance modes.
- Separate reading colors, night mode, brightness controls and break reminders.

### Notes and search

- Long-press text to select, copy, underline, highlight or write a note.
- Browse, filter, edit and export excerpts and annotations, or return to their source.
- In-book and library-wide text search.

### Statistics and data

- Weekly, monthly and yearly reading time, trends, heatmaps and metrics.
- Reading goals and reminders.
- Local backup, restore, automatic backup and library export.
- App lock and PIN.

### Read-aloud

Read-aloud is disabled by default and can be enabled in **Settings → App**. It supports system speech and separately downloaded or imported offline models, with in-app management, selection and previews.

## Usage tips

- Tap the library group selector to switch groups. Status filters follow that group; Favorites always uses the full library.
- Tap while reading to open the toolbar. Settings provides font, typography, theme and page-turn controls.
- Opening a source folder depends on access granted by the originating app. The private reading copy can be saved to a location of your choice.

See the [changelog](CHANGELOG.md) and [release notes](https://github.com/yizisha/yijuan/releases) for changes and known issues. Include the app version, device information and reproduction steps when reporting a problem.

## Development

Built with Kotlin and Jetpack Compose. See [Contributing](CONTRIBUTING.md) and [Architecture](ARCHITECTURE.md) for build instructions and module details.

## Attribution and license

Yijuan builds on the open-source [FlowReader](https://github.com/HuZaiGong/flowreader) codebase and is maintained and released independently in this repository. Thanks to HuZaiGong and the upstream contributors.

The project uses [GNU GPL-3.0](LICENSE) and retains upstream copyright and license notices. Upstream releases are archived separately in [Upstream history](docs/UPSTREAM_CHANGELOG.md). Speech components and fonts retain their own licenses; see [speech notices](app/src/main/assets/speech-licenses/SOURCES.txt) and [font licenses](core/src/main/assets/font_licenses).
