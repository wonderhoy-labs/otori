// Shared between the harness app and its widget extension: ActivityKit matches attributes by type name.
import ActivityKit
import Foundation

struct RefAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var tick: Int
    }
    var name: String
}
