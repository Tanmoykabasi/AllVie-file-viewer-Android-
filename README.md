# AllVie 📁

> **Offline Android File Viewer** — View PDF, TXT, DOC/DOCX, XLS/XLSX, and PPT/PPTX in one clean, privacy-focused app.

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android)
![Language](https://img.shields.io/badge/Language-Kotlin-0095D5?logo=kotlin)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![License](https://img.shields.io/badge/License-MIT-yellow)

[![Latest Release](https://img.shields.io/github/v/release/Tanmoykabasi/AllVie-file-viewer-Android-?label=Latest)](https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-/releases)

---

## 📦 Download
Get the latest APK from [Releases](https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-/releases):
- **v0.2** (May 2026) — [app-debug.apk](https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-/releases) (~54 MB)

---

## ✨ Features (v0.2)
- 📄 **Native Viewers**: In-app rendering for **PDF**, **Images**, and **Plain Text**
- 🎞️ **PPT/PPTX Preview**: Vertical Google Slides-style presentation view *(Experimental)*
- 🌓 **Dark Mode**: Improved readability across Files, Recents, Bookmarks, Settings & viewers
- 📑 **PDF Controls**: Page indicator + right-side scroll for smoother navigation
- 💾 **Local Persistence**: Bookmarks & recent files stored via Room
- ⚙️ **User Preferences**: Theme, layout mode, and storage root via DataStore
- 🔄 **Office Handoff**: Securely opens DOC/XLS/PPT via installed apps when native preview isn't available
- 🧹 **Clean UI**: Material 3 design with adaptive layouts for phones & tablets

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
| Min SDK | 24 (Android 7.0+) |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17+
- Android SDK 34+

### Build from Source
```bash
git clone https://github.com/Tanmoykabasi/AllVie-file-viewer-Android-.git
cd AllVie-file-viewer-Android-
# Open in Android Studio → Sync Gradle → Run
```

### Project Structure
