import UIKit
import ObjectiveC
import ComposeApp

/// SDL creates its UIWindow with `initWithFrame:`. Under the UIScene lifecycle (this app has a scene manifest)
/// a window that does not belong to a scene is never displayed, so give every such window the foreground scene
/// as it is created. This replaces what used to be an SDL patch.
extension UIWindow {
    static let attachNewWindowsToScene: Void = {
        guard let original = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.init(frame:))),
              let attaching = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.alpha3_attachedInit(frame:)))
        else { return }
        method_exchangeImplementations(original, attaching)
    }()

    /// After the exchange this is `initWithFrame:`; the call to itself below runs the original.
    @objc(alpha3_attachedInitWithFrame:)
    func alpha3_attachedInit(frame: CGRect) -> UIWindow {
        let window = alpha3_attachedInit(frame: frame)
        if window.windowScene == nil {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            window.windowScene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        }
        return window
    }
}

/// Hosts the Compose launcher and owns orientation: portrait for the launcher, landscape once the game starts.
class LauncherRootViewController: UIViewController {
    static var shared: LauncherRootViewController?
    var content: UIViewController
    var allowLandscape = false

    init(content: UIViewController) {
        self.content = content
        super.init(nibName: nil, bundle: nil)
        LauncherRootViewController.shared = self
        addChild(content)
        view.addSubview(content.view)
        content.didMove(toParent: self)
        view.backgroundColor = .clear
    }
    required init?(coder: NSCoder) { fatalError() }
    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        content.view.frame = view.bounds
    }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return allowLandscape ? .landscape : .portrait
    }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation {
        return allowLandscape ? .landscapeRight : .portrait
    }
    func switchToLandscape() {
        allowLandscape = true
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
            if let windowScene = view.window?.windowScene {
                windowScene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscape))
            }
        } else {
            UIDevice.current.setValue(UIInterfaceOrientation.landscapeRight.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }
}

/// Landscape host for the in-game Compose controls (the same UI Android shows over its SDL surface).
/// Compose forwards touches no control consumed to the engine, so this window can cover the whole screen.
class GameplayHostViewController: UIViewController {
    private let content = GameplayViewControllerKt.GameplayViewController()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        addChild(content)
        view.addSubview(content.view)
        content.didMove(toParent: self)
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        content.view.frame = view.bounds
    }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
}

private var overlayWindow: UIWindow?

func startEngine() {
    guard OpenMWLauncher.hasConfiguredGame else {
        presentAlert(title: "No game files found",
                     message: "Select your Morrowind folder with “Select Games Files” on the Home screen first.")
        return
    }
    LauncherRootViewController.shared?.switchToLandscape()
    if let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene {
        let window = UIWindow(windowScene: scene)
        window.rootViewController = GameplayHostViewController()
        window.windowLevel = UIWindow.Level.statusBar + 1
        window.backgroundColor = .clear
        window.isOpaque = false
        window.makeKeyAndVisible()
        overlayWindow = window
    }
    // The engine never returns and pumps the run loop itself. It must therefore be started from a run-loop
    // timer, not a GCD block: a main-queue block that never finishes would starve every other block queued on
    // the main queue, and Compose resumes its coroutines (recomposition, touch handling) through that queue.
    let timer = Timer(timeInterval: 0.5, repeats: false) { _ in
        do { try OpenMWLauncher.launch() }
        catch { presentAlert(title: "Could not start the game", message: error.localizedDescription) }
    }
    RunLoop.main.add(timer, forMode: .common)
}

private func presentAlert(title: String, message: String) {
    let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
    alert.addAction(UIAlertAction(title: "OK", style: .default))
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }.first { $0.isKeyWindow }?.rootViewController?.present(alert, animated: true)
}
