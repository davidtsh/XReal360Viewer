# XREAL 360° Viewer — Android App

A 360° image and video viewer for **XREAL One** and **Nreal Air** glasses, built with Kotlin, OpenGL ES 2.0, and ExoPlayer.

---

## [Download](http://davidalandi.cat/xreal360viewer)

1.0.0  Only photos. Tap to open a new one.

1.1.0  Photos & Videos. Tap to open a new one. Slide up & down to change photos and videos in the same folder. Pinch to zoom. Remember same folder. Tap and hold to speed 5x a video.

1.1.2  Shake to rotate 180º. Català. New design.

1.1.3  Nreal Air axis mapping refinements. Tap now pauses/plays video. Two-finger hold rewinds video ×5. Double tap closes the file.

<video src="https://github.com/user-attachments/assets/0976bb51-27f5-4b62-a7ee-926c3883923a" controls autoplay loop muted></video>


## Features
- 📷 Load any equirectangular 360° **image** (JPG/PNG) from your device gallery
- 🎬 Load any equirectangular 360° **video** (MP4/MKV) with full playback
- 🕶️ **Head tracking** via XREAL One sensors and Nreal Air USB IMU data
- ✋ Touch controls: swipe between files, pinch zoom, hold to fast-forward video, two-finger tap to pause/play video, two-finger hold to rewind video
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
│   │   │   └── HeadTracker.kt          ← XREAL One + Nreal Air tracking
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
- XREAL One or Nreal Air glasses connected via USB-C

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
2. Tap **"Open 360° File"**
3. Pick a file from your gallery
4. Put on your XREAL One or Nreal Air glasses and look around!

### Finding 360° content
- Download sample equirectangular images from https://polyhaven.com (free, CC0)
- Record 360° video with an Insta360 or GoPro MAX camera
- Download from YouTube 360° (use a downloader app)

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Black screen | Make sure the image is equirectangular (2:1 ratio, e.g. 4096×2048) |
| No head tracking | Confirm the glasses are connected over USB-C and accept any Android USB permission prompt |
| Video won't play | Use H.264 MP4 format for best compatibility |
| Gradle sync fails | Update Gradle plugin version in root `build.gradle` |

---

## Dependencies
- [ExoPlayer / Media3](https://developer.android.com/media/media3) — video playback
- [XREAL SDK](https://developer.xreal.com) — glasses head tracking
- OpenGL ES 2.0 — sphere rendering (built into Android)
