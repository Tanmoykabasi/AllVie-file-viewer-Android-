# AllVie 📁

> **Android File Viewer App** — View PDF, TXT, DOCX, XLS/XLSX, and PPT/PPTX in one clean, privacy-focused app.

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android)
![Language](https://img.shields.io/badge/Language-Kotlin-0095D5?logo=kotlin)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![License](https://img.shields.io/badge/License-MIT-yellow)

[![Latest Release](https://img.shields.io/github/v/release/Tanmoykabasi/AllVie-file-viewer-Android-?label=Latest)](https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-/releases)

---

## 📦 Download
Get the latest APK from [Releases](https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-/releases):
- **v3.0** (June 2026) — [Allvie.apk](https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-/releases/download/v3.0/app-debug.apk) (~55 MB)

---

## ✨ Features (v3.0)
- 📄 **Native Viewers**: In-app rendering for **PDF**, **Images**, **Plain Text**, **DOCX**, **XLS/XLSX**, and **PPT/PPTX**
- 🔎 **PDF Controls**: Zoom, scrolling, page controls, dark viewer background, and password-protected PDF support
- 📝 **DOCX Renderer**: Native `.docx` rendering with Apache POI, Canvas rendering, image support, and page caching
- 🎞️ **Presentation Viewer**: Native PPT/PPTX slide rendering with bitmap caching and smooth slide navigation
- 📊 **Spreadsheet Preview**: In-app XLS/XLSX table preview
- 🌙 **Dark Mode**: Improved readability across Files, Recents, Bookmarks, Settings, and viewers
- 💾 **Local Persistence**: Bookmarks and recent files stored via Room
- ⚙️ **User Preferences**: Theme and layout mode via DataStore
- 🔄 **External Handoff**: Legacy `.doc` files open with external apps for best compatibility
- 🧹 **Clean UI**: Material 3 design with adaptive layouts for phones and tablets

---

## 🛠 Tech Stack
| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Storage | Room (SQLite) + DataStore |
| Async | Kotlin Coroutines + Flow |
| Build | Gradle (Kotlin DSL) |
| Min SDK | 26 (Android 8.0+) |
| Target SDK | 35 |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- JDK 17+
- Android SDK 35+

### Build from Source
```bash
git clone https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-.git
cd AllVie-file-viewer-Android-
# Open in Android Studio → Sync Gradle → Run
```

### Project Structure
```
📦 AllVie
 ┣ 📂 app/          → Main application module
 ┣ 📂 gradle/       → Build configuration
 ┣ 📜 build.gradle.kts
 ┣ 📜 settings.gradle.kts
 ┗ 📜 README.md
```

---

## 🗺️ Roadmap
## Roadmap
- [ ] Search within documents
- [ ] Widget and quick-access shortcuts
- [ ] More advanced Office layout fidelity

> ⚠️ **Note**: DOCX preview is experimental in v3.0. Legacy `.doc` files are intentionally not rendered in-app.

---

## 🤝 Contributing
1. Fork the repo
2. Create a feature branch: `git checkout -b feat/your-change`
3. Commit: `git commit -m 'feat: describe change'`
4. Push & open a PR

Please follow Kotlin coding conventions and test on Android 10+ devices.

---

## 📜 License
MIT License — See [LICENSE](LICENSE) for details.

---

> Built by [Tanmoy Kabasi](https://github.com/Tanmoykabasi) using Kotlin & Jetpack Compose.
