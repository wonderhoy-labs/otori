// Reference harness: plays a real audio track on the iPhone 15 Pro simulator, publishes Now Playing
// metadata and runs a Live Activity, so the system island can be captured and measured.
import ActivityKit
import AVFoundation
import MediaPlayer
import SwiftUI

final class Player: NSObject, ObservableObject {
    static let shared = Player()
    var player: AVAudioPlayer?
    var activity: Activity<RefAttributes>?
    var tick = 0
    let title = "Island Reference Track"
    let artist = "Test Artist"
    let album = "Measurement Sessions"

    func start() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, options: [])
        try? s.setActive(true)
        guard let url = Bundle.main.url(forResource: "track", withExtension: "m4a") else {
            NSLog("IslandRef: no track")
            return
        }
        player = try? AVAudioPlayer(contentsOf: url)
        player?.numberOfLoops = -1
        player?.prepareToPlay()
        player?.play()
        UIApplication.shared.beginReceivingRemoteControlEvents()
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.isEnabled = true
        c.pauseCommand.isEnabled = true
        c.togglePlayPauseCommand.isEnabled = true
        c.nextTrackCommand.isEnabled = true
        c.previousTrackCommand.isEnabled = true
        c.changePlaybackPositionCommand.isEnabled = true
        _ = c.playCommand.addTarget { [weak self] _ in self?.player?.play(); self?.publish(); return .success }
        _ = c.pauseCommand.addTarget { [weak self] _ in self?.player?.pause(); self?.publish(); return .success }
        _ = c.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let p = self?.player else { return .commandFailed }
            if p.isPlaying { p.pause() } else { p.play() }
            self?.publish()
            return .success
        }
        _ = c.nextTrackCommand.addTarget { [weak self] _ in self?.player?.currentTime = 0; self?.publish(); return .success }
        _ = c.previousTrackCommand.addTarget { [weak self] _ in self?.player?.currentTime = 0; self?.publish(); return .success }
        publish()
    }

    func startActivity() {
        guard activity == nil else { return }
        NSLog("IslandRef: activities enabled = \(ActivityAuthorizationInfo().areActivitiesEnabled)")
        do {
            activity = try Activity.request(
                attributes: RefAttributes(name: "ref"),
                content: .init(state: .init(tick: 0), staleDate: nil),
                pushType: nil
            )
            NSLog("IslandRef: activity started \(activity?.id ?? "nil")")
        } catch {
            NSLog("IslandRef: activity failed \(error); retrying")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.startActivity() }
            return
        }
        // Periodic alerting updates make the system auto-expand the island with no touch input,
        // which lets the expand and collapse springs be recorded repeatably.
        Timer.scheduledTimer(withTimeInterval: 16, repeats: true) { [weak self] _ in self?.alert() }
    }

    func alert() {
        guard let activity else { return }
        tick += 1
        let n = tick
        Task {
            await activity.update(
                ActivityContent(state: .init(tick: n), staleDate: nil),
                alertConfiguration: AlertConfiguration(title: "Ref", body: "Update \(n)", sound: .default)
            )
            NSLog("IslandRef: alert \(n)")
        }
    }

    func publish() {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: artist,
            MPMediaItemPropertyAlbumTitle: album,
            MPMediaItemPropertyPlaybackDuration: player?.duration ?? 180,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player?.currentTime ?? 0,
            MPNowPlayingInfoPropertyPlaybackRate: (player?.isPlaying ?? false) ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let path = Bundle.main.path(forResource: "cover", ofType: "jpg"), let img = UIImage(contentsOfFile: path) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = (player?.isPlaying ?? false) ? .playing : .paused
    }
}

struct ContentView: View {
    @Environment(\.scenePhase) private var phase
    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            VStack(spacing: 24) {
                Text("IslandRef").font(.largeTitle).bold()
                Button("Play") { Player.shared.player?.play(); Player.shared.publish() }
                Button("Pause") { Player.shared.player?.pause(); Player.shared.publish() }
            }
        }
        .onAppear { Player.shared.start() }
        .onChange(of: phase) { _, p in if p == .active { Player.shared.startActivity() } }
    }
}

struct RefApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}
RefApp.main()
