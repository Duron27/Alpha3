import UIKit
import ComposeApp

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        _ = UIWindow.attachNewWindowsToScene
        return true
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

/// The whole UI is Compose Multiplatform (shared with Android); UIKit only hosts it.
class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        LogRedirector.shared.start()
        let launcher = MainViewControllerKt.MainViewController(
            onPlay: { startEngine() },
            onResetSettings: { OpenMWLauncher.resetSettingsToDefault() }
        )
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = LauncherRootViewController(content: launcher)
        window.makeKeyAndVisible()
        self.window = window
    }
}
