# XREAL 360° Viewer — Android App

A 360° image and video viewer for the **XREAL One** glasses, built with Kotlin, OpenGL ES 2.0, and ExoPlayer.

---

## Download

1.0.0 Namek : Only photos. Tap to open a new one.

1.1.0 Vegeta : Photos & Videos. Tap to open a new one. Slide up & down to change photos and videos in the same folder. Pinch to zoom. Remember same folder. Tap and hold to speed 5x a video.


## Features
- 📷 Load any equirectangular 360° **image** (JPG/PNG) from your device gallery
- 🎬 Load any equirectangular 360° **video** (MP4/MKV) with full playback
- 🕶️ **Head tracking** via XREAL One sensors (gyroscope fallback included)
- Smooth OpenGL ES sphere rendering at 60fps

---

## Project Structure

```
XReal360Viewer/
├── app/src/main/
│   ├── java/com/xreal360/viewer/
│   │   ├── gl/
│   │   │   ├── SphereGeometry.kt       ← UV sphere mesh generator
│   │   │   ├── ShaderProgram.kt        ← GLSL shader loader
│   │   │   └── Panorama360Renderer.kt  ← Main OpenGL renderer
│   │   ├── sensors/
│   │   │   └── HeadTracker.kt          ← XREAL SDK + Android gyro
│   │   ├── media/
│   │   │   └── MediaItem.kt
│   │   └── ui/
│   │       ├── MainActivity.kt         ← Gallery picker
│   │       └── ViewerActivity.kt       ← 360° viewer screen
│   └── res/
│       ├── raw/sphere_vertex.glsl
│       └── raw/sphere_fragment.glsl
```

---

## Setup Instructions

### 1. Prerequisites
- [Android Studio Hedgehog](https://developer.android.com/studio) or newer
- Android device running **Android 8.0+** (API 26+)
- XREAL One glasses connected via USB-C

### 2. Open the project
1. Open Android Studio → **File → Open**
2. Select the `XReal360Viewer` folder
3. Wait for Gradle sync to finish

### 3. Add the XREAL SDK (required for glasses head tracking)

**Option A — Maven (if available):**
In `app/build.gradle`, uncomment:
```gradle
implementation 'ai.xreal:xrealsdk:2.2.0'
```
Update the version to match your download from https://developer.xreal.com

**Option B — Local AAR:**
1. Download the XREAL SDK AAR from https://developer.xreal.com/download
2. Copy the `.aar` file to `app/libs/`
3. In `app/build.gradle`, uncomment:
```gradle
implementation fileTree(dir: 'libs', include: ['*.aar'])
```

**Option C — Use gyroscope fallback only (no SDK needed):**
The app works out-of-the-box with the Android rotation vector sensor.
Skip this step and the phone's gyro will be used for head tracking.

### 4. Enable XREAL head tracking in code
Once the SDK is added, open `HeadTracker.kt` and:
- Uncomment the `NRSessionManager` block
- Comment out the `SensorEventListener` fallback block
- Follow the XREAL SDK documentation for session initialisation

### 5. Build & Run
1. Connect your Android device (enable Developer Options + USB Debugging)
2. Select your device in the toolbar
3. Click ▶ **Run**

---

## How to Use
1. Launch the app
2. Tap **"Open 360° Image"** or **"Open 360° Video"**
3. Pick a file from your gallery
4. Put on your XREAL One glasses and look around!

### Finding 360° content
- Download sample equirectangular images from https://polyhaven.com (free, CC0)
- Record 360° video with an Insta360 or GoPro MAX camera
- Download from YouTube 360° (use a downloader app)

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Black screen | Make sure the image is equirectangular (2:1 ratio, e.g. 4096×2048) |
| No head tracking | Check gyroscope permission; add XREAL SDK for glasses tracking |
| Video won't play | Use H.264 MP4 format for best compatibility |
| Gradle sync fails | Update Gradle plugin version in root `build.gradle` |

---

## Dependencies
- [ExoPlayer / Media3](https://developer.android.com/media/media3) — video playback
- [XREAL SDK](https://developer.xreal.com) — glasses head tracking
- OpenGL ES 2.0 — sphere rendering (built into Android)
