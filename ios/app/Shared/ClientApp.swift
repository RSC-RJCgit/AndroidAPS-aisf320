import SwiftUI
import AapsShared

/// The app shell, built once into both AAPSClient and AAPSClient2.
///
/// The two products differ only in bundle id, name and icon, exactly as the Android `aapsclient`
/// and `aapsclient2` flavours do, so that one phone can follow two Nightscout sites at once. None
/// of that belongs in the code: the name is read back from the bundle, so this file does not know
/// or care which of the two it is running as.
@main
struct ClientApp: App {

    /// AAPS starts here, not when the first screen is built (upstream, kept in the 2026-11-26 dev41 merge). iOS can start
    /// the app in the background with no window - to hand over a Bluetooth event after it ended the app for memory - and
    /// then SwiftUI builds no screen at all. See `startAapsApp`.
    ///
    /// The fork's apps have no Firebase Crashlytics (2026-11-05): this overload hands in `NoCrashReporter`. The BG Live
    /// Activity is handed in here as well, as ActivityKit is Swift only.
    init() {
        AapsAppHostKt.startAapsApp(nsSocketFactory: SwiftNsSocketFactory(), liveActivity: SwiftLiveActivity())
    }
    var body: some SwiftUI.Scene {
        WindowGroup {
            ShellView()
        }
    }
}

/// Hosts AAPS itself inside SwiftUI.
///
/// Compose Multiplatform hands back a plain UIViewController, so SwiftUI shows it the way it shows
/// any UIKit screen. Nothing here is drawn by SwiftUI: this is the real `AapsAppRoot` from
/// `appshell/commonMain` - the same theme, splash gate, dialog hosts and navigation the Android app
/// runs - built by the Kotlin graph on the other side of this call.
struct AapsAppView: UIViewControllerRepresentable {

    func makeUIViewController(context: UIViewControllerRepresentableContext<AapsAppView>) -> UIViewController {
        // AAPS was started by `ClientApp.init`; this only hands over its screen. The two arguments
        // are used only if nothing started it before.
        //
        // The Nightscout socket is handed to the Kotlin graph from here, because it is written in
        // Swift and cannot carry a Kotlin annotation. Android and desktop contribute their own
        // socket.io client automatically; this is the same project's Swift client, so both platforms
        // speak to Nightscout identically.
        // The Live Activity is handed in the same way and for the same reason: ActivityKit is Swift only.
        AapsAppHostKt.aapsAppViewController(nsSocketFactory: SwiftNsSocketFactory(), liveActivity: SwiftLiveActivity())
    }

    func updateUIViewController(_ controller: UIViewController, context: UIViewControllerRepresentableContext<AapsAppView>) {}
}

/// The whole screen is AAPS.
///
/// The diagnostic page that used to live here - module count, DI check, database round trip - was
/// scaffolding for proving the pieces worked one at a time. They do, so the app shows the app.
/// `ShellInfo`'s checks are still there and still callable if a single layer needs isolating again.
struct ShellView: View {

    var body: some View {
        AapsAppView()
            .ignoresSafeArea()
    }
}
