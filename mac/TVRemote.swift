// TV Remote: press Control+Option+Space anywhere on the Mac and a remote pops up. Drive the TV with
// the keyboard (or click the buttons): it talks to the TV over Wi-Fi through adb. Press the
// shortcut again, or click away, to hide it. Lives in the menu bar; no Dock icon.
//
// Build: swiftc -O -swift-version 5 TVRemote.swift -o TVRemote   (mac/install.sh does this)

import AppKit
import Carbon.HIToolbox
import SwiftUI

// The TV's address, written by install.sh: ~/.config/tv-detox/tv (like 192.168.1.20:5555).
let tvAddress = (try? String(contentsOfFile: NSHomeDirectory() + "/.config/tv-detox/tv", encoding: .utf8))?
    .trimmingCharacters(in: .whitespacesAndNewlines) ?? "192.168.1.20:5555"
let adbPath = ["/opt/homebrew/bin/adb", "/usr/local/bin/adb"].first { FileManager.default.fileExists(atPath: $0) } ?? "adb"

// MARK: - Talking to the TV

/// One long-lived `adb shell`: each command is a line written to it, so key presses land in about
/// a tenth of a second instead of starting a new adb process every time.
final class TV {
    private var shell: Process?
    private var input: FileHandle?
    private let queue = DispatchQueue(label: "tv")
    var onStatus: ((Bool) -> Void)?

    func send(_ command: String) {
        queue.async {
            if self.shell?.isRunning != true { self.open() }
            guard let input = self.input, self.shell?.isRunning == true else { return }
            input.write((command + "\n").data(using: .utf8)!)
        }
    }

    func key(_ code: String) { send("input keyevent \(code)") }

    /// Opens a home screen tile by its key, a site's host ("example.com"), or its position ("1").
    func openTile(_ key: String) { send("am start -n com.ansub.tvhome/.MainActivity --es open \(key)") }

    /// Types text into whatever field is selected on the TV.
    func type(_ text: String) {
        // `input text` takes %s for spaces; everything else is quoted for the TV's shell.
        let escaped = text.replacingOccurrences(of: "'", with: "'\\''").replacingOccurrences(of: " ", with: "%s")
        send("input text '\(escaped)'")
    }

    private func open() {
        run([adbPath, "connect", tvAddress])
        let p = Process()
        p.executableURL = URL(fileURLWithPath: adbPath)
        p.arguments = ["-s", tvAddress, "shell"]
        let pipe = Pipe()
        p.standardInput = pipe
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        p.terminationHandler = { [weak self] _ in self?.status(false) }
        do {
            try p.run()
            shell = p
            input = pipe.fileHandleForWriting
            status(true)
        } catch {
            status(false)
        }
    }

    private func status(_ ok: Bool) { DispatchQueue.main.async { self.onStatus?(ok) } }

    @discardableResult
    private func run(_ args: [String]) -> Int32 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: args[0])
        p.arguments = Array(args.dropFirst())
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try? p.run()
        p.waitUntilExit()
        return p.terminationStatus
    }
}

// MARK: - Remote state

/// What the window shows: connection state, which button is lit, and the text being typed.
final class Remote: ObservableObject {
    let tv: TV
    @Published var online = false
    @Published var lit: String?          // button that flashes after a press
    @Published var typing = false
    @Published var text = ""

    init(tv: TV) {
        self.tv = tv
        tv.onStatus = { [weak self] ok in self?.online = ok }
    }

    /// One button press: send it to the TV and flash the button.
    func press(_ id: String) {
        flash(id)
        switch id {
        case "up": tv.key("DPAD_UP")
        case "down": tv.key("DPAD_DOWN")
        case "left": tv.key("DPAD_LEFT")
        case "right": tv.key("DPAD_RIGHT")
        case "ok": tv.key("DPAD_CENTER")
        case "back": tv.key("BACK")
        case "home": tv.key("HOME")
        case "settings": tv.send("am start -n com.ansub.tvhome/.QuickPanel")
        case "play": tv.key("MEDIA_PLAY_PAUSE")
        case "rew": tv.key("MEDIA_REWIND")
        case "ff": tv.key("MEDIA_FAST_FORWARD")
        case "vol-": tv.key("VOLUME_DOWN")
        case "vol+": tv.key("VOLUME_UP")
        case "mute": tv.key("VOLUME_MUTE")
        case "power": tv.key("POWER")
        case "type": typing = true
        default:
            if id.hasPrefix("tile") { tv.openTile(String(id.dropFirst(4))) }
        }
    }

