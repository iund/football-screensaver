//
//  Snapshot.swift — CI-only: runs the broadcast headless and writes what the layer tree looks like.
//  swiftc -parse-as-library macos/Saver/{Match,Art,Broadcast,HUD}.swift macos/Tools/Snapshot.swift -o snapshot && ./snapshot outdir
//

import AppKit
import Metal
import QuartzCore

@main
enum Snapshot {
    static func png(_ img: CGImage, _ path: String) {
        try? NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
    }

    static func main() {
        let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
        let w = 1280, h = 720, size = CGSize(width: w, height: h), b = Broadcast()
        b.setSize(size)
        let start = Date()
        while !b.ready && Date().timeIntervalSince(start) < 60 { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
        for _ in 0..<240 { b.tick(1.0 / 60); RunLoop.main.run(until: Date().addingTimeInterval(0.002)) }
        CATransaction.flush()
        print(b.debugDump())

        png(bitmap(w, h, flipped: false) { b.root.render(in: $0) }, "\(out)/cpu-render.png")
        if let still = b.still(width: w, height: h) { png(still, "\(out)/still.png") }

        guard let dev = MTLCreateSystemDefaultDevice(), let q = dev.makeCommandQueue() else { print("no Metal device"); return }
        print("Metal device: \(dev.name)")
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
        d.usage = [.renderTarget, .shaderRead, .shaderWrite]; d.storageMode = .shared
        let tex = dev.makeTexture(descriptor: d)!
        let r = CARenderer(mtlTexture: tex, options: [kCARendererMetalCommandQueue: q])
        r.layer = b.root; r.bounds = CGRect(origin: .zero, size: size)
        CATransaction.flush()
        for _ in 0..<2 { r.beginFrame(atTime: CACurrentMediaTime(), timeStamp: nil); r.addUpdate(r.bounds); r.render(); r.endFrame() }
        let cb = q.makeCommandBuffer()!
        cb.commit(); cb.waitUntilCompleted()
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        tex.getBytes(&bytes, bytesPerRow: w * 4, from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)
        let ctx = CGContext(data: &bytes, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue)!
        let raw = ctx.makeImage()!  // texture rows are bottom-up
        png(bitmap(w, h, flipped: true) { $0.draw(raw, in: CGRect(x: 0, y: 0, width: w, height: h)) }, "\(out)/gpu-render.png")
    }
}
