import Foundation
import UIKit
import UniformTypeIdentifiers

/// Implemented — Not Verified. User-invoked system documents only; no automatic sharing.
/// The synchronized iosApp source group includes this file in the app, never the widget target.
final class HistoryDocumentPicker: NSObject, UIDocumentPickerDelegate {
    static let shared = HistoryDocumentPicker()
    private var completion: ((String?) -> Void)?
    private var exporting = false
    private var picker: UIDocumentPickerViewController?
    private var temporaryDirectory: URL?
    private let maxBytes = 4_194_304

    private func presenter() -> UIViewController? {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
              var controller = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return nil }
        while let presented = controller.presentedViewController { controller = presented }
        return controller
    }

    func exportJSON(_ value: String, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard self.completion == nil, let presenter = self.presenter(),
                  let data = value.data(using: .utf8), data.count <= self.maxBytes else {
                completion("ERROR")
                return
            }
            do {
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                self.temporaryDirectory = directory
                let file = directory.appendingPathComponent("apex-companion-backup.json")
                try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                let picker = UIDocumentPickerViewController(forExporting: [file], asCopy: true)
                self.completion = completion
                self.exporting = true
                self.present(picker, from: presenter)
            } catch {
                self.removeTemporaryFile()
                completion("ERROR")
            }
        }
    }

    func importJSON(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard self.completion == nil, let presenter = self.presenter() else {
                completion("DOCUMENT_PICKER_UNAVAILABLE")
                return
            }
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.json, .plainText, .data], asCopy: false)
            picker.allowsMultipleSelection = false
            self.completion = completion
            self.exporting = false
            self.present(picker, from: presenter)
        }
    }

    private func present(_ picker: UIDocumentPickerViewController, from presenter: UIViewController) {
        self.picker = picker
        picker.delegate = self
        // Full screen preserves a definite cancel callback instead of an unobserved sheet swipe.
        picker.modalPresentationStyle = .fullScreen
        presenter.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard picker === controller else { return }
        if exporting { finish("SAVED"); return }
        guard let url = urls.first else { finish(nil); return }
        let granted = url.startAccessingSecurityScopedResource()
        // Keep access until the coordinated bounded read has finished.
        DispatchQueue.global(qos: .userInitiated).async {
            defer { if granted { url.stopAccessingSecurityScopedResource() } }
            var result = "UNREADABLE_BACKUP_DOCUMENT"
            var coordinationError: NSError?
            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readableURL in
                do {
                    result = try readBoundedHistoryJSON(at: readableURL, maxBytes: self.maxBytes)
                } catch { /* Return an invalid document; shared UI reports validation failure. */ }
            }
            if coordinationError != nil { result = "UNREADABLE_BACKUP_DOCUMENT" }
            DispatchQueue.main.async { if self.picker === controller { self.finish(result) } }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        if picker === controller { finish(nil) }
    }

    func cancel() {
        DispatchQueue.main.async {
            self.picker?.dismiss(animated: true)
            self.finish(nil)
        }
    }

    private func removeTemporaryFile() {
        if let directory = temporaryDirectory { try? FileManager.default.removeItem(at: directory) }
        temporaryDirectory = nil
    }

    private func finish(_ result: String?) {
        let callback = completion
        completion = nil
        picker = nil
        removeTemporaryFile()
        callback?(result)
    }
}

/// Bounded bytes and strict UTF-8 before shared schema parsing. Handles short reads correctly.
func readBoundedHistoryJSON(at url: URL, maxBytes: Int) throws -> String {
    let input = try FileHandle(forReadingFrom: url)
    defer { try? input.close() }
    var data = Data()
    while let chunk = try input.read(upToCount: min(8192, maxBytes + 1 - data.count)), !chunk.isEmpty {
        data.append(chunk)
        guard data.count <= maxBytes else { throw CocoaError(.fileReadTooLarge) }
    }
    guard let text = String(data: data, encoding: .utf8) else { throw CocoaError(.fileReadInapplicableStringEncoding) }
    return text
}
