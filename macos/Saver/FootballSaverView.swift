//
//  FootballSaverView.swift
//  Football Screensaver
//
//  The ScreenSaverView entry point. Lifecycle rules carried over from
//  iund/spreadpoint-clock-screensaver (see docs/DESIGN.md): CALayers only,
//  only the newest instance in the (reused) host process animates, adopt the
//  window size if the host never sends one, and hand cacheDisplay a CPU still.
//

import Cocoa
import ScreenSaver
import os

/// Read with: log show --last 5m --predicate 'subsystem == "com.iund.footballsaver"'
let saverLog = Logger(subsystem: "com.iund.footballsaver", category: "lifecycle")

@objc(FootballSaverView)
final class FootballSaverView: ScreenSaverView {

    private var broadcast: Broadcast!
    private var animationStopped = false
    private var lastTime: CFTimeInterval = 0

    /// WallpaperAgent keeps superseded views alive (and animating) alongside new ones; only the newest may draw.
    private static let liveInstances = NSHashTable<FootballSaverView>.weakObjects()
    private static var latestGeneration: UInt64 = 0
    private var generation: UInt64 = 0

    override init?(frame: NSRect, isPreview: Bool) {
        super.init(frame: frame, isPreview: isPreview)
        commonInit()
    }

    required init?(coder decoder: NSCoder) {
        super.init(coder: decoder)
        commonInit()
    }

    private func commonInit() {
        FootballSaverView.latestGeneration += 1
        generation = FootballSaverView.latestGeneration
        FootballSaverView.liveInstances.add(self)
        saverLog.notice("init frame=\(NSStringFromRect(self.frame), privacy: .public) isPreview=\(self.isPreview, privacy: .public) generation=\(self.generation, privacy: .public)")
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        animationTimeInterval = 1.0 / 60.0
        broadcast = Broadcast()
        layer?.addSublayer(broadcast.root)
    }

    deinit {
        FootballSaverView.liveInstances.remove(self)
        saverLog.notice("deinit generation=\(self.generation, privacy: .public)")
    }

    private var hasDrawableSurface: Bool {
        generation == FootballSaverView.latestGeneration
            && !animationStopped && window != nil && bounds.width >= 1 && bounds.height >= 1
    }

    /// Full-screen instances keep the score bug below the menu bar / camera notch band.
    private func updateTopInset() {
        broadcast.backingScale = window?.backingScaleFactor ?? window?.screen?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 1
        guard !isPreview, let screen = window?.screen ?? NSScreen.main else { broadcast.topInset = 0; return }
        broadcast.topInset = max(screen.safeAreaInsets.top, NSStatusBar.system.thickness)
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        updateTopInset()
        adoptWindowSizeIfNeeded()
        syncSurface()
        saverLog.notice("viewDidMoveToWindow window=\(self.window != nil, privacy: .public) frame=\(NSStringFromRect(self.frame), privacy: .public)")
    }

    /// Some hosts attach a window and never size the view; ask the window instead of waiting.
    private func adoptWindowSizeIfNeeded() {
        guard let window, bounds.width < 1 || bounds.height < 1 else { return }
        let size = window.contentView?.bounds.size ?? window.frame.size
        guard size.width >= 1, size.height >= 1 else { return }
        setFrameSize(size)
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        updateTopInset()
    }

    override func startAnimation() {
        super.startAnimation()
        updateTopInset()
        animationStopped = false
        lastTime = 0
        saverLog.notice("startAnimation frames=\(self.broadcast.framesDrawn, privacy: .public)")
    }

    override func stopAnimation() {
        super.stopAnimation()
        animationStopped = true
        saverLog.notice("stopAnimation frames=\(self.broadcast.framesDrawn, privacy: .public)")
    }

    override func animateOneFrame() {
        guard hasDrawableSurface else { return }
        let now = CACurrentMediaTime(), dt = lastTime == 0 ? 1.0 / 60 : min(0.1, now - lastTime)
        lastTime = now
        broadcast.tick(dt)
    }

    /// WallpaperAgent / Quick Look snapshot the view through CoreGraphics, which can't read a live layer tree.
    override func cacheDisplay(in rect: NSRect, to bitmapImageRep: NSBitmapImageRep) {
        let px = convertToBacking(bounds).size
        guard let context = NSGraphicsContext(bitmapImageRep: bitmapImageRep),
              let image = broadcast.still(width: Int(px.width.rounded()), height: Int(px.height.rounded())) else {
            super.cacheDisplay(in: rect, to: bitmapImageRep)
            return
        }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        context.cgContext.draw(image, in: bounds)
        NSGraphicsContext.restoreGraphicsState()
    }

    override func draw(_ rect: NSRect) {
        NSColor.black.setFill()
        rect.fill()
    }

    private func syncSurface() { broadcast.setSize(bounds.size) }

    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        syncSurface()
    }

    override func layout() {
        super.layout()
        syncSurface()
    }

    override func resizeSubviews(withOldSize oldSize: NSSize) {
        super.resizeSubviews(withOldSize: oldSize)
        syncSurface()
    }

    override var hasConfigureSheet: Bool { false }
    override var configureSheet: NSWindow? { nil }
}
