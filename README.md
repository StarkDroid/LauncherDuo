# Launcher DUO

A fold effect for the Android home screen. Tilting the phone bends the screen into
the edge you tilt toward: the picture compresses, frosts and darkens, with a thin
black sliver where the glass leaves the panel. The opposite edge is the hinge and
stays pinned and sharp, so there is no seam.

Nothing is replaced. The launcher keeps working underneath; the effect is an
overlay that is only visible while you tilt.

## How it works

1. **Tilt.** `TiltTracker` reads `TYPE_GAME_ROTATION_VECTOR` (fused accelerometer +
   gyroscope) and measures the screen normal against a calibrated centre. Vertical
   tilt is ignored; yaw and in-plane roll are summed, then filtered with a one-euro
   filter so hand tremor does not reach the shader.
2. **Centre.** The effect is off until the phone has been held still (under 15°/s
   for about a second) while upright. That pose becomes `zero`. Holding still again
   re-zeroes it, so there is never a permanent bend.
3. **Frame.** While the home screen is up and flat, the accessibility service takes
   one screenshot roughly twice a second. The shader samples that image. It is
   never taken while the overlay is visible, otherwise the effect would record
   itself.
4. **Warp.** `res/raw/duo_edge.agsl` is an AGSL port of the iPhone DUO fold. Per
   pixel it traces the eye ray through the tilted glass to the panel plane, blurs
   with a Vogel disk whose radius grows with the glass-to-panel gap, dims in
   proportion to that blur, and returns black where the kernel misses the panel.
   The plane sits behind the glass, so content compresses inward.
5. **Draw.** One accessibility overlay window, 30 fps, `FLAG_NOT_TOUCHABLE` — taps
   and swipes still reach the launcher.

The effect exists only on the launcher's home screen, is hidden on the lock screen,
and is cleared the moment another app comes forward.

## Permissions

Only one: an **accessibility service**. It is used for exactly three things.

| Capability | Why |
|---|---|
| Draw overlays (`TYPE_ACCESSIBILITY_OVERLAY`) | to paint the fold above the launcher |
| Take screenshots (`canTakeScreenshot`) | one still frame of the home screen, twice a second |
| Window state events | to know when the home screen is in front and the lock screen is up |

`canRetrieveWindowContent` is only used to read the foreground *package name*. No
window content, text, keystrokes or notification data is read, stored or
transmitted. Screenshots are held in memory and released as soon as the home screen
leaves the foreground. There is no internet permission.

## Calibration lifecycle

1. Flipping the switch **in the app** does not calibrate anything. It arms the
   service and sets a "waiting for the home screen" flag; the overlay stays hidden
   even if the sensors move.
2. The gate is decided by the **focused window**, polled every 250 ms
   (`rootInActiveWindow`, falling back to the window list). Window-state events are
   used only as a fast path and deliberately ignore our own package, because the
   overlay window would otherwise toggle the gate and the effect would stutter at
   1 Hz.
3. When the launcher is confirmed in front, calibration starts: a 900 ms window
   that must see at least 8 samples with the yaw staying inside a 3° spread. If the
   phone is still moving, the window restarts instead of guessing. A toast reports
   "Recalibrating the fold…" and then "Fold ready".
4. Yaw is only interpreted while the screen is roughly vertical; facing the ceiling
   makes `atan2(x, z)` ill-conditioned and would report a phantom turn.
5. A tap or a launcher content change hides the fold instantly and re-runs steps
   3-4, rate limited to one recalibration per 1.5 s.

## Turning it on

The effect never resumes by itself after a reboot or an accessibility rebind — the
switch has to be flipped in the app. That is deliberate: enabling it starts a fresh
calibration of how the phone is being held, so the fold is measured against the
pose you are actually using rather than one captured days ago.

## Build

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 37). No root, no Shizuku, no ADB
after install.

## Limits

The frame is a still image captured while flat, so animations at the edge freeze
while the effect is visible. There are no live previews of other apps because
task snapshots are not readable without root.

## Licence

MIT — see [LICENSE](LICENSE).
