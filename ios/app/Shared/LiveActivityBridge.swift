import ActivityKit
import Foundation
import UIKit
import AapsShared

/// The Swift side of the Live Activity (2026-10-24, per explicit request). Kotlin's `IosLiveActivityUpdater` calls
/// `update` with finished text; this starts the activity or updates the one that runs.
///
/// iOS only lets an app START a Live Activity while it is in front, and ends every Live Activity after about 8 hours. So
/// when there is no running activity, the newest state is kept and the activity starts the next time the app comes to the
/// front. Updates work in the background, which the keep-alive or the RileyLink heartbeat keeps the app in.
final class SwiftLiveActivity: NSObject, LiveActivityBridge {

    private var current: Activity<AapsActivityAttributes>?
    private var latest: AapsActivityAttributes.ContentState?
    private var retryTask: Task<Void, Never>?
    private var statusReporter: LiveActivityStatusReporter?

    override init() {
        super.init()
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, let state = self.latest else { return }
            Task { @MainActor in await self.push(state) }
        }
    }

    // Kotlin installs this on the main thread. ActivityKit's asynchronous results then reach the
    // same AAPS log as Kotlin failures, rather than only appearing in the iPhone's system console.
    func setStatusReporter(reporter: LiveActivityStatusReporter) {
        statusReporter = reporter
        report("bridge ready; activities enabled=\(ActivityAuthorizationInfo().areActivitiesEnabled)")
    }

    private func report(_ message: String, isError: Bool = false) {
        NSLog("AAPS Live Activity: \(message)")
        statusReporter?.report(message: message, isError: isError)
    }

    func update(bg: String, arrow: String, delta: String, iob: String, cob: String, bgTimeMillis: Int64, range: Int32) {
        let state = AapsActivityAttributes.ContentState(
            bg: bg, arrow: arrow, delta: delta, iob: iob, cob: cob,
            bgDate: Date(timeIntervalSince1970: TimeInterval(bgTimeMillis) / 1000.0),
            range: Int(range)
        )
        Task { @MainActor in await self.push(state) }
    }

    @MainActor private func push(_ state: AapsActivityAttributes.ContentState) async {
        latest = state
        // The user can turn Live Activities off for this app in iPhone Settings
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            report("start/update blocked: Live Activities are disabled by iOS", isError: true)
            return
        }
        // Shown greyed out by the widget once the glucose value is 12 minutes old
        let content = ActivityContent(state: state, staleDate: state.bgDate.addingTimeInterval(12 * 60))

        // After an app restart, take back the activity that is still on the lock screen; end any extra ones
        if current == nil {
            let running = Activity<AapsActivityAttributes>.activities
            current = running.first { $0.activityState == .active || $0.activityState == .stale }
            if let activity = current { report("resumed activity \(activity.id)") }
            for extra in running where extra.id != current?.id {
                await extra.end(nil, dismissalPolicy: .immediate)
            }
        }
        // Stale means the content is old, not that the activity has ended. Updating it also works
        // in the background; ending it here would leave nothing on screen until a foreground start.
        if let activity = current, activity.activityState == .active || activity.activityState == .stale {
            await activity.update(content)
            report("updated activity \(activity.id)")
            retryTask?.cancel()
            retryTask = nil
            return
        }
        // Ended by the user or by the 8 hour limit: start a new one, which iOS allows only while the app is in front
        if let ended = current {
            await ended.end(nil, dismissalPolicy: .immediate)
            current = nil
        }
        guard UIApplication.shared.applicationState == .active else {
            report("start waiting for the app to become active")
            scheduleRetry()
            return
        }
        let name = Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "AAPS"
        do {
            report("requesting activity start")
            current = try Activity.request(attributes: AapsActivityAttributes(appName: name), content: content, pushType: nil)
            if let activity = current { report("started activity \(activity.id)") }
            retryTask?.cancel()
            retryTask = nil
        } catch {
            let failure = error as NSError
            report("start failed: \(failure.domain) code=\(failure.code): \(failure.localizedDescription)", isError: true)
            scheduleRetry()
        }
    }

    /// ActivityKit may reject a start during a foreground transition. Keep the latest state and retry
    /// shortly; the normal updater can otherwise suppress an unchanged payload for five minutes.
    @MainActor private func scheduleRetry() {
        guard retryTask == nil else { return }
        retryTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard !Task.isCancelled, let self else { return }
            self.retryTask = nil
            guard UIApplication.shared.applicationState == .active, let state = self.latest else { return }
            await self.push(state)
        }
    }
}
