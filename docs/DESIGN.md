# Football Screensaver — design

A simulated football match shown as a live TV broadcast. `mockup.html` is the reference implementation; the macOS `.saver` ports it 1:1.

## Look
- Night match, floodlit: striped pitch, far stand + two end stands (crowd texture), LED ad boards (panel set rotates every 10 s).
- Broadcast gantry camera on the near side: `C = (0.55·cam.x, −60, 25)`, looks at `(cam.x, cam.y+5, 0)`; smoothed follow of the ball (lead 0.6 s), zooms out on long balls / breaks, follows the scorer on goals.
- Players: side-view sprites (8 run frames + idle), mirrored by screen direction, sized by perspective, contact shadow + two faint floodlight shadows. Ball with height and ground shadow. Goals drawn as 3D line sets with nets.
- TV graphics (Barlow Condensed): score bug top-left (codes, kit chips, score, clock, `+N` added time), `LIVE` top-right, `GOAL` lower third, half-time/full-time card. Fictional clubs, names and sponsors only.

## Simulation (fixed 60 Hz step, `step(dt)`)
- Pitch 105×68 m, origin at centre, x along length. Teams 4-4-2 (`FORM`), shape shifts with ball and possession.
- Owner dribbles toward goal avoiding opponents; decides every 0.4–1.6 s: shoot (<28 m), pass (forwardness + space + distance score; lofted if long or lane blocked) or keep dribbling.
- Shot outcome fixed at the kick (goal / save / wide-over); keeper reacts accordingly.
- Nearest defender presses, second covers; tackles and interceptions are probabilistic.
- Restarts: kickoff, throw-in, goal kick, corner. Match clock is real time: 2 × 45 min + added time, HT/FT card, then a new fixture.

## macOS port (rules from `iund/spreadpoint-clock-screensaver`)
- `ScreenSaverView` subclass, `animationTimeInterval = 1/60`, `animateOneFrame` drives everything. No display link.
- **No Metal / no `CAMetalLayer`** — breaks on second activation in the reused `legacyScreenSaver` host. **No per-frame `CGContext.draw`** (~28% CPU). Use plain `CALayer`s only.
- Planes (pitch, stands, boards): one `CALayer` each, `.contents` = `CGImage` rasterised once per match on a background queue (token-guarded), `anchorPoint = (0,0)`, `transform = CATransform3D` built per frame from the same camera maths as `css()` in the mockup (CA uses row vectors: transpose). Keep every plane corner ≥ 4 m in front of the camera (the clamp in `view()`); CA, like CSS, mishandles w ≤ 0.
- Players/ref/ball: sprite `CALayer`s; per frame set `position`, `bounds`, `contents` (pre-rendered frame `CGImage`), `zPosition` = −depth, `transform` scaleX −1 to mirror. Shadows: pre-rendered ellipse images on their own layers.
- Goals: one `CAShapeLayer` per goal, path rebuilt per frame (≈40 segments).
- Wrap each frame in `CATransaction.setDisableActions(true)`. Never write a whole `position` when only one component should change.
- HUD: layers/`CATextLayer`, updated only when text changes (≤ 1 Hz); goal/card slide via explicit `CABasicAnimation`.
- Only the newest instance animates (`generation` / `liveInstances` gating); `adoptWindowSizeIfNeeded`; fresh `configureSheet` window per request; `cacheDisplay(in:to:)` returns a CPU-rendered still; lifecycle to the unified log.
- Keep the score bug below the menu-bar/notch band (`max(safeAreaInsets.top, NSStatusBar.system.thickness)`) on full-screen instances.
- Test reactivation with real lock/unlock cycles before claiming stability.
