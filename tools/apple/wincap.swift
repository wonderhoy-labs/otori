// Records one on-screen window to H.264 with ScreenCaptureKit, so island motion on the simulator can be
// measured frame by frame. Usage: wincap "<window title>" <seconds> <out.mp4> [--png <out.png>]
import AppKit
import AVFoundation
import CoreMedia
import Foundation
import ScreenCaptureKit

final class Recorder: NSObject, SCStreamOutput {
    let writer: AVAssetWriter
    let input: AVAssetWriterInput
    let adaptor: AVAssetWriterInputPixelBufferAdaptor
    var started = false
    var frames = 0

    init(url: URL, width: Int, height: Int) throws {
        try? FileManager.default.removeItem(at: url)
        writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 60_000_000],
        ])
        input.expectsMediaDataInRealTime = true
        adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: nil)
        writer.add(input)
        writer.startWriting()
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, CMSampleBufferIsValid(sb), let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        // ScreenCaptureKit also delivers idle status frames; only complete frames carry new pixels.
        if let att = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
           let raw = att.first?[.status] as? Int, SCFrameStatus(rawValue: raw) != .complete { return }
        let t = CMSampleBufferGetPresentationTimeStamp(sb)
        if !started {
            writer.startSession(atSourceTime: t)
            started = true
        }
        if input.isReadyForMoreMediaData {
            adaptor.append(pb, withPresentationTime: t)
            frames += 1
        }
    }
}

_ = NSApplication.shared
let args = CommandLine.arguments
guard args.count >= 4, let seconds = Double(args[2]) else {
    print("usage: wincap <title> <seconds> <out.mp4>")
    exit(2)
}
let title = args[1]
let out = URL(fileURLWithPath: args[3])

Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let win = content.windows.first(where: { $0.title == title }) else {
            print("window not found: \(title); have: \(content.windows.compactMap { $0.title }.filter { !$0.isEmpty }.prefix(20))")
            exit(3)
        }
        let cfg = SCStreamConfiguration()
        let scale = 2
        cfg.width = Int(win.frame.width) * scale
        cfg.height = Int(win.frame.height) * scale
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: 120)
        cfg.pixelFormat = kCVPixelFormatType_32BGRA
        cfg.showsCursor = false
        cfg.queueDepth = 8
        let rec = try Recorder(url: out, width: cfg.width, height: cfg.height)
        let stream = SCStream(filter: SCContentFilter(desktopIndependentWindow: win), configuration: cfg, delegate: nil)
        try stream.addStreamOutput(rec, type: .screen, sampleHandlerQueue: DispatchQueue(label: "cap"))
        try await stream.startCapture()
        print("recording \(cfg.width)x\(cfg.height) for \(seconds)s")
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        try await stream.stopCapture()
        rec.input.markAsFinished()
        await rec.writer.finishWriting()
        print("frames \(rec.frames) -> \(out.path) status \(rec.writer.status.rawValue) \(rec.writer.error?.localizedDescription ?? "")")
        exit(0)
    } catch {
        print("error \(error)")
        exit(1)
    }
}
RunLoop.main.run()
