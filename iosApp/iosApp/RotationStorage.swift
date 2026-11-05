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

    // Implemented — Not Verified. A distinct, backup-excluded private directory owns history.
    // Errors return an invalid record sentinel so shared initialization fails closed, not empty.
    private let historyLock = NSLock()
    private func historyURL() throws -> URL {
        guard let root = files.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            throw CocoaError(.fileNoSuchFile)
        }
        var directory = root.appendingPathComponent("ApexCompanionPrivateHistory", isDirectory: true)
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        var attributes = URLResourceValues()
        attributes.isExcludedFromBackup = true
        try directory.setResourceValues(attributes)
        return directory.appendingPathComponent("ranked-history-v1.json")
    }
    func readHistoryCache() -> String? {
        historyLock.lock()
        defer { historyLock.unlock() }
        do {
            let url = try historyURL()
            guard files.fileExists(atPath: url.path) else { return nil }
            return try readBoundedHistoryJSON(at: url, maxBytes: 4_194_304)
        } catch { return "UNREADABLE_PRIVATE_HISTORY_RECORD" }
    }
    func writeHistoryCache(value: String) -> Bool {
        historyLock.lock()
        defer { historyLock.unlock() }
        guard let data = value.data(using: .utf8), data.count <= 4_194_304 else { return false }
        do {
            let url = try historyURL()
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch { return false }
    }
    func exportHistoryDocument(value: String, completion: IosHistoryDocumentCompletion) {
        HistoryDocumentPicker.shared.exportJSON(value) { completion.complete(value: $0) }
    }
    func importHistoryDocument(completion: IosHistoryDocumentCompletion) {
        HistoryDocumentPicker.shared.importJSON { completion.complete(value: $0) }
    }
    func cancelHistoryDocument() { HistoryDocumentPicker.shared.cancel() }

    // Implemented — Not Verified. Appearance-only private record; no history migration.
    private let appearanceLock = NSLock()
    func readAppearanceCache() -> String? {
        appearanceLock.lock(); defer { appearanceLock.unlock() }
        guard let url = privateURL("appearance-v1.json") else { return "UNREADABLE_APPEARANCE" }
        guard files.fileExists(atPath: url.path) else { return nil }
        do { return try readBoundedHistoryJSON(at: url, maxBytes: 131_072) }
        catch { return "UNREADABLE_APPEARANCE" }
    }
    func writeAppearanceCache(value: String) -> Bool {
        appearanceLock.lock(); defer { appearanceLock.unlock() }
        guard let data = value.data(using: .utf8), data.count <= 131_072,
              let url = privateURL("appearance-v1.json") else { return false }
        do {
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch { return false }
    }
    func publishAppearanceSnapshot(value: String) -> Bool {
        appearanceLock.lock(); defer { appearanceLock.unlock() }
        guard let identifier = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
              let directory = files.containerURL(forSecurityApplicationGroupIdentifier: identifier),
              let data = value.data(using: .utf8), data.count <= 2048 else { return false }
        let url = directory.appendingPathComponent("widget-appearance-v1.json")
        let existingSize = (try? files.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue
        let unchanged = existingSize.map { $0 <= 2048 } == true && (try? Data(contentsOf: url)) == data
        do {
            if !unchanged {
                try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
            // Also recovers a previous committed write whose reload request was interrupted.
            WidgetCenter.shared.reloadAllTimelines()
            return true
        } catch { return false }
    }

    func clear() {
        for name in ["ranked-cache-v1.json", "predator-cache-v1.json", "appearance-v1.json"] {
            if let url = privateURL(name) { try? files.removeItem(at: url) }
        }
        if let url = privateCacheURL { try? files.removeItem(at: url) }
        if let identifier = Bundle.main.object(forInfoDictionaryKey: "ApexAppGroup") as? String,
           let directory = files.containerURL(forSecurityApplicationGroupIdentifier: identifier) {
            try? files.removeItem(at: directory.appendingPathComponent(snapshotName))
            try? files.removeItem(at: directory.appendingPathComponent("ranked-widget-v1.json"))
            try? files.removeItem(at: directory.appendingPathComponent("widget-appearance-v1.json"))
        }
        if let url = try? historyURL() { try? files.removeItem(at: url) }
        WidgetCenter.shared.reloadAllTimelines()
    }
}
