import Foundation

/// The newest BG for the home screen and lock screen widget (2026-10-28, per explicit request).
///
/// The app writes it each time it updates the Live Activity, and the widget reads it. They are two processes, so the
/// value goes through an App Group: a storage area that both may use. Built into both the app and the LiveActivity
/// extension, the same as `AapsActivityAttributes`, whose `ContentState` is what is stored.
///
/// The App Group must be set up in the Apple developer portal for the app id and the extension id. Without it each side
/// gets its own private storage, the widget finds nothing and shows "Open AAPS" instead of a value.
enum WidgetBgStore {

    /// The widget kind. The app names it when it asks iOS to redraw the widget.
    static let kind = "AapsBgWidget"

    private static let key = "widgetBg"

    /// "group." and the app id. The extension id is the app id with ".LiveActivity" at the end, so both get the same
    /// group. Taken from the bundle rather than written here, so a build with other app ids still finds its own group.
    static var groupId: String? {
        guard var id = Bundle.main.bundleIdentifier else { return nil }
        let extensionSuffix = ".LiveActivity"
        if id.hasSuffix(extensionSuffix) { id = String(id.dropLast(extensionSuffix.count)) }
        return "group." + id
    }

    private static var defaults: UserDefaults? { groupId.flatMap { UserDefaults(suiteName: $0) } }

    static func save(_ state: AapsActivityAttributes.ContentState) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        defaults?.set(data, forKey: key)
    }

    static func load() -> AapsActivityAttributes.ContentState? {
        guard let data = defaults?.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(AapsActivityAttributes.ContentState.self, from: data)
    }
}