    func sendText() {
        if !text.isEmpty { tv.type(text); tv.key("ENTER") }
        text = ""
        typing = false
    }

    private func flash(_ id: String) {
        lit = id
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { if self.lit == id { self.lit = nil } }
    }

    /// Keyboard to button. Returns false for keys the remote doesn't use.
    func handle(_ e: NSEvent) -> Bool {
        if typing { return false }
        let id: String?
        switch Int(e.keyCode) {
        case kVK_UpArrow: id = "up"
        case kVK_DownArrow: id = "down"
        case kVK_LeftArrow: id = "left"
        case kVK_RightArrow: id = "right"
        case kVK_Return, kVK_ANSI_KeypadEnter: id = "ok"
        case kVK_Escape, kVK_Delete: id = "back"
        case kVK_Space: id = "play"
        case kVK_ANSI_LeftBracket: id = "rew"
        case kVK_ANSI_RightBracket: id = "ff"
        case kVK_ANSI_Equal, kVK_ANSI_KeypadPlus: id = "vol+"
        case kVK_ANSI_Minus, kVK_ANSI_KeypadMinus: id = "vol-"
        default:
            switch e.charactersIgnoringModifiers?.lowercased() ?? "" {
            case "h": id = "home"
            case "g": id = "settings"
            case "m": id = "mute"
            case "o": id = "power"
            case "t", "/": id = "type"
            case let c where c.count == 1 && "123456789".contains(c): id = "tile" + c
            default: id = nil
            }
        }
        guard let id else { return false }
        press(id)
        return true
    }
}

// MARK: - The window

private let ink = Color.white
private let dim = Color.white.opacity(0.55)

/// A round button with an SF Symbol, which lights up when pressed (by click or key).
struct RoundKey: View {
    @ObservedObject var remote: Remote
    let id: String, symbol: String, hint: String
    var size: CGFloat = 48
    var tint: Color = .white

    var body: some View {
        VStack(spacing: 5) {
            Button { remote.press(id) } label: {
                Image(systemName: symbol)
                    .font(.system(size: size * 0.36, weight: .semibold))
                    .foregroundStyle(remote.lit == id ? Color.black : tint)
                    .frame(width: size, height: size)
                    .background(Circle().fill(remote.lit == id ? Color.white : Color.white.opacity(0.1)))
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.12)))
                    .scaleEffect(remote.lit == id ? 0.92 : 1)
                    .animation(.easeOut(duration: 0.12), value: remote.lit)
            }
            .buttonStyle(.plain)
            Keycap(hint)
        }
    }
}

/// The small key name under a button, like a keycap.
struct Keycap: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .medium, design: .rounded))
            .foregroundStyle(dim)
            .padding(.horizontal, 5).padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 4).fill(Color.white.opacity(0.07)))
    }
}

/// The ring: four arrows around a big OK button, each lighting up on its own.
struct DPad: View {
    @ObservedObject var remote: Remote
    var body: some View {
        ZStack {
            Circle().fill(Color.white.opacity(0.08))
                .overlay(Circle().strokeBorder(Color.white.opacity(0.1)))
            ForEach([("up", "chevron.up", 0.0, -66.0), ("down", "chevron.down", 0.0, 66.0),
                     ("left", "chevron.left", -66.0, 0.0), ("right", "chevron.right", 66.0, 0.0)], id: \.0) { k in
                Button { remote.press(k.0) } label: {
                    Image(systemName: k.1)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(remote.lit == k.0 ? Color.black : ink)
                        .frame(width: 46, height: 46)
                        .background(Circle().fill(remote.lit == k.0 ? Color.white : Color.clear))
                }
                .buttonStyle(.plain)
                .offset(x: k.2, y: k.3)
            }
            Button { remote.press("ok") } label: {
                Text("OK")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundStyle(remote.lit == "ok" ? Color.black : ink)
                    .frame(width: 78, height: 78)
                    .background(Circle().fill(remote.lit == "ok" ? Color.white : Color.white.opacity(0.14)))
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.15)))
                    .scaleEffect(remote.lit == "ok" ? 0.94 : 1)
            }
            .buttonStyle(.plain)
        }
        .frame(width: 196, height: 196)
        .animation(.easeOut(duration: 0.12), value: remote.lit)
    }
}

