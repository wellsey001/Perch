<h1 align="center">Perch</h1>
<p align="center"><i>A glassy, iOS-style home screen for Android.</i></p>

<p align="center">
  <a href="https://github.com/wellsey001/Perch/releases/latest"><img src="https://img.shields.io/github/v/release/wellsey001/Perch?label=latest&color=7c3aed" alt="Latest release"></a>
  <a href="https://github.com/wellsey001/Perch/actions/workflows/build.yml"><img src="https://github.com/wellsey001/Perch/actions/workflows/build.yml/badge.svg" alt="Build APK"></a>
  <img src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+">
  <img src="https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/dependencies-0-blue" alt="Zero dependencies">
</p>

---

## ✨ What it does

| | |
|---|---|
| 🧊 **Frosted glass** | Widgets, dock, search pill, app drawer and menus are frosted panels over your real wallpaper, with a bright glass edge. |
| 🕰️ **Home** | Big clock and date, weather and next-event widgets, a 4-column app grid with pages, page dots, a search pill and a 4-app dock. |
| 🗂️ **App drawer** | Swipe up (or tap Search): the home screen shrinks and blurs as a glass sheet slides up with search, suggested apps and an A–Z grid. |
| 📌 **Long-press menu** | The background blurs, the icon lifts, and a glass menu shows the app's shortcuts plus Edit home screen, Hide, App info and Uninstall. |
| ✏️ **Edit mode** | Icons jiggle; drag them around, into the dock, or to the screen edge for another page. Tap Done (or empty space) to finish. |
| 🌀 **Motion** | Icons spring in, launches zoom from the icon, sheets glide. Turn on *Remove animations* in Android's accessibility settings and Perch keeps still. |
| ⚙️ **Settings** | Wallpaper, grid size, switches for the search bar, widgets, glass and haptics, gestures, hidden apps, and set-as-default. |
| 📁 **Folders** | Hold an app over another for a moment and drop it to make a folder. Tap to open a glass panel; rename it, drag apps out. |
| 🧩 **Real widgets** | Swipe right on the first page (or long-press empty space › Widgets) for a panel of any app's Android widgets, in glass frames. Long-press one to resize, move or remove it. |
| 🔴 **Notification dots** | Counts on icons and folders (needs notification access; Perch never reads what they say). |
| 🎵 **At a Glance** | Under the date: what's playing, charging status, or your next alarm. Tap to jump there. |
| 🎨 **Themed icons** | Optional one-colour icons tinted to match your wallpaper. Glass gets darker over bright wallpapers and at night. |
| 🔎 **Search everything** | Apps, app shortcuts, contacts, settings pages and quick sums (`12*4`, `15% of 80`), plus web search. |
| 🔤 **Drawer extras** | A–Z scroller, a Work tab for work-profile apps, and a "New" row for apps installed in the last few days. |
| ✌️ **More gestures** | Pinch to edit, two-finger swipe down for quick settings, optional swipe up on an app for its shortcuts. |
| ⚡ **Instant start** | Keeps a snapshot of your apps and icons, so the home screen appears at once after a restart. |
| 💾 **Backup** | Save your layout and settings to a file and restore them later or on a new phone. |
| 🪶 **Tiny** | Pure Kotlin, zero third-party libraries. |

## 📲 Install

1. On your phone, download **[Perch.apk](https://github.com/wellsey001/Perch/releases/latest/download/Perch.apk)**. This link always points to the newest version.
2. Open the file to install it. Allow your browser to install apps if Android asks.
3. Press Home and choose **Perch**, or tap **Set as default** on the card Perch shows. (Also in the **Perch** app in your app list.)

Every push to `main` builds a new APK with a fixed signing key, so updates install straight over the old version.

## 🧊 About the glass

Perch frosts the wallpaper itself, so the glass works on any phone running Android 12 or later. It needs to be able to see the wallpaper image:

- **Perch wallpapers** (Aurora, Sunset, Ocean) and **photos you pick in Perch settings** work straight away.
- **Your existing wallpaper**: Android 13+ only lets apps read it with *All files access*. Perch settings › *Frost my current wallpaper* walks you through it. Perch only reads the wallpaper.
- **Live wallpapers** can't be frosted; the panels fall back to tinted glass.

## 👆 Gestures

| Do this | To get this |
|---|---|
| Swipe up | App drawer (or search, or nothing) |
| Swipe down | Notifications (or search, or nothing) |
| Double-tap empty space | Lock the screen (needs Perch's accessibility service; Perch tells you how) |
| Long-press empty space | Edit home screen, wallpaper, settings |
| Long-press an app, then drag | Move it straight away |
| Tap the clock / date | Alarms / calendar |

## 🔒 Permissions

| Permission | Why |
|---|---|
| Internet, approximate location | Weather from [Open-Meteo](https://open-meteo.com) (asked when you tap the widget) |
| Calendar | Your next event (asked when you tap the widget) |
| Set wallpaper | Perch's own wallpapers |
| All files access (optional) | Read your current wallpaper so the glass can frost it |
| Expand status bar | Swipe down for notifications / quick settings |
| Contacts (optional) | Contacts in search |
| Notification access (optional) | Notification dots, and music in At a Glance |
| Request uninstall | Uninstall from the long-press menu |

## 🛠️ Build it yourself

```sh
gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.
