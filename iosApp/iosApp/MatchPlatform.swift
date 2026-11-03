import Foundation
import Security
import CryptoKit
import Shared
import UIKit
import PhotosUI
import UniformTypeIdentifiers
import ImageIO

/// Implemented — Not Verified. App-only storage; no private history or pairing enters App Group.
extension RotationStorage: IosMatchStorageBridge {
    private func matchFileURL() throws -> URL {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        var directory = root.appendingPathComponent("ApexCompanionPrivateMatches", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        return directory.appendingPathComponent("matches-v1.json")
    }
    func readMatchCache() -> String? {
        do {
            let url = try matchFileURL()
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            return try readBoundedHistoryJSON(at: url, maxBytes: 16_777_216)
        } catch { return "UNREADABLE_PRIVATE_MATCH_JOURNAL" }
    }
    func writeMatchCache(value: String) -> Bool {
        guard let data = value.data(using: .utf8), data.count <= 16_777_216 else { return false }
        do {
            try data.write(to: matchFileURL(), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch { return false }
    }
    private var pairingQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "io.github.szymonbonkowski.apexcompanion.match-pairing-v1",
         kSecAttrAccount as String: "local-companion"]
    }
    func readMatchPairing() -> String? {
        var query = pairingQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data, data.count <= 16_384,
              let text = String(data: data, encoding: .utf8) else { return "UNREADABLE_PAIRING" }
        return text
    }
    func writeMatchPairing(value: String?) -> Bool {
        guard let value = value else {
            let status = SecItemDelete(pairingQuery as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        guard let data = value.data(using: .utf8), data.count <= 16_384 else { return false }
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let update = SecItemUpdate(pairingQuery as CFDictionary, attributes as CFDictionary)
        if update == errSecSuccess { return true }
        guard update == errSecItemNotFound else { return false }
        return SecItemAdd(pairingQuery.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess
    }
    func requestMatchSync(pairing: String, path: String, body: String?, completion: IosHistoryDocumentCompletion) {
        MatchSyncRequest.shared.start(pairing: pairing, path: path, body: body) { completion.complete(value: $0) }
    }
    func cancelMatchRequest() { MatchSyncRequest.shared.cancel() }
    func exportMatchDocument(value: String, completion: IosHistoryDocumentCompletion) {
        HistoryDocumentPicker.matches.exportJSON(value) { completion.complete(value: $0) }
    }
    func importMatchDocument(completion: IosHistoryDocumentCompletion) {
        HistoryDocumentPicker.matches.importJSON { completion.complete(value: $0) }
    }
    func cancelMatchDocument() { HistoryDocumentPicker.matches.cancel() }
    func pickMatchScreenshot(completion: IosHistoryDocumentCompletion) {
        MatchPhotoPicker.shared.pick { completion.complete(value: $0) }
    }
    func cancelMatchScreenshot() { MatchPhotoPicker.shared.cancel() }
}

/// Strict pinned trust anchor, existing TLS hostname policy, bounded IO, redirects forbidden.
private final class MatchSyncRequest: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    static let shared = MatchSyncRequest()
    private var session: URLSession?
    private var completion: ((String?) -> Void)?
    private var pin = ""
    private var data = Data()
    private let lock = NSRecursiveLock()
    func start(pairing: String, path: String, body: String?, completion: @escaping (String?) -> Void) {
        lock.lock(); defer { lock.unlock() }
        guard self.completion == nil, pairing.utf8.count <= 16_384,
              let bytes = pairing.data(using: .utf8),
              let config = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any],
              let endpoint = config["endpoint"] as? String,
              let pin = config["certificatePin"] as? String, pin.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              let token = config["token"] as? String, token.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              let base = URLComponents(string: endpoint), base.scheme == "https", let host = base.host,
              Self.isPrivateIPv4(host), let port = base.port, (1024...65535).contains(port),
              base.user == nil, base.password == nil, base.query == nil, base.fragment == nil, base.path.isEmpty,
              path.range(of: "^/v1/matches\\?after=[0-9]{1,19}$", options: .regularExpression) != nil || path == "/v1/ack" || path == "/v1/unpair",
              let url = URL(string: endpoint + path) else { completion(nil); return }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 10)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = body {
            guard let payload = body.data(using: .utf8), payload.count <= 1024 else { completion(nil); return }
            request.httpMethod = "POST"; request.httpBody = payload
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        self.pin = pin; self.completion = completion; data = Data()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil; configuration.httpCookieStorage = nil
        configuration.timeoutIntervalForRequest = 10; configuration.timeoutIntervalForResource = 15
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        session?.dataTask(with: request).resume()
    }
    private static func isPrivateIPv4(_ value: String) -> Bool {
        let pieces = value.split(separator: ".", omittingEmptySubsequences: false)
        guard pieces.count == 4, pieces.allSatisfy({ !$0.isEmpty && $0.count <= 3 && $0.allSatisfy({ $0.isASCII && $0.isNumber }) && ($0 == "0" || !$0.hasPrefix("0")) }),
              let a = Int(pieces[0]), let b = Int(pieces[1]), let c = Int(pieces[2]), let d = Int(pieces[3]),
              [a,b,c,d].allSatisfy({ (0...255).contains($0) }) else { return false }
        return a == 10 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        lock.lock(); defer { lock.unlock() }
        guard self.session === session, challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let certificate = chain.first else {
            completionHandler(.cancelAuthenticationChallenge, nil); return
        }
        let digest = SHA256.hash(data: SecCertificateCopyData(certificate) as Data).map { String(format: "%02x", $0) }.joined()
        guard digest == pin,
              SecTrustSetAnchorCertificates(trust, [certificate] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess,
              SecTrustEvaluateWithError(trust, nil) else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard self.session === session, let response = response as? HTTPURLResponse, (200...299).contains(response.statusCode),
              response.expectedContentLength <= 1_048_576 else { completionHandler(.cancel); return }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock(); defer { lock.unlock() }
        guard self.session === session else { return }
        guard data.count + chunk.count <= 1_048_576 else { dataTask.cancel(); return }
        data.append(chunk)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); defer { lock.unlock() }
        guard self.session === session else { return }
        finish(error == nil ? String(data: data, encoding: .utf8) : nil)
    }
    private func finish(_ result: String?) {
        let callback = completion; completion = nil
        session?.invalidateAndCancel(); session = nil; data.removeAll(keepingCapacity: false); pin = ""
        callback?(result)
    }
    func cancel() { lock.lock(); defer { lock.unlock() }; finish(nil) }
}

/// Real system Photos picker. Images are transient, bounded and never uploaded; no OCR claimed.
private final class MatchPhotoPicker: NSObject, PHPickerViewControllerDelegate {
    static let shared = MatchPhotoPicker()
    private var completion: ((String?) -> Void)?
    private var picker: PHPickerViewController?
    private var generation = UUID()
    private var loading: Progress?
    func pick(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard self.completion == nil,
                  let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
                  var presenter = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { completion(nil); return }
            while let presented = presenter.presentedViewController { presenter = presented }
            var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 1
            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self; picker.modalPresentationStyle = .fullScreen
            self.generation = UUID(); self.completion = completion; self.picker = picker
            presenter.present(picker, animated: true)
        }
    }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        guard self.picker === picker else { return }
        picker.dismiss(animated: true)
        guard let item = results.first else { finish(nil); return }
        let current = generation
        loading = item.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
            var result: String?
            if let url = url, let input = InputStream(url: url) {
                input.open(); defer { input.close() }
                var data = Data(); var buffer = [UInt8](repeating: 0, count: 8192)
                while data.count <= 16_777_216 {
                    let count = input.read(&buffer, maxLength: min(buffer.count, 16_777_217 - data.count))
                    if count < 0 { data.removeAll(); break }
                    if count == 0 { break }
                    data.append(buffer, count: count)
                }
                if !data.isEmpty, data.count <= 16_777_216,
                   let source = CGImageSourceCreateWithData(data as CFData, nil),
                   let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                   let width = properties[kCGImagePropertyPixelWidth] as? Int, let height = properties[kCGImagePropertyPixelHeight] as? Int,
                   (1...16384).contains(width), (1...16384).contains(height),
                   CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                       kCGImageSourceThumbnailMaxPixelSize: 2048, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) != nil {
                    let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                    if let json = try? JSONSerialization.data(withJSONObject: ["evidenceEventId": "screenshot:\(digest)"]) { result = String(data: json, encoding: .utf8) }
                }
                data.resetBytes(in: 0..<data.count); data.removeAll(keepingCapacity: false)
            }
            DispatchQueue.main.async { if current == self.generation { self.finish(result) } }
        }
    }
    private func finish(_ value: String?) { let callback = completion; completion = nil; picker = nil; loading = nil; callback?(value) }
    func cancel() { DispatchQueue.main.async { self.generation = UUID(); self.loading?.cancel(); self.picker?.dismiss(animated: true); self.finish(nil) } }
}
