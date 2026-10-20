// TV Remote: press Control+Option+Space anywhere on the Mac and a small remote pops up. The keyboard
// then drives the TV over Wi-Fi (through adb, already paired). Press the shortcut again, or click
// away, to hide it. Lives in the menu bar; no Dock icon.
//
// Build: swiftc -O TVRemote.swift -o TVRemote   (see install.sh)

import AppKit
import Carbon.HIToolbox

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

// MARK: - The popup

final class RemotePanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

final class RemoteView: NSView {
    let tv: TV
    var typing = false { didSet { refresh() } }
    private let status = NSTextField(labelWithString: "Connecting…")
    private let field = NSTextField()
    private var keyMonitor: Any?

    // What each key does, shown on the remote itself.
    private let legend: [(String, String)] = [
        ("Arrows", "Move"), ("Return", "OK"), ("Esc  ⌫", "Back"), ("H", "Home"),
        ("Space", "Play / pause"), ("[   ]", "Rewind / forward"), ("+   −", "Volume"), ("M", "Mute"),
        ("G", "Settings sidebar"), ("T", "Type text"), ("O", "TV on / off"), ("", ""),
        ("1 – 9", "Open home tile 1–9"), ("Y   N", "YouTube, Netflix"),
    ]

    init(tv: TV) {
        self.tv = tv
        super.init(frame: NSRect(x: 0, y: 0, width: 460, height: 470))
        wantsLayer = true

        let title = NSTextField(labelWithString: "TV Remote")
        title.font = .systemFont(ofSize: 22, weight: .semibold)
        title.textColor = .white
        title.frame = NSRect(x: 28, y: 420, width: 300, height: 30)
        addSubview(title)

        status.font = .systemFont(ofSize: 12)
        status.textColor = .secondaryLabelColor
        status.frame = NSRect(x: 28, y: 398, width: 400, height: 18)
        addSubview(status)

        // Two columns of key, action.
        var y: CGFloat = 360
        for (i, item) in legend.enumerated() {
            let col: CGFloat = i % 2 == 0 ? 28 : 244
            if i % 2 == 0 && i > 0 { y -= 34 }
            guard !item.0.isEmpty else { continue }
            let k = NSTextField(labelWithString: item.0)
            k.font = .monospacedSystemFont(ofSize: 12, weight: .semibold)
            k.textColor = .white
            k.alignment = .center
            k.wantsLayer = true
            k.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.12).cgColor
            k.layer?.cornerRadius = 6
            k.frame = NSRect(x: col, y: y, width: 70, height: 22)
            addSubview(k)
            let a = NSTextField(labelWithString: item.1)
            a.font = .systemFont(ofSize: 13)
            a.textColor = NSColor.white.withAlphaComponent(0.8)
            a.frame = NSRect(x: col + 80, y: y + 2, width: 130, height: 18)
            addSubview(a)
        }

        field.placeholderString = "Type, then Return to send to the TV"
        field.font = .systemFont(ofSize: 15)
        field.frame = NSRect(x: 28, y: 24, width: 404, height: 28)
        field.isHidden = true
        field.target = self
        field.action = #selector(sendText)
        addSubview(field)

        tv.onStatus = { [weak self] ok in
            self?.status.stringValue = ok ? "Connected to BRAVIA  ·  ⌃⌥Space to hide" : "Can't reach the TV. Is it on and on Wi-Fi?"
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    private func refresh() {
        field.isHidden = !typing
        if typing { window?.makeFirstResponder(field) } else { field.stringValue = ""; window?.makeFirstResponder(self) }
    }

    @objc private func sendText() {
        let text = field.stringValue
        if !text.isEmpty { tv.type(text); tv.key("ENTER") }
        typing = false
    }

    override var acceptsFirstResponder: Bool { true }

    override func keyDown(with e: NSEvent) {
        if typing {
            if e.keyCode == UInt16(kVK_Escape) { typing = false } else { super.keyDown(with: e) }
            return
        }
        switch Int(e.keyCode) {
        case kVK_UpArrow: tv.key("DPAD_UP")
        case kVK_DownArrow: tv.key("DPAD_DOWN")
        case kVK_LeftArrow: tv.key("DPAD_LEFT")
        case kVK_RightArrow: tv.key("DPAD_RIGHT")
        case kVK_Return, kVK_ANSI_KeypadEnter: tv.key("DPAD_CENTER")
        case kVK_Escape, kVK_Delete: tv.key("BACK")
        case kVK_Space: tv.key("MEDIA_PLAY_PAUSE")
        case kVK_ANSI_LeftBracket: tv.key("MEDIA_REWIND")
        case kVK_ANSI_RightBracket: tv.key("MEDIA_FAST_FORWARD")
        case kVK_ANSI_Equal, kVK_ANSI_KeypadPlus: tv.key("VOLUME_UP")
        case kVK_ANSI_Minus, kVK_ANSI_KeypadMinus: tv.key("VOLUME_DOWN")
        default:
            switch e.charactersIgnoringModifiers?.lowercased() ?? "" {
            case "h": tv.key("HOME")
            case "m": tv.key("VOLUME_MUTE")
            case "o": tv.key("POWER")
            case "g": tv.send("am start -n com.ansub.tvhome/.QuickPanel")
            case "t", "/": typing = true
            case "1", "2", "3", "4", "5", "6", "7", "8", "9": tv.openTile(e.charactersIgnoringModifiers!)
            case "y": tv.openTile("com.google.android.youtube.tv")
            case "n": tv.openTile("com.netflix.ninja")
            default: NSSound.beep()
            }
        }
    }
}

// MARK: - App

final class App: NSObject, NSApplicationDelegate, NSWindowDelegate {
    let tv = TV()
    var panel: RemotePanel!
    var item: NSStatusItem!
    var hotKey: EventHotKeyRef?

    func applicationDidFinishLaunching(_ n: Notification) {
        panel = RemotePanel(contentRect: NSRect(x: 0, y: 0, width: 460, height: 470),
                            styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.titleVisibility = .hidden
        panel.titlebarAppearsTransparent = true
        panel.isMovableByWindowBackground = true
        panel.level = .floating
        panel.appearance = NSAppearance(named: .darkAqua)
        panel.delegate = self
        let blur = NSVisualEffectView(frame: panel.contentView!.bounds)
        blur.material = .hudWindow
        blur.state = .active
        blur.autoresizingMask = [.width, .height]
        let remote = RemoteView(tv: tv)
        remote.frame = blur.bounds
        remote.autoresizingMask = [.width, .height]
        blur.addSubview(remote)
        panel.contentView = blur

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
        panel.center()
        NSApp.activate(ignoringOtherApps: true)
        panel.makeKeyAndOrderFront(nil)
        panel.makeFirstResponder(panel.contentView?.subviews.first?.subviews.first ?? panel.contentView)
    }

    // Clicking anywhere else hides it.
    func windowDidResignKey(_ n: Notification) { panel.orderOut(nil) }
}

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
