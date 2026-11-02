import Foundation
import WidgetKit
import Shared

/// Native file operations are deliberately narrow: no credentials or app database
/// are shared with the extension. Shared Kotlin owns the serialized contracts.
final class RotationStorage: NSObject, IosRotationStorageBridge {
    static let shared = RotationStorage()
    private let files = FileManager.default
    private let snapshotName = "map-widget-v1.json"

    private var privateCacheURL: URL? {
        guard let directory = files.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let appDirectory = directory.appendingPathComponent("ApexCompanion", isDirectory: true)
        try? files.createDirectory(at: appDirectory, withIntermediateDirectories: true)
        return appDirectory.appendingPathComponent("map-cache-v1.json")
    }

    func readCache() -> String? {
        guard let url = privateCacheURL,
              let data = try? Data(contentsOf: url), data.count <= 1_048_576 else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Returning false keeps the Kotlin repository from publishing an uncommitted result.
    func writeCache(value: String) -> Bool {
        guard let url = privateCacheURL, let data = value.data(using: .utf8),
              data.count <= 1_048_576 else { return false }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }

    func publishWidgetSnapshot(value: String) -> Bool {
        guard let identifier = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
              let directory = files.containerURL(forSecurityApplicationGroupIdentifier: identifier),
              let data = value.data(using: .utf8), data.count <= 1_048_576 else { return false }
        let url = directory.appendingPathComponent(snapshotName)
        // Avoid consuming a WidgetKit reload budget for an unchanged projection.
        guard (try? Data(contentsOf: url)) != data else { return true }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            WidgetCenter.shared.reloadTimelines(ofKind: "ApexCompanionMap")
            return true
        } catch {
            // A missing/unprovisioned App Group leaves widgets UNAVAILABLE.
            // Never fall back to sharing an arbitrary private app path.
            return false
        }
    }

    func clear() {
        if let url = privateCacheURL { try? files.removeItem(at: url) }
        if let identifier = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
           let directory = files.containerURL(forSecurityApplicationGroupIdentifier: identifier) {
            try? files.removeItem(at: directory.appendingPathComponent(snapshotName))
        }
        WidgetCenter.shared.reloadTimelines(ofKind: "ApexCompanionMap")
    }
}
