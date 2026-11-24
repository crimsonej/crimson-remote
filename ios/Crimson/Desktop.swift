import SwiftUI
import UIKit
import WebKit

/// Crimson Desktop for iPhone: when the iPhone is connected to a TV with AirPlay
/// or an HDMI adapter, the TV shows a desktop (wallpaper, clock, browser window,
/// dock) and the iPhone becomes its trackpad and keyboard.
@MainActor
final class DesktopModel: ObservableObject {
    static let shared = DesktopModel()

    @Published var connected = false
    @Published var cursor = CGPoint(x: 400, y: 300)
    @Published var clickPulse = 0
    @Published var url = URL(string: "https://www.google.com")!
    @Published var pageTitle = "Browser"
    var screen = CGSize(width: 1280, height: 720)
    var targets: [String: CGRect] = [:]
    weak var web: WKWebView?

    let shortcuts: [(String, String, String)] = [
        ("Google", "magnifyingglass", "https://www.google.com"),
        ("YouTube", "play.rectangle.fill", "https://m.youtube.com"),
        ("News", "newspaper.fill", "https://news.google.com"),
        ("Wikipedia", "book.fill", "https://www.wikipedia.org"),
        ("Maps", "map.fill", "https://www.google.com/maps"),
    ]

    func move(_ dx: CGFloat, _ dy: CGFloat) {
        cursor.x = min(max(0, cursor.x + dx), screen.width - 2)
        cursor.y = min(max(0, cursor.y + dy), screen.height - 2)
    }

    func click() {
        clickPulse += 1
        for (id, rect) in targets where rect.contains(cursor) {
            if id.hasPrefix("go:"), let u = URL(string: String(id.dropFirst(3))) { open(u) }
            else if id == "back" { web?.goBack() }
            else if id == "reload" { web?.reload() }
            else if id == "web", let web {
                let p = CGPoint(x: cursor.x - rect.minX, y: cursor.y - rect.minY)
                web.evaluateJavaScript("(function(){var e=document.elementFromPoint(\(p.x),\(p.y));if(e){e.focus&&e.focus();e.click();}})()")
            }
            return
        }
    }

    func scroll(_ dy: CGFloat) {
        guard let sv = web?.scrollView else { return }
        let maxY = max(0, sv.contentSize.height - sv.bounds.height)
        sv.setContentOffset(CGPoint(x: 0, y: min(max(0, sv.contentOffset.y + dy), maxY)), animated: false)
    }

    func open(_ u: URL) {
        url = u
        web?.load(URLRequest(url: u))
    }

    func type(_ text: String) {
        let safe = text.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "'", with: "\\'")
        web?.evaluateJavaScript("""
        (function(){var e=document.activeElement;if(!e)return;if(e.value!==undefined){e.value+='\(safe)';
        e.dispatchEvent(new Event('input',{bubbles:true}));}else{document.execCommand('insertText',false,'\(safe)');}})()
        """)
    }

    func backspace() {
        web?.evaluateJavaScript("(function(){var e=document.activeElement;if(e&&e.value!==undefined){e.value=e.value.slice(0,-1);e.dispatchEvent(new Event('input',{bubbles:true}));}})()")
    }

    func enter() {
        web?.evaluateJavaScript("(function(){var e=document.activeElement;if(!e)return;if(e.form){e.form.submit();}else{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));}})()")
    }
}

/// Puts the desktop on the TV when iOS connects an external display.
final class ExternalSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let ws = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: ws)
        w.rootViewController = UIHostingController(rootView: DesktopScreen().environmentObject(DesktopModel.shared))
        w.isHidden = false
        window = w
        Task { @MainActor in
            DesktopModel.shared.screen = ws.screen.bounds.size
            DesktopModel.shared.cursor = CGPoint(x: ws.screen.bounds.midX, y: ws.screen.bounds.midY)
            DesktopModel.shared.connected = true
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        window = nil
        Task { @MainActor in DesktopModel.shared.connected = false }
    }
}

/// Records where clickable things are, in screen coordinates.
private struct Target: ViewModifier {
    let id: String
    @EnvironmentObject var model: DesktopModel
    func body(content: Content) -> some View {
        content.background(GeometryReader { g in
            Color.clear
                .onAppear { model.targets[id] = g.frame(in: .global) }
                .onChange(of: g.frame(in: .global)) { f in model.targets[id] = f }
        })
    }
}

