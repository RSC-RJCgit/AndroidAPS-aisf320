import ActivityKit
import Foundation

/// What the Live Activity shows (2026-10-24, per explicit request).
///
/// Built into both the app, which starts and updates the activity, and the LiveActivity extension, which draws it. That is
/// why it is a file of its own: the extension does not link the Kotlin framework, so this type is the whole contract
/// between them. All values arrive as finished text from `IosLiveActivityUpdater`, so the widget does no formatting.
struct AapsActivityAttributes: ActivityAttributes {

    struct ContentState: Codable, Hashable {
        /// Glucose in the user's units, for example "7.5"
        var bg: String
        /// Trend arrow symbol, or empty
        var arrow: String
        /// Signed change over 5 minutes, for example "-0.2"
        var delta: String
        /// Insulin on board with its unit, for example "0.65 U"
        var iob: String
        /// Carbs on board with its unit, for example "12 g"
        var cob: String
        /// Time of the glucose value
        var bgDate: Date
        /// -1 below the low line, 1 above the high line, 0 in between
        var range: Int
    }

    /// The app's display name, so AAPSClient and AAPSClient2 can be told apart when both run
    var appName: String
}
