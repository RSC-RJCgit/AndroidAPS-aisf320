import SwiftUI
import WidgetKit

/// The BG widget for the home screen and the lock screen (2026-10-28, per explicit request). Works on every iPhone, also
/// one without a Dynamic Island.
///
/// It shows what the app last stored in `WidgetBgStore`. iOS decides how often a widget may redraw, so the value can be a
/// few minutes behind the Live Activity. The age counts up by itself, and 12 minutes after the reading the value turns
/// grey and is struck through, the same as the Live Activity, so an old value never looks current.
struct AapsBgWidget: Widget {

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetBgStore.kind, provider: BgProvider()) { entry in
            BgWidgetView(entry: entry)
                .containerBackground(.black, for: .widget)
        }
        .configurationDisplayName("BG")
        .description("Newest glucose value, trend, IOB and COB.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

struct BgEntry: TimelineEntry {

    let date: Date
    /// Nil when the app has stored nothing yet, or the App Group is missing
    let state: AapsActivityAttributes.ContentState?
    let isStale: Bool
}

/// The same limit as the Live Activity's staleDate
private let staleAfter: TimeInterval = 12 * 60

struct BgProvider: TimelineProvider {

    func placeholder(in context: Context) -> BgEntry {
        BgEntry(
            date: Date(),
            state: AapsActivityAttributes.ContentState(bg: "5.5", arrow: "→", delta: "+0.1", iob: "0.50 U", cob: "0 g", bgDate: Date(), range: 0),
            isStale: false
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (BgEntry) -> Void) {
        completion(context.isPreview && WidgetBgStore.load() == nil ? placeholder(in: context) : entries().first!)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<BgEntry>) -> Void) {
        // No refresh of its own: the app asks for a redraw when a new reading arrives
        completion(Timeline(entries: entries(), policy: .never))
    }

    /// Now, and the moment the value becomes old, so it turns grey even if no redraw comes
    private func entries() -> [BgEntry] {
        let now = Date()
        guard let state = WidgetBgStore.load() else { return [BgEntry(date: now, state: nil, isStale: false)] }
        let staleDate = state.bgDate.addingTimeInterval(staleAfter)
        if staleDate <= now { return [BgEntry(date: now, state: state, isStale: true)] }
        return [BgEntry(date: now, state: state, isStale: false), BgEntry(date: staleDate, state: state, isStale: true)]
    }
}

/// Red below the low line, yellow above the high line, green in range, grey once old
private func bgColor(_ state: AapsActivityAttributes.ContentState, isStale: Bool) -> Color {
    if isStale { return .gray }
    switch state.range {
    case ..<0: return .red
    case 1...: return .yellow
    default: return .green
    }
}

struct BgWidgetView: View {

    @Environment(\.widgetFamily) private var family
    let entry: BgEntry

    var body: some View {
        if let state = entry.state {
            switch family {
            case .accessoryInline:
                Text("\(state.bg) \(state.arrow) \(state.delta)")
            case .accessoryCircular:
                VStack(spacing: 0) {
                    bgText(state).font(.headline)
                    Text(state.arrow).font(.caption)
                }
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 4) {
                        bgText(state).font(.title3)
                        Text(state.arrow)
                        Text(state.delta)
                    }
                    ageText(state).font(.caption)
                    Text("IOB \(state.iob)").font(.caption)
                }
            case .systemMedium:
                HStack {
                    mainColumn(state)
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("IOB \(state.iob)")
                        Text("COB \(state.cob)")
                        Text(appName).font(.caption2).foregroundColor(.secondary)
                    }
                    .font(.subheadline)
                }
                .foregroundColor(.white)
            default:
                VStack(alignment: .leading, spacing: 2) {
                    mainColumn(state)
                    Spacer(minLength: 0)
                    Text("IOB \(state.iob)").font(.caption)
                    Text("COB \(state.cob)").font(.caption)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .foregroundColor(.white)
            }
        } else {
            Text("Open AAPS").font(.caption).foregroundColor(.secondary)
        }
    }

    private func bgText(_ state: AapsActivityAttributes.ContentState) -> some View {
        Text(state.bg)
            .fontWeight(.bold)
            .foregroundColor(bgColor(state, isStale: entry.isStale))
            .strikethrough(entry.isStale)
    }

    private func ageText(_ state: AapsActivityAttributes.ContentState) -> some View {
        Text(state.bgDate, style: .relative)
            .foregroundColor(entry.isStale ? .red : .secondary)
    }

    private func mainColumn(_ state: AapsActivityAttributes.ContentState) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 4) {
                bgText(state).font(.system(size: 34))
                Text(state.arrow).font(.title2).foregroundColor(bgColor(state, isStale: entry.isStale))
            }
            HStack(spacing: 6) {
                Text(state.delta)
                ageText(state)
            }
            .font(.subheadline)
        }
    }

    private var appName: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "AAPS"
    }
}
