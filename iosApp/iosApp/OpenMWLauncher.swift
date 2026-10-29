import UIKit
import ComposeApp

class LogRedirector {
    static let shared = LogRedirector()
    private var timer: Timer?
    private var lastOffset: UInt64 = 0
    private var logURL: URL {
        OpenMWLauncher.userConfigURL.appendingPathComponent("openmw.log")
    }

    func start() {
        // Start watching the engine's internal log file
        timer?.invalidate()
        lastOffset = 0
        
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.checkLogs()
        }
    }

    private func checkLogs() {
        let fm = FileManager.default
        guard fm.fileExists(atPath: logURL.path) else { return }
        
        guard let attributes = try? fm.attributesOfItem(atPath: logURL.path),
              let size = attributes[.size] as? UInt64 else { return }

        if size < lastOffset { lastOffset = 0 } // Log was cleared or rotated
        guard size > lastOffset else { return }

        do {
            let handle = try FileHandle(forReadingFrom: logURL)
            try handle.seek(toOffset: lastOffset)
            let data = handle.readDataToEndOfFile()
            lastOffset = size
            
            if let str = String(data: data, encoding: .utf8) {
                let lines = str.components(separatedBy: .newlines).filter { !$0.isEmpty }
                DispatchQueue.main.async {
                    PlayBridge.shared.addLogs(lines: lines)
                }
            }
            try handle.close()
        } catch {
            print("LogRedirector error: \(error)")
        }
    }
}

/// Bridges the Compose launcher to the OpenMW engine, which is built as
/// libopenmw.dylib (embedded in the app's Frameworks directory) exporting
/// a C `main`.
///
/// Filesystem layout at runtime:
///  - Bundle/OpenMWAssets                    shipped defaults (openmw.cfg, settings.cfg, defaults.bin, resources/);
///                                           the Compose launcher copies them out on first run
///  - Library/Application Support/Alpha3/config   global openmw.cfg: the engine's "local" config (resources, vfs)
///  - Library/Preferences/openmw             user openmw.cfg (game data, content, archives) written by the
///                                           launcher when a Morrowind folder is selected, plus settings.cfg
///  - Documents/<Morrowind folder>           the selected game, copied in through the Files app
enum OpenMWLauncher {

    static var documentsURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static var userConfigURL: URL {
        FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Preferences/openmw", isDirectory: true)
    }