struct RemoteView: View {
    @ObservedObject var remote: Remote
    @FocusState private var fieldFocused: Bool

    var body: some View {
        VStack(spacing: 18) {
            // Header: name and whether the TV answers.
            HStack {
                Image(systemName: "appletvremote.gen4.fill").foregroundStyle(dim)
                Text("TV Remote").font(.system(size: 15, weight: .semibold)).foregroundStyle(ink)
                Spacer()
                HStack(spacing: 6) {
                    Circle().fill(remote.online ? Color.green : Color.red).frame(width: 7, height: 7)
                    Text(remote.online ? "Connected" : "Offline").font(.system(size: 11, weight: .medium))
                        .foregroundStyle(dim)
                }
                .padding(.horizontal, 9).padding(.vertical, 4)
                .background(Capsule().fill(Color.white.opacity(0.08)))
            }

            HStack(spacing: 26) {
                RoundKey(remote: remote, id: "back", symbol: "chevron.backward", hint: "esc")
                RoundKey(remote: remote, id: "home", symbol: "house.fill", hint: "H")
                RoundKey(remote: remote, id: "settings", symbol: "slider.horizontal.3", hint: "G")
            }

            VStack(spacing: 6) {
                DPad(remote: remote)
                HStack(spacing: 6) { Keycap("← ↑ → ↓"); Keycap("return") }
            }

            HStack(spacing: 18) {
                RoundKey(remote: remote, id: "rew", symbol: "backward.fill", hint: "[", size: 42)
                RoundKey(remote: remote, id: "play", symbol: "playpause.fill", hint: "space", size: 52)
                RoundKey(remote: remote, id: "ff", symbol: "forward.fill", hint: "]", size: 42)
            }

            HStack(spacing: 18) {
                RoundKey(remote: remote, id: "vol-", symbol: "speaker.wave.1.fill", hint: "−", size: 42)
                RoundKey(remote: remote, id: "mute", symbol: "speaker.slash.fill", hint: "M", size: 42)
                RoundKey(remote: remote, id: "vol+", symbol: "speaker.wave.3.fill", hint: "+", size: 42)
                RoundKey(remote: remote, id: "power", symbol: "power", hint: "O", size: 42, tint: Color(red: 1, green: 0.42, blue: 0.4))
            }

            // Typing: T opens the field, Return sends it to the TV's search box.
            HStack(spacing: 8) {
                Image(systemName: "keyboard").foregroundStyle(dim)
                if remote.typing {
                    TextField("Type, then Return", text: $remote.text)
                        .textFieldStyle(.plain)
                        .foregroundStyle(ink)
                        .focused($fieldFocused)
                        .onSubmit { remote.sendText() }
                        .onExitCommand { remote.typing = false; remote.text = "" }
                        .onAppear { fieldFocused = true }
                } else {
                    Text("Type a search").foregroundStyle(dim)
                    Spacer()
                    Keycap("T")
                }
            }
            .font(.system(size: 13))
            .padding(.horizontal, 12).frame(height: 36)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(remote.typing ? 0.14 : 0.07)))
            .onTapGesture { remote.typing = true }

