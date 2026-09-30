# Football Screensaver

A macOS screensaver showing a simulated football match as a live TV broadcast: score bug, goal banner, slow-motion replay from the goal mouth. Fictional clubs and sponsors.

- Preview: open `mockup.html` in a browser.
- Install: download `Football.saver.zip` from Releases, unzip, double-click `Football.saver`. If macOS blocks it, right-click → Open (it is ad-hoc signed, not notarized).
- Build: `brew install xcodegen && cd macos && xcodegen && xcodebuild -scheme Football -configuration Release build`.
- Release: push a `v*` tag; `.github/workflows/build.yml` builds and publishes the zip.
- Design and macOS stability rules: `docs/DESIGN.md`.