    static var userResourcesURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Alpha3/resources", isDirectory: true)
    }

    static var globalConfigURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Alpha3/config", isDirectory: true)
    }

    /// True once the launcher has set up a game: the user config lists at least one plugin.
    static var hasConfiguredGame: Bool {
        guard let text = try? String(contentsOf: userConfigURL.appendingPathComponent("openmw.cfg"), encoding: .utf8)
        else { return false }
        return text.split(separator: "\n").contains { $0.hasPrefix("content=") }
    }

    /// iOS-only entries the shared Kotlin setup knows nothing about, kept in the user config: Bink video
    /// playback stalls here (the player waits on an audio clock that never advances) and freezes the engine on
    /// the intro logos. Pointing the logo entries at a nonexistent file makes the video widget log
    /// "Failed to open video" and return at once. An empty fallback value is rejected by the config parser.
    private static func ensureIosOverrides() throws {
        let url = userConfigURL.appendingPathComponent("openmw.cfg")
        var text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        let wanted = ["fallback=Movies_Company_Logo,skipped_on_ios.bik",
                      "fallback=Movies_Morrowind_Logo,skipped_on_ios.bik"]
        let lines = Set(text.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) })
        let missing = wanted.filter { !lines.contains($0) }
        guard !missing.isEmpty else { return }
        if !text.isEmpty && !text.hasSuffix("\n") { text += "\n" }
        text += missing.joined(separator: "\n") + "\n"
        try text.write(to: url, atomically: true, encoding: .utf8)
    }

    static var assetsURL: URL? {
        Bundle.main.resourceURL?.appendingPathComponent("OpenMWAssets", isDirectory: true)
    }

    static func resetSettingsToDefault() {
        let fm = FileManager.default
        let settingsURL = userConfigURL.appendingPathComponent("settings.cfg")

        // Remove existing
        if fm.fileExists(atPath: settingsURL.path) {
            try? fm.removeItem(at: settingsURL)
        }

        // Copy from bundle if possible
        if let bundleSettings = assetsURL?.appendingPathComponent("settings.cfg"),
           fm.fileExists(atPath: bundleSettings.path) {
            try? fm.copyItem(at: bundleSettings, to: settingsURL)
        }
    }

    enum LaunchError: LocalizedError {
        case assetsMissing
        case dylibMissing
        case dlopenFailed(String)
        case mainMissing

        var errorDescription: String? {
            switch self {
            case .assetsMissing: return "The base openmw.cfg has not been set up yet. Restart the app and try again."
            case .dylibMissing: return "libopenmw.framework is missing from the app's Frameworks."
            case .dlopenFailed(let msg): return "Could not load the engine: \(msg)"
            case .mainMissing: return "The engine library does not export main()."
            }
        }
    }

    /// gl4es resolves GLES symbols through this hook. It must be a C function
    /// pointer, so the driver handle lives in a global rather than a capture.
    private static var glesDriverHandle: UnsafeMutableRawPointer?

    /// Kept alive for the process lifetime: gl4es's hardware probe needs a
    /// current GL context, and SDL doesn't create one until the engine starts.
    private static var bootstrapGLContext: EAGLContext?

    /// Prepares the OpenGL ES driver and runs its one-time initialization so 
    /// per-context state exists before the engine's first GL call.
    private static func initializeGLES() throws {
        glesDriverHandle = dlopen("/System/Library/Frameworks/OpenGLES.framework/OpenGLES", RTLD_NOW)
        guard glesDriverHandle != nil else {
            throw LaunchError.dlopenFailed(String(cString: dlerror()))
        }

        // The hardware probe needs a current context — crash otherwise. 
        // Try GLES3 first.
        guard let context = EAGLContext(api: .openGLES3) ?? EAGLContext(api: .openGLES2) else {
            throw LaunchError.dlopenFailed("Could not create a bootstrap OpenGL ES context.")
        }
        bootstrapGLContext = context
        EAGLContext.setCurrent(context)

        print("--- OpenGL ES Info ---")
        if let vendor = glGetString(GLenum(GL_VENDOR)) {
            print("Vendor: \(String(cString: vendor))")
        }
        if let renderer = glGetString(GLenum(GL_RENDERER)) {
            print("Renderer: \(String(cString: renderer))")
        }
        if let version = glGetString(GLenum(GL_VERSION)) {
            print("Version: \(String(cString: version))")
        }
        
        var numExtensions: GLint = 0
        glGetIntegerv(GLenum(GL_NUM_EXTENSIONS), &numExtensions)
        print("Extensions (\(numExtensions)):")
        
        for i in 0..<numExtensions {
            if let name = glGetStringi(GLenum(GL_EXTENSIONS), GLuint(i)) {
                print("  \(String(cString: name))")
            }
        }
        print("-----------------------")
    }

    /// Loads libopenmw.framework and calls its `main`. This call blocks and takes
    /// over the process (SDL creates its own window on top of the launcher),
    /// so it must run on the main thread and is effectively one-way.
    static func launch() throws -> Never {
        let globalConfig = globalConfigURL
        guard FileManager.default.fileExists(atPath: globalConfig.appendingPathComponent("openmw.cfg").path)
        else { throw LaunchError.assetsMissing }

        try ensureIosOverrides()

        // The engine resolves its "local" (base) configuration from this
        // directory; see components/files/macospath.cpp. It chains to the
        // user config through `config="?userconfig?"`.
        setenv("OPENMW_LOCAL_CONFIG_DIR", globalConfig.path, 1)
        // OSG on iOS has no plugin bundles; silence plugin path lookups.
        setenv("OSG_LIBRARY_PATH", "", 1)
        // The app has its own keyboards (Compose, and a system input view); SDL must not present one.
        setenv("SDL_ENABLE_SCREEN_KEYBOARD", "0", 1)
        setenv("OPENMW_GLES_VERSION", "30", 1)
        setenv("LIBGL_ES", "3", 1)
        setenv("OSG_GL_CONTEXT_VERSION", "3.0", 1)
        setenv("OSG_GLES3_AVAILABLE", "1", 1)
        setenv("OSG_VERTEX_BUFFER_HINT", "VBO", 1)
        setenv("OSG_TEXT_SHADER_TECHNIQUE", "ALL", 1)
        setenv("OPENMW_DEBUG_OPENGL", "1", 1)
        setenv("OSG_GL_ERROR_CHECKING", "OFF", 1)
        setenv("OSG_NOTIFY_LEVEL", "WARN", 1)
        // MoltenVK: raise per-stage sampler limit above Metal's 16 (needs Tier 2 argument buffers, A13+)
        setenv("MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS", "1", 1)

        guard let fwPath = Bundle.main.privateFrameworksPath else { throw LaunchError.dylibMissing }

        try initializeGLES()

        let frameworkPath = fwPath + "/libopenmw.framework/libopenmw"
        guard FileManager.default.fileExists(atPath: frameworkPath) else { throw LaunchError.dylibMissing }

        guard let handle = dlopen(frameworkPath, RTLD_NOW) else {
            throw LaunchError.dlopenFailed(String(cString: dlerror()))
        }
        guard let sym = dlsym(handle, "main") else { throw LaunchError.mainMissing }

        typealias MainFn = @convention(c) (Int32, UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?) -> Int32
        let engineMain = unsafeBitCast(sym, to: MainFn.self)

        var argv: [UnsafeMutablePointer<CChar>?] = [strdup("openmw"), nil]
        let code = engineMain(1, &argv)
        // The engine returning means the game quit; there is no launcher UI
        // to return to in a meaningful state, so end the process cleanly.
        exit(code)
    }
}
