# Football Screensaver — design

A simulated football match shown as a live TV broadcast. `mockup.html` is the reference implementation; the macOS `.saver` ports it 1:1.

## Look
- Night match, floodlit: striped pitch, far stand + two end stands (crowd texture), LED ad boards (panel set rotates every 10 s).
- Broadcast gantry camera on the near side: `C = (0.55·cam.x, −60, 25)`, looks at `(cam.x, cam.y+5, 0)`; smoothed follow of the ball (lead 0.6 s), zooms out on long balls / breaks, follows the scorer on goals.
- Players: side-view sprites (12 run frames with knee flex, heel kick and flight phase; 8 walk frames; idle; cadence scales with speed), mirrored by screen direction, sized by perspective, contact shadow + two faint floodlight shadows. Ball with height and ground shadow. Goals drawn as 3D line sets with nets.
- TV graphics (Barlow Condensed): score bug top-left (codes, kit chips, score, clock, `+N` added time), `LIVE` top-right, `GOAL` lower third, half-time/full-time card. Fictional clubs, names and sponsors only.

## Simulation (fixed 60 Hz step, `step(dt)`)
- Pitch 105×68 m, origin at centre, x along length. Teams 4-4-2 (`FORM`), shape shifts with ball and possession.
- Owner dribbles toward goal avoiding opponents; decides every 0.4–1.6 s: shoot (<28 m), pass (forwardness + space + distance score; lofted if long or lane blocked) or keep dribbling.
- Shot outcome fixed at the kick (goal / save / wide-over); keeper reacts accordingly.
- Nearest defender presses, second covers; tackles and interceptions are probabilistic.
- Goal cutscene: 3.5 s live celebration with `GOAL` lower third, then a `REPLAY` wipe, slow-motion (0.4×) replay of the last 3 s before the goal + 0.7 s after (8 s ring buffer of player/ball state, interpolated), wipe back to live, kickoff. Replay camera: low on the pitch facing the scoring goal, `C = (s·31, −5, 2.4)` looking at `(s·53, ≈ball.y·0.3, 1)`, zoom 1.5 — shows the keeper missing and the ball hitting the net. Only that end's stand/boards plus a 32 px/m crop of that goal area are shown (every other plane would sit behind the lens). `LIVE` tag becomes `REPLAY`.
- Restarts: kickoff, throw-in, goal kick, corner. Match clock is real time: 2 × 45 min + added time, HT/FT card, then a new fixture.

## macOS port (rules from `iund/spreadpoint-clock-screensaver`)
- `ScreenSaverView` subclass, `animationTimeInterval = 1/60`, `animateOneFrame` drives everything. No display link.
- **No Metal / no `CAMetalLayer`** — breaks on second activation in the reused `legacyScreenSaver` host. **No per-frame `CGContext.draw`** (~28% CPU). Use plain `CALayer`s only.
- Planes (pitch, stands, boards): one `CALayer` each, `.contents` = `CGImage` rasterised once per match on a background queue (token-guarded), `anchorPoint = (0,0)`, `transform = CATransform3D` built per frame from the same camera maths as `css()` in the mockup (CA uses row vectors: transpose). Keep every plane corner ≥ 4 m in front of the camera (the clamp in `view()`); CA, like CSS, mishandles w ≤ 0.
- Players/ref/ball: sprite `CALayer`s; per frame set `position`, `bounds`, `contents` (pre-rendered frame `CGImage`), `zPosition` = −depth, `transform` scaleX −1 to mirror. Shadows: pre-rendered ellipse images on their own layers.
- Goals: one `CAShapeLayer` per goal, path rebuilt per frame (≈40 segments).
- Wrap each frame in `CATransaction.setDisableActions(true)`. Never write a whole `position` when only one component should change.
- Replay: record a fixed-size ring buffer (`Float32Array` per step in the mockup → flat `[Float]` in Swift); playback just feeds interpolated snapshots into the same layer update path. Wipe = one layer with a `CABasicAnimation` on `position.x`; cut at its midpoint.
- HUD: layers/`CATextLayer`, updated only when text changes (≤ 1 Hz); goal/card slide via explicit `CABasicAnimation`.
- Only the newest instance animates (`generation` / `liveInstances` gating); `adoptWindowSizeIfNeeded`; fresh `configureSheet` window per request; `cacheDisplay(in:to:)` returns a CPU-rendered still; lifecycle to the unified log.
- Keep the score bug below the menu-bar/notch band (`max(safeAreaInsets.top, NSStatusBar.system.thickness)`) on full-screen instances.
- Test reactivation with real lock/unlock cycles before claiming stability.