extension View {
    fileprivate func target(_ id: String) -> some View { modifier(Target(id: id)) }
}

struct DesktopScreen: View {
    @EnvironmentObject var model: DesktopModel
    @State private var now = Date()
    @State private var ring = false
    private let timer = Timer.publish(every: 15, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack(alignment: .topLeading) {
            LinearGradient(colors: [Theme.crimson.opacity(0.55), Color(hex: 0x14070C), Color(hex: 0x050507)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                HStack {
                    Text("CRIMSON").font(.system(size: 15, weight: .black)).tracking(5).foregroundStyle(.white)
                    Spacer()
                    Text(now, format: .dateTime.weekday(.abbreviated).day().month()).foregroundStyle(.white.opacity(0.8))
                    Text(now, format: .dateTime.hour().minute()).foregroundStyle(.white).padding(.leading, 12)
                }
                .font(.system(size: 15, weight: .medium))
                .padding(.horizontal, 28).padding(.vertical, 12)
                .background(.black.opacity(0.25))

                HStack(alignment: .top, spacing: 28) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(now, format: .dateTime.hour().minute())
                            .font(.system(size: 88, weight: .thin)).foregroundStyle(.white)
                        Text(now, format: .dateTime.weekday(.wide).day().month(.wide))
                            .font(.system(size: 20, weight: .medium)).foregroundStyle(.white.opacity(0.75))
                        Text("Use your iPhone as the trackpad")
                            .font(.system(size: 14)).foregroundStyle(.white.opacity(0.5)).padding(.top, 12)
                    }
                    .frame(width: 300, alignment: .leading)

                    browser
                }
                .padding(28)

                Spacer(minLength: 0)

                HStack(spacing: 14) {
                    ForEach(model.shortcuts, id: \.2) { s in
                        VStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(LinearGradient(colors: [Theme.crimson, Theme.crimsonDark], startPoint: .topLeading, endPoint: .bottomTrailing))
                                .frame(width: 54, height: 54)
                                .overlay(Image(systemName: s.1).font(.system(size: 22)).foregroundStyle(.white))
                            Text(s.0).font(.system(size: 12)).foregroundStyle(.white.opacity(0.85))
                        }
                        .target("go:" + s.2)
                    }
                }
                .padding(.horizontal, 22).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color.black.opacity(0.55)))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(.white.opacity(0.15)))
                .frame(maxWidth: .infinity)
                .padding(.bottom, 22)
            }

            pointer
        }
        .onReceive(timer) { now = $0 }
        .onChange(of: model.clickPulse) { _ in
            ring = false
            withAnimation(.easeOut(duration: 0.35)) { ring = true }
        }
    }

    private var browser: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "chevron.left").target("back")
                Image(systemName: "arrow.clockwise").target("reload")
                Text(model.url.host ?? model.url.absoluteString)
                    .lineLimit(1)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Capsule().fill(.white.opacity(0.1)))
            }
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(.white)
            .padding(10)
            .background(Color(hex: 0x1A1A22))
            BrowserView().target("web")
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(.white.opacity(0.15)))
        .shadow(color: .black.opacity(0.5), radius: 30, y: 12)
    }

    private var pointer: some View {
        ZStack(alignment: .topLeading) {
            Circle().stroke(Theme.crimson, lineWidth: 2)
                .frame(width: 44, height: 44)
                .scaleEffect(ring ? 1.4 : 0.3)
                .opacity(ring ? 0 : 1)
                .offset(x: -22, y: -22)
            Image(systemName: "cursorarrow")
                .font(.system(size: 26, weight: .regular))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.7), radius: 2, x: 1, y: 1)
        }
        .position(x: model.cursor.x + 8, y: model.cursor.y + 12)
        .allowsHitTesting(false)
    }
}

struct BrowserView: UIViewRepresentable {
    @EnvironmentObject var model: DesktopModel

    func makeCoordinator() -> Coordinator { Coordinator(model) }

