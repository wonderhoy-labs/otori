// Live Activity harness. The layout mirrors the now-playing presentation so the system's real
// compact/expanded containers, sizes and springs can be captured from the simulator.
import ActivityKit
import SwiftUI
import UIKit
import WidgetKit

private func cover() -> Image {
    if let p = Bundle.main.path(forResource: "cover_small", ofType: "jpg"), let i = UIImage(contentsOfFile: p) {
        return Image(uiImage: i)
    }
    return Image(systemName: "music.note")
}

private struct Bars: View {
    var height: CGFloat
    var body: some View {
        HStack(spacing: 2) {
            ForEach([0.5, 0.9, 0.65, 1.0, 0.45, 0.8], id: \.self) { h in
                Capsule().fill(Color(red: 1, green: 0.45, blue: 0.35)).frame(width: 2.5, height: height * h)
            }
        }
    }
}

struct RefLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RefAttributes.self) { _ in
            HStack { cover().resizable().frame(width: 44, height: 44); Text("Island Reference Track") }.padding()
        } dynamicIsland: { _ in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    cover().resizable().aspectRatio(contentMode: .fill)
                        .frame(width: 58, height: 58)
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Island Reference Track").font(.system(size: 15, weight: .semibold)).lineLimit(1)
                        Text("Test Artist").font(.system(size: 15)).foregroundStyle(.secondary).lineLimit(1)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.trailing) { Bars(height: 22) }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 10) {
                        HStack(spacing: 8) {
                            Text("0:42").font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
                            Capsule().fill(.white.opacity(0.25)).frame(height: 6)
                            Text("-2:18").font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
                        }
                        HStack(spacing: 44) {
                            Image(systemName: "backward.fill").font(.system(size: 26))
                            Image(systemName: "pause.fill").font(.system(size: 34))
                            Image(systemName: "forward.fill").font(.system(size: 26))
                        }
                    }
                }
            } compactLeading: {
                cover().resizable().aspectRatio(contentMode: .fill)
                    .frame(width: 23, height: 23)
                    .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            } compactTrailing: {
                Bars(height: 14)
            } minimal: {
                Bars(height: 12)
            }
            .keylineTint(.clear)
        }
    }
}

@main
struct RefWidgets: WidgetBundle {
    var body: some Widget { RefLiveActivity() }
}
