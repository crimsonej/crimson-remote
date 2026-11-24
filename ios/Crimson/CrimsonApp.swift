import SwiftUI

@main
struct CrimsonApp: App {
    @StateObject private var tv = TVController()

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(tv)
        }
    }
}
