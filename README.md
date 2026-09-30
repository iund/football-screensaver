# Football Screensaver

A macOS screensaver showing a simulated football match as a live TV broadcast: score bug, goal banner, slow-motion replay from the goal mouth. Fictional clubs and sponsors.

- Preview: open `mockup.html` in a browser.
- Install: download `Football-v1.0.N.saver.zip` from Releases, unzip, double-click `Football.saver`. It is ad-hoc signed, not notarized: if macOS blocks it, use System Settings → Privacy & Security → Open Anyway, or `xattr -dr com.apple.quarantine Football.saver`.
- Build: `brew install xcodegen && cd macos && xcodegen && xcodebuild -scheme Football -configuration Release build`.
- Release: every push to `main` (or a `v*` tag) builds and publishes the zip via `.github/workflows/build.yml`.
- Design and macOS stability rules: `docs/DESIGN.md`.
