import ActivityKit
import SwiftUI
import WidgetKit

/// The LiveActivity extension (2026-10-24, per explicit request): draws the BG Live Activity on the lock screen, in the
/// Dynamic Island and, from iOS 18, in the Apple Watch Smart Stack and CarPlay (the `.small` family). The same file builds
/// AAPSLiveActivity (inside AAPSClient) and AAPSLiveActivity2 (inside AAPSClient2). It does not link the Kotlin framework:
/// everything it shows arrives as text in `AapsActivityAttributes.ContentState`.
@main
struct AapsLiveActivityBundle: WidgetBundle {

    var body: some Widget {
        AapsLiveActivity()
    }
}

struct AapsLiveActivity: Widget {

    var body: some WidgetConfiguration {
        let config = ActivityConfiguration(for: AapsActivityAttributes.self) { context in
            LockScreenView(context: context)
                .activityBackgroundTint(Color.black.opacity(0.7))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    BgLabel(context: context).font(.title)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing) {
                        Text(context.state.delta)
                        AgeLabel(context: context).font(.caption)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Text("IOB \(context.state.iob)")
                        Spacer()
                        Text("COB \(context.state.cob)")
                    }
                    .font(.callout)
                }
            } compactLeading: {
                BgLabel(context: context)
            } compactTrailing: {
                Text(context.state.delta)
            } minimal: {
                Text(context.state.bg).foregroundColor(bgColor(context))
            }
        }
        // iOS 18 also shows the small family on a paired Apple Watch and in CarPlay
        if #available(iOS 18.0, *) {
            return config.supplementalActivityFamilies([.small])
        } else {
            return config
        }
    }
}

/// Red below the low line, yellow above the high line, green in range; grey once the value is old (see the staleDate the
/// app sets).
private func bgColor(_ context: ActivityViewContext<AapsActivityAttributes>) -> Color {
    if context.isStale { return .gray }
    switch context.state.range {
    case ..<0: return .red
    case 1...: return .yellow
    default: return .green
    }
}

private struct BgLabel: View {

    let context: ActivityViewContext<AapsActivityAttributes>

    var body: some View {
        Text("\(context.state.bg) \(context.state.arrow)")
            .fontWeight(.bold)
            .foregroundColor(bgColor(context))
            .strikethrough(context.isStale)
    }
}

/// How old the glucose value is, counting up by itself
private struct AgeLabel: View {

    let context: ActivityViewContext<AapsActivityAttributes>

    var body: some View {
        Text(context.state.bgDate, style: .relative)
            .foregroundColor(context.isStale ? .red : .secondary)
    }
}

private struct LockScreenView: View {

    let context: ActivityViewContext<AapsActivityAttributes>

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                BgLabel(context: context).font(.system(size: 34))
                HStack(spacing: 6) {
                    Text(context.state.delta)
                    AgeLabel(context: context)
                }
                .font(.subheadline)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("IOB \(context.state.iob)")
                Text("COB \(context.state.cob)")
                Text(context.attributes.appName).font(.caption2).foregroundColor(.secondary)
            }
            .font(.subheadline)
        }
        .foregroundColor(.white)
        .padding()
    }
}