    func makeUIView(context: Context) -> WKWebView {
        let w = WKWebView()
        w.navigationDelegate = context.coordinator
        w.load(URLRequest(url: model.url))
        model.web = w
        return w
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate {
        let model: DesktopModel
        init(_ m: DesktopModel) { model = m }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in
                if let u = webView.url { self.model.url = u }
                self.model.pageTitle = webView.title ?? "Browser"
            }
        }
    }
}

/// The iPhone side while the desktop is on the TV.
struct DesktopControls: View {
    @EnvironmentObject var model: DesktopModel
    @Environment(\.dismiss) private var dismiss
    @State private var last: CGPoint?
    @State private var start = Date()
    @State private var moved = false
    @State private var typing = ""
    @FocusState private var focus: Bool

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("CRIMSON DESKTOP").font(.system(size: 12, weight: .black)).tracking(2.5).foregroundStyle(Theme.crimson)
                    Text(model.connected ? "On the TV · \(model.pageTitle)" : "Connect the TV with AirPlay or an HDMI adapter")
                        .font(.system(size: 13)).foregroundStyle(Theme.sub).lineLimit(1)
                }
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark").font(.system(size: 14, weight: .bold)).foregroundStyle(Theme.sub)
                        .frame(width: 36, height: 36).card(radius: 18)
                }
                .buttonStyle(PressStyle())
            }

            ZStack {
                RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Theme.surface)
                    .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).stroke(Theme.stroke))
                VStack(spacing: 6) {
                    Image(systemName: "hand.point.up.left").font(.system(size: 28)).foregroundStyle(Theme.faint)
                    Text("Swipe to move · tap to click").font(.system(size: 13)).foregroundStyle(Theme.faint)
                    Text("Two fingers to scroll").font(.system(size: 12)).foregroundStyle(Theme.faint)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { g in
                    if last == nil { last = g.location; start = Date(); moved = false; return }
                    let dx = g.location.x - last!.x, dy = g.location.y - last!.y
                    last = g.location
                    if abs(g.translation.width) > 8 || abs(g.translation.height) > 8 { moved = true }
                    let speed = 1.8 + min(2.5, hypot(dx, dy) / 14)
                    model.move(dx * speed, dy * speed)
                }
                .onEnded { _ in
                    if !moved && Date().timeIntervalSince(start) < 0.3 { Haptics.tap(); model.click() }
                    last = nil
                })
            .overlay(alignment: .trailing) {
                // A scroll strip on the right edge: drag up and down to scroll the page.
                RoundedRectangle(cornerRadius: 10).fill(Theme.surface2)
                    .frame(width: 26)
                    .overlay(Image(systemName: "arrow.up.and.down").font(.system(size: 11)).foregroundStyle(Theme.faint))
                    .padding(10)
                    .gesture(DragGesture().onChanged { g in model.scroll(-g.velocity(1)) })
            }

            HStack(spacing: 8) {
                TextField("", text: $typing, prompt: Text("Type on the TV…").foregroundColor(Theme.faint))
                    .focused($focus)
                    .foregroundStyle(Theme.text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .padding(.horizontal, 14).frame(height: 46)
                    .card(radius: 23, fill: Theme.surface2)
                    .onChange(of: typing) { v in
                        if v.count > 0 { model.type(v); typing = "" }
                    }
                    .onSubmit { model.enter(); focus = true }
                Button { model.backspace() } label: {
                    Image(systemName: "delete.left").foregroundStyle(Theme.text).frame(width: 46, height: 46).card(radius: 23, fill: Theme.surface2)
                }
                .buttonStyle(PressStyle())
            }

            HStack(spacing: 8) {
                bar("Back", "chevron.left") { model.web?.goBack() }
                bar("Home", "house.fill") { model.open(URL(string: "https://www.google.com")!) }
                bar("Reload", "arrow.clockwise") { model.web?.reload() }
            }
        }
        .padding(20)
    }

    private func bar(_ t: String, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button { Haptics.tap(); action() } label: {
            Label(t, systemImage: icon).font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.text)
                .frame(maxWidth: .infinity).frame(height: 46).card(radius: 23, fill: Theme.surface2)
        }
        .buttonStyle(PressStyle())
    }
}

private extension DragGesture.Value {
    /// Rough vertical speed for the scroll strip.
    func velocity(_ k: CGFloat) -> CGFloat { (predictedEndLocation.y - location.y) * 0.25 * k }
}
