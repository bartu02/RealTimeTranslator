# RealTimeTranslator

A live camera translator for Android, in the spirit of Google Lens: point the
phone at German text and the English translation is painted over the words in
place, staying attached to them as the camera moves.

Everything runs on the device. There is no network call in the working path.

---

## How it works

The central idea is that **recognition rate and overlay rate are separate**.

Text recognition and translation are expensive, so they run a few times per
second. Redrawing the overlay is cheap, so it runs at display rate. In between
recognizer passes, the overlay boxes are carried forward by tracking how the
scene itself moved. That is what makes the translation appear glued to the
surface rather than jumping every time the recognizer catches up.

```
CameraX ImageAnalysis (YUV_420_888, 1280x720, KEEP_ONLY_LATEST)
   │
   ├─ every frame ─────────────────────────────────────────────┐
   │    luminance plane → 2x2 box downsample → 320px upright   │
   │    grayscale → Shi-Tomasi corners → Lucas-Kanade optical  │
   │    flow → RANSAC similarity transform                     │
   │         └── existing overlay boxes move with the scene ───┤
   │                                                            │
   └─ every ~250 ms ───────────────────────────────────────────┤
        YUV → upright RGB → crop to the selection box           │
          → ML Kit text recognition                             │
          → oriented line boxes, reading order, same-line merge  │
          → drop recognizer noise (TextHeuristics)               │
          → match against the blocks already on screen           │
              ├ same words, same place → keep the translation    │
              └ new or changed → translate (LRU cached)          │
          → estimate ink and paper colour per line               │
          → apply motion accumulated since capture ─────────────┤
                                                                 │
                                            StateFlow<OverlayState>
                                                                 │
                                              Compose draw pass ─┘
```

### Why some of it looks the way it does

**Motion is a similarity transform, stored as a complex number.** Frame-to-frame
camera motion over a roughly flat surface is well described by rotation, uniform
scale and translation. Storing it as `(a + bi, tx, ty)` makes composition a
complex multiply, which matters because motion has to be *accumulated* while a
recognizer pass is in flight. When results come back they are nudged forward by
everything that happened since the frame was captured, so boxes do not snap
backwards on every refresh.

**Both colours are estimated, not chosen.** A block's region is split at its
Otsu threshold into two populations: the larger one is the surface, the smaller
one is the ink, because glyphs cover less area than what they sit on. The
translation is then painted in the original ink colour on the original surface
colour. A single averaged colour cannot express this — averaging black text on
white paper gives grey — and writing in the real ink colour is most of what makes
a replaced word look printed rather than pasted.

**Colour is sampled per line, and fills are rotated.** A block that runs from a
white panel onto a coloured band has no single background, so each line carries
the colour sampled behind itself. The fills are the lines' own rotated
rectangles rather than the axis-aligned box around the block, which for slanted
text is far larger than the text itself. Those two things together are what stop
the overlay from reading as a rectangle stuck onto the scene.

**Text is drawn at the size and slant of what it replaces.** The layout starts
from the height of the original line and only shrinks if the translation does not
fit, and it is rotated to the median line angle.

**A recognizer pass amends the overlay instead of rebuilding it.** Readings are
matched to the blocks already on screen by box overlap and text similarity. Text
that has not changed keeps the translation it already has, so the words on screen
stop rewriting themselves several times a second. Blocks the recognizer misses
survive a few passes before being dropped, because it loses a block for a frame
or two constantly and removing them immediately makes the overlay blink.

**Recognition is cropped to the selection box.** The user's box is mapped back
into image space and only that region is recognized, rather than recognizing the
whole frame and discarding most of it.

---

## Layout

```
app/src/main/java/com/example/realtimetranslator/
├── TranslatorApp.kt          Application; loads the OpenCV native library
├── TranslatorViewModel.kt    The pipeline: owns cadence, state and lifetimes
├── MainActivity.kt           Activity, splash gate, camera permission gate
├── model/                    Overlay data types, shared enums
├── core/
│   ├── ViewportMapper.kt     Image space ↔ view space for FIT_CENTER preview
│   ├── TextHeuristics.kt     Noise filter, normalisation, edit distance
│   └── TaskExtensions.kt     Play Services Task → coroutine, no extra dependency
├── camera/
│   ├── FrameConverter.kt     Reusable buffers; luminance plane → tracking image
│   └── FrameSource.kt        Upright dimensions and the RGB frame for recognition
├── tracking/
│   ├── Transform.kt          Similarity transform, composition, rescaling
│   └── RegionTracker.kt      Optical flow, allocation-free in the steady state
├── ocr/
│   ├── BlockExtractor.kt     Recognizer output → ordered, merged, oriented blocks
│   ├── BlockMatcher.kt       Is this reading the block already on screen?
│   └── ImageNormalization.kt Deskew / perspective helpers (not wired up, see below)
├── translate/
│   ├── TranslationRepository.kt  On-device translation behind a bounded LRU cache
│   └── OnlineTranslator.kt       DeepL path, not wired up
├── render/
│   ├── StyleEstimator.kt     Otsu split into surface colour and ink colour
│   └── TextLayoutCache.kt    Preferred-size-first font fitting, memoised
└── ui/
    ├── CameraScreen.kt       Camera binding, selection state, layout
    ├── TranslationOverlay.kt Draws the translated blocks
    ├── SelectionOverlay.kt   The draggable, resizable selection box
    ├── ModePicker.kt
    └── SplashScreen.kt
```

