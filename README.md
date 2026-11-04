# Chemistry Lab - Android App

## Setup Instructions for Android Studio

### Step 1: Extract the ZIP
Unzip `ChemistryLab.zip` to any folder on your computer.

### Step 2: Open in Android Studio
1. Open Android Studio
2. Click **File > Open**
3. Navigate to the extracted `ChemistryLab` folder
4. Click **OK**
5. Wait for Gradle sync to finish (may take 2-3 minutes first time)

### Step 3: Fix Gradle Version (if needed)
If Android Studio shows a Gradle version error:
1. Go to **File > Project Structure > Project**
2. Set **Gradle Version** to `8.2`
3. Set **Android Gradle Plugin Version** to `8.2.0`
4. Click **Apply > OK**

### Step 4: Run the App
1. Connect your Android phone via USB (enable USB Debugging in Developer Options)
   - OR use the built-in emulator (AVD Manager > Create Virtual Device)
2. Click the green **Run** button (or Shift+F10)
3. Select your device
4. App will install and launch!

### Step 5: Build APK for sharing
1. Go to **Build > Build Bundle(s) / APK(s) > Build APK(s)**
2. Wait for build to complete
3. Click **locate** in the notification
4. APK will be at: `app/build/outputs/apk/debug/app-debug.apk`
5. Share this APK file with anyone!

---

## Project Structure

```
ChemistryLab/
├── build.gradle              # Root build config
├── settings.gradle           # Project settings
├── gradle.properties         # Gradle settings
├── gradle/wrapper/           # Gradle wrapper
├── README.md                 # This file
└── app/
    ├── build.gradle          # App build config
    ├── proguard-rules.pro    # ProGuard rules
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/chemlab/app/
        │   └── MainActivity.kt    # Main activity with WebView
        ├── assets/
        │   └── index.html         # The chemistry lab app (HTML+React)
        └── res/
            ├── drawable/           # App icon vector
            ├── values/             # Colors, strings, themes
            └── mipmap-*/           # App icons
```

## How It Works

The app uses a **WebView** to load a self-contained HTML file that includes:
- React 18 (loaded from CDN on first launch)
- The complete Chemistry Lab with all features
- Periodic Table (118 elements)
- 30 chemical reactions
- Achievements, sound effects, timed challenges

## Requirements

- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34
- Min Android version: 7.0 (API 24)
- Internet needed for first launch (to load React from CDN)

## Making It Work Offline

To make the app work 100% offline, download these files and put them in the `assets` folder:
1. `react.production.min.js` from https://unpkg.com/react@18/umd/react.production.min.js
2. `react-dom.production.min.js` from https://unpkg.com/react-dom@18/umd/react-dom.production.min.js
3. `babel.min.js` from https://unpkg.com/@babel/standalone@7/babel.min.js

Then update `index.html` to use local paths:
```html
<script src="react.production.min.js"></script>
<script src="react-dom.production.min.js"></script>
<script src="babel.min.js"></script>
```

## 🚀 Automated Build & Release (CI/CD)

The app is fully configured for **automated building, signing, and releasing** both locally and in the cloud via GitHub Actions.

### 🌐 Method 1: Cloud Automation via GitHub Actions (Zero Local Setup)
Whenever you want to release a new version:
1. **Option A (Automatic via Git Tag)**:
   ```bash
   git tag v2.4
   git push origin v2.4
   ```
   GitHub Actions will automatically build signed `.apk` and `.aab` files, create an official GitHub Release, and attach the download links!

2. **Option B (1-Click UI via GitHub)**:
   - Go to your repository on GitHub: `https://github.com/ssilu07/chemistryLab`
   - Click the **Actions** tab.
   - Select **Build & Release Android App** on the left.
   - Click **Run workflow** -> **Run workflow**.

3. **Setting Up Production Signing Secrets (1-Minute Setup)**:
   Run the helper script on your PC:
   ```powershell
   .\scripts\export-keystore-secret.ps1
   ```
   This will automatically copy your keystore to your clipboard! Then:
   - Go to [GitHub Repository Secrets](https://github.com/ssilu07/chemistryLab/settings/secrets/actions)
   - Add:
     - `KEYSTORE_BASE64`: Paste your clipboard (Ctrl+V)
     - `KEYSTORE_PASSWORD`: `8750257510`
     - `KEY_ALIAS`: `key0`
     - `KEY_PASSWORD`: `8750257510`

---

### 💻 Method 2: Local 1-Click Release Script
Run the automated release script on your PC:
- **Windows Double-Click**: Double-click [release.bat](file:///d:/htmlgames/ChemistryLab/release.bat)
- **PowerShell**:
  ```powershell
  # Interactive mode (asks whether to bump version):
  .\scripts\release.ps1

  # Or automatic bump:
  .\scripts\release.ps1 -Bump minor
  ```
**What it does automatically:**
1. Auto-increments `versionCode` and `versionName` in `app/build.gradle`.
2. Compiles and signs both **Release APK** (for direct install) and **Release AAB** (for Play Store).
3. Renames and packages outputs with version number into `release-outputs/v<version>/`.
4. Generates SHA256 integrity checksums.
5. Commits to git, creates a git tag, and pushes to GitHub (which triggers the GitHub Release online).

---

## Features

- 🧪 24 chemicals with real properties
- ⚗️ 30 chemical reactions with equations
- ⚛️ Full periodic table (118 elements)
- 🔥 Bunsen burner toggle
- 🌡️ pH scale, temperature, reaction speed
- 🛡️ Safety warnings
- 💡 Hint system (green glow for compatible items)
- 📋 Experiment log with progress tracking
- 🎯 8 quest challenges
- 🏅 11 achievement badges
- 🔊 Sound effects
- ⏱️ 60-second timed challenge mode
- 📊 Stats and leaderboard

## Troubleshooting

**App shows blank screen:**
- Check internet connection (needed for first CDN load)
- Make sure WebView is enabled on your device

**Gradle sync fails:**
- Update Android Studio to latest version
- Go to File > Invalidate Caches and Restart

**Build fails:**
- Make sure JDK 17+ is installed
- Check SDK Manager has SDK 34 installed
# chemistryLab
