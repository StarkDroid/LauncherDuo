# The mark

The logo is the effect drawn as a letter: a **D** whose hinge edge stays pinned and
sharp, whose glass leaves the panel at a thin sliver, and whose far side is
foreshortened — the same three things the shader does to the home screen.

- **Hinge stem** — solid, near-white, the edge that never bends.
- **Sliver** — the gap where the glass leaves the panel.
- **Bent pane** — the bowl, compressed at its far edge, in glass cyan.

## Palette

| Name | Hex | Use |
|---|---|---|
| Ink | `#0B1220` | tile background, symbol on light |
| Hinge | `#F4F7FB` | the pinned edge, type on dark |
| Glass | `#7CE8FF` | the bending pane, accents on dark |
| Slate | `#A8B6CC` | secondary type on dark |

The one-colour symbol is `#0B1220` on light and `#F4F7FB` on dark. The icon is the
only two-tone lockup; everything else should collapse to one colour.

## Clear space and minimum size

- Clear space: the width of the sliver (20/256 of the mark) on every side.
- Minimum size: **16 px** for the one-colour symbol, **24 px** for the two-tone icon.
- Do not rotate, outline, add a gradient or a shadow, or close the sliver. The gap is
  the idea.

## Files

| File | What |
|---|---|
| `launcher-duo-symbol.svg` | one-colour symbol, ink |
| `launcher-duo-symbol-white.svg` | one-colour symbol, reversed |
| `launcher-duo-icon.svg` | app icon, two-tone on ink |
| `launcher-duo-banner.svg` | README / social banner |
| `*-512.png`, `*-192.png` | raster exports |

The Android launcher icon lives in `app/src/main/res/drawable/ic_launcher_foreground.xml`
(adaptive foreground) and `ic_launcher_monochrome.xml` (themed icons).

*The mark is original. Launcher DUO is an unofficial, for-fun experiment and is not
affiliated with Apple.*