Composables render; they do not own pipeline state. Everything that used to live
in the screen — recognizer, translator, caches, timing, stability tracking — now
lives in `TranslatorViewModel` and reaches the UI as a single `StateFlow`.

---

## Building

Requires JDK 17 and an Android SDK with API 36.

### The OpenCV module is not in this repository

`settings.gradle.kts` includes `:opencv`, but that directory is **not tracked by
git** — it is a local copy of the OpenCV Android SDK (~1.4 GB). A fresh clone
will not configure until you supply it:

1. Download the OpenCV Android SDK (this project is built against **4.12.0**)
   from <https://opencv.org/releases/>.
2. Copy its `sdk` directory into the project root as `opencv/`, so that
   `opencv/build.gradle`, `opencv/java/` and `opencv/native/` exist.

Then:

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:testDebugUnitTest # unit tests
```

Debug builds are restricted to `arm64-v8a` and `x86_64`. OpenCV and ML Kit each
ship a native library per ABI, and the emulator-only ones accounted for more than
half the APK. Release builds keep every ABI, so device support is unchanged.

For distribution prefer `./gradlew :app:bundleRelease` — an App Bundle lets the
store deliver one ABI per device instead of all four.

---

## Tuning

The knobs that matter are constants in `TranslatorViewModel`:

| Constant | Default | Effect |
|---|---|---|
| `RECOGNITION_INTERVAL_MS` | `250` | How often a frame goes through recognition and translation. Lower is more responsive and more expensive. |
| `TRACKING_LONG_SIDE` | `320` | Resolution of the tracking image. Lower is cheaper and less accurate. |
| `MAX_MISSED_PASSES` | `4` | How many consecutive passes a block survives without being found again. Higher is steadier but keeps stale text longer. |

Matching thresholds are in `BlockMatcher`: `MIN_OVERLAP` decides how far a block
may drift and still be considered the same one, and `MIN_TEXT_SIMILARITY` how
much the recognizer may wobble before a re-translation is triggered.

Tracking behaviour is in `RegionTracker`: `MAX_POINTS`, `MIN_POINTS`,
`REDETECT_INTERVAL`, and the `MIN_SCALE`/`MAX_SCALE` sanity bounds that reject
implausible motion estimates.

Appearance is in `TranslationOverlay`: `FILL_PADDING_RATIO` is how far a fill
overshoots the words it covers, and `CAP_HEIGHT_RATIO` the fraction of the
original line height the translation is drawn at.

### Performance notes

The per-frame path is written to allocate nothing once it is warm.
`FrameConverter` reuses every buffer and reads about half the luminance plane by
sampling at a stride with 2x2 averaging, which also removes the separate resize.
`RegionTracker` moves point data through primitive arrays instead of `Point`
objects and copies the reference frame into an existing `Mat` rather than cloning
it. At 30 frames per second the object churn was otherwise heavy enough to cause
collection pauses.

On the UI side, `TranslationOverlay` receives a `State<OverlayState>` and reads
it inside its draw lambda. Boxes move on every camera frame, and reading that
state during composition instead would recompose the whole screen thirty times a
second.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Unit tests cover the logic that has no Android dependencies: the recognizer noise
filter, normalisation and edit distance in `TextHeuristics`; the composition,
rescaling and oriented-box mapping of `Transform`; and the overlap measure in
`BlockMatcher`, which is written against plain coordinates for exactly this
reason. Code that works on `Bitmap`, or in `RectF` directly, is not covered here
— those are stubs in local unit tests and would need Robolectric or an
instrumented test to be meaningful.

---

## Known limitations

- **Preview and analysis resolutions are assumed to match.** Both use the same
  `ResolutionSelector`, and the selection box is mapped through the preview's
  `FIT_CENTER` geometry. If a device hands the two use cases different aspect
  ratios the crop will be offset. The robust fix is `CoordinateTransform` from
  `camera-view`.
- **German to English only.** The language pair is fixed in
  `TranslationRepository`, and the vowel check in `TextHeuristics` assumes a
  Latin script.
- **Online mode is inert.** `OnlineTranslator` exists and takes an API key, but
  nothing calls it and the mode picker shows it disabled.
- **`ImageNormalization` is not in the pipeline.** Its `deskew` and
  `perspectiveCorrect` are plausibly useful and worth an A/B test. Its
  `preprocess` hard-binarises the image, which generally *hurts* ML Kit — that
  recognizer is trained on natural images, not on thresholded scans.
- **The recognition frame is converted at full resolution.** Cropping in raw
  sensor space before rotating would avoid a full-frame rotation and one
  large bitmap allocation per pass, at the cost of an inverse rotation mapping
  that is easy to get subtly wrong.

---

## Third-party

- [CameraX](https://developer.android.com/training/camerax) — capture and analysis
- [ML Kit](https://developers.google.com/ml-kit) — on-device text recognition and translation
- [OpenCV](https://opencv.org/) 4.12.0 — optical flow and motion estimation
- [OkHttp](https://square.github.io/okhttp/) — used only by the inert online path
