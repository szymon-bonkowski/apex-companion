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


    // Ranked private data and global Predator cache use distinct atomic records.
    // No UID, name or credential is copied to ranked-widget-v1.json.
    private func privateURL(_ name: String) -> URL? {
        privateCacheURL?.deletingLastPathComponent().appendingPathComponent(name)
    }
    private func readPrivate(_ name: String) -> String? {
        guard let url = privateURL(name), let data = try? Data(contentsOf: url),
              data.count <= 524_288 else { return nil }
        return String(data: data, encoding: .utf8)
    }
    private func writePrivate(_ name: String, value: String) -> Bool {
        guard let url = privateURL(name), let data = value.data(using: .utf8),
              data.count <= 524_288 else { return false }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch { return false }
    }
    func readRankedCache() -> String? { readPrivate("ranked-cache-v1.json") }
    func writeRankedCache(value: String) -> Bool {
        guard writePrivate("ranked-cache-v1.json", value: value),
              var url = privateURL("ranked-cache-v1.json") else { return false }
        do {
            var attributes = URLResourceValues()
            attributes.isExcludedFromBackup = true
            try url.setResourceValues(attributes)
            return true
        } catch { return false }
    }
    func readPredatorCache() -> String? { readPrivate("predator-cache-v1.json") }
    func writePredatorCache(value: String) -> Bool { writePrivate("predator-cache-v1.json", value: value) }
    func publishRankedWidgetSnapshot(value: String) -> Bool {
        guard let identifier = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
              let directory = files.containerURL(forSecurityApplicationGroupIdentifier: identifier),
              let data = value.data(using: .utf8), data.count <= 131_072 else { return false }
        let url = directory.appendingPathComponent("ranked-widget-v1.json")
        guard (try? Data(contentsOf: url)) != data else { return true }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            for kind in ["ApexCompanionRankProgress", "ApexCompanionRankedGoal", "ApexCompanionPredator"] {
                WidgetCenter.shared.reloadTimelines(ofKind: kind)
            }
            return true
        } catch { return false }
    }

    func clear() {
        for name in ["ranked-cache-v1.json", "predator-cache-v1.json"] {
            if let url = privateURL(name) { try? files.removeItem(at: url) }
        }
        if let url = privateCacheURL { try? files.removeItem(at: url) }
        if let identifier = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
           let directory = files.containerURL(forSecurityApplicationGroupIdentifier: identifier) {
            try? files.removeItem(at: directory.appendingPathComponent(snapshotName))
            try? files.removeItem(at: directory.appendingPathComponent("ranked-widget-v1.json"))
        }
        WidgetCenter.shared.reloadAllTimelines()
    }
}