            HStack(spacing: 6) {
                Text("Home tiles").font(.system(size: 11)).foregroundStyle(dim)
                ForEach(1..<10) { n in
                    Button { remote.press("tile\(n)") } label: {
                        Text("\(n)").font(.system(size: 11, weight: .semibold, design: .rounded))
                            .foregroundStyle(remote.lit == "tile\(n)" ? Color.black : ink)
                            .frame(width: 22, height: 22)
                            .background(RoundedRectangle(cornerRadius: 6)
                                .fill(remote.lit == "tile\(n)" ? Color.white : Color.white.opacity(0.08)))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 24).padding(.top, 30).padding(.bottom, 22)
        .frame(width: 360)
    }
}

/// Hosts the SwiftUI remote and sends keys to it before anything else sees them.
final class KeyHost: NSHostingView<RemoteView> {
    var remote: Remote!
    override var acceptsFirstResponder: Bool { true }
    override func keyDown(with e: NSEvent) { if !remote.handle(e) { super.keyDown(with: e) } }
}

final class RemotePanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

// MARK: - App

final class App: NSObject, NSApplicationDelegate, NSWindowDelegate {
    let tv = TV()
    lazy var remote = Remote(tv: tv)
    var panel: RemotePanel!
    var host: KeyHost!
    var item: NSStatusItem!
    var hotKey: EventHotKeyRef?

    func applicationDidFinishLaunching(_ n: Notification) {
        signal(SIGPIPE, SIG_IGN) // a dropped TV connection must not take the app down
        host = KeyHost(rootView: RemoteView(remote: remote))
        host.remote = remote
        let size = host.fittingSize
        panel = RemotePanel(contentRect: NSRect(origin: .zero, size: size),
                            styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.level = .floating
        panel.appearance = NSAppearance(named: .darkAqua)
        panel.delegate = self
        let blur = NSVisualEffectView(frame: NSRect(origin: .zero, size: size))
        blur.material = .hudWindow
        blur.state = .active
        host.frame = blur.bounds
        host.autoresizingMask = [.width, .height]
        blur.addSubview(host)
        panel.contentView = blur
        panel.standardWindowButton(.closeButton)?.isHidden = true
        panel.standardWindowButton(.miniaturizeButton)?.isHidden = true
        panel.standardWindowButton(.zoomButton)?.isHidden = true

        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "appletvremote.gen4", accessibilityDescription: "TV Remote")
            ?? NSImage(systemSymbolName: "tv", accessibilityDescription: "TV Remote")
        let menu = NSMenu()
        menu.addItem(withTitle: "Show remote  (⌃⌥Space)", action: #selector(toggle), keyEquivalent: "").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit TV Remote", action: #selector(NSApp.terminate(_:)), keyEquivalent: "q")
        item.menu = menu

        registerHotKey()
        tv.send("true") // connect in the background so the first key press is instant
    }

    // Control+Option+Space, system-wide. Carbon hot keys need no Accessibility permission.
    func registerHotKey() {
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, ctx in
            let app = Unmanaged<App>.fromOpaque(ctx!).takeUnretainedValue()
            DispatchQueue.main.async { app.toggle() }
            return noErr
        }, 1, &spec, Unmanaged.passUnretained(self).toOpaque(), nil)
        let id = EventHotKeyID(signature: OSType(0x54565252), id: 1) // "TVRR"
        RegisterEventHotKey(UInt32(kVK_Space), UInt32(controlKey | optionKey), id, GetApplicationEventTarget(), 0, &hotKey)
    }

    @objc func toggle() {
        if panel.isVisible { panel.orderOut(nil); return }
        remote.typing = false
        panel.center()
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
        panel.makeFirstResponder(host)
        tv.send("true") // wake the connection so the status is fresh
    }

    // Clicking anywhere else hides it.
    func windowDidResignKey(_ n: Notification) { panel.orderOut(nil) }
}

// `TVRemote --snapshot out.png` draws the remote to an image (for the README) and quits.
if let i = CommandLine.arguments.firstIndex(of: "--snapshot"), i + 1 < CommandLine.arguments.count {
  MainActor.assumeIsolated {
    let r = Remote(tv: TV())
    r.online = true
    r.lit = "ok"
    let view = RemoteView(remote: r).background(Color(red: 0.13, green: 0.13, blue: 0.15)).environment(\.colorScheme, .dark)
    let renderer = ImageRenderer(content: view)
    renderer.scale = 2
    if let cg = renderer.cgImage, let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) {
        try? png.write(to: URL(fileURLWithPath: CommandLine.arguments[i + 1]))
    }
  }
  exit(0)
}

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
