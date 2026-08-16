import CryptoKit
import Foundation
import UniformTypeIdentifiers

actor FileStore {
    private let fileManager = FileManager.default
    private let outgoingDirectory: URL
    private let inboxDirectory: URL

    init() {
        let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let applicationSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        outgoingDirectory = caches.appendingPathComponent("outgoing", isDirectory: true)
        inboxDirectory = applicationSupport.appendingPathComponent("Inbox", isDirectory: true)
    }

    func stageOutgoing(_ sourceURL: URL) throws -> StagedFile {
        let accessed = sourceURL.startAccessingSecurityScopedResource()
        defer { if accessed { sourceURL.stopAccessingSecurityScopedResource() } }

        try fileManager.createDirectory(at: outgoingDirectory, withIntermediateDirectories: true)
        let values = try sourceURL.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
        let name = Self.sanitizeFileName(sourceURL.lastPathComponent)
        let type = values.contentType ?? UTType(filenameExtension: sourceURL.pathExtension)
        guard let mimeType = type?.preferredMIMEType, TransferMetadata.isAllowed(mimeType: mimeType) else {
            throw FileStoreError.unsupportedType
        }
        if let size = values.fileSize, !hasCapacity(in: outgoingDirectory, bytes: Int64(size)) {
            throw FileStoreError.insufficientStorage
        }

        let destination = outgoingDirectory.appendingPathComponent("\(UUID().uuidString)-\(name)")
        do {
            let result = try copyAndHash(from: sourceURL, to: destination)
            guard result.size > 0 else { throw FileStoreError.emptyFile }
            if let expected = values.fileSize, result.size != Int64(expected) { throw FileStoreError.fileChanged }
            return StagedFile(
                url: destination,
                displayName: name,
                mimeType: mimeType,
                sizeBytes: result.size,
                sha256: result.sha256
            )
        } catch {
            try? fileManager.removeItem(at: destination)
            throw error
        }
    }

    func stageOwnedTemporary(_ sourceURL: URL) throws -> StagedFile {
        defer { try? fileManager.removeItem(at: sourceURL) }
        try fileManager.createDirectory(at: outgoingDirectory, withIntermediateDirectories: true)
        let values = try sourceURL.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
        let name = Self.sanitizeFileName(sourceURL.lastPathComponent)
        let type = values.contentType ?? UTType(filenameExtension: sourceURL.pathExtension)
        guard let mimeType = type?.preferredMIMEType, TransferMetadata.isAllowed(mimeType: mimeType) else {
            throw FileStoreError.unsupportedType
        }
        guard let expectedSize = values.fileSize, expectedSize > 0 else { throw FileStoreError.emptyFile }

        let destination = outgoingDirectory.appendingPathComponent("\(UUID().uuidString)-\(name)")
        do {
            do {
                try fileManager.moveItem(at: sourceURL, to: destination)
            } catch {
                guard hasCapacity(in: outgoingDirectory, bytes: Int64(expectedSize)) else {
                    throw FileStoreError.insufficientStorage
                }
                try fileManager.copyItem(at: sourceURL, to: destination)
            }
            let result = try hashFile(destination)
            guard result.size == Int64(expectedSize) else { throw FileStoreError.fileChanged }
            return StagedFile(
                url: destination,
                displayName: name,
                mimeType: mimeType,
                sizeBytes: result.size,
                sha256: result.sha256
            )
        } catch {
            try? fileManager.removeItem(at: destination)
            throw error
        }
    }

    func hasCapacity(for metadata: TransferMetadata) -> Bool {
        hasCapacity(in: inboxDirectory, bytes: metadata.sizeBytes)
    }

    func commitIncoming(resourceURL: URL, metadata: TransferMetadata) throws -> ReceivedFile {
        guard hasCapacity(for: metadata) else { throw FileStoreError.insufficientStorage }
        try fileManager.createDirectory(at: inboxDirectory, withIntermediateDirectories: true)
        let safeName = uniqueInboxName(Self.sanitizeFileName(metadata.fileName))
        let partial = inboxDirectory.appendingPathComponent(".\(safeName).\(UUID().uuidString).part")
        let destination = inboxDirectory.appendingPathComponent(safeName)
        do {
            let result = try copyAndHash(from: resourceURL, to: partial)
            guard result.size == metadata.sizeBytes else { throw FileStoreError.sizeMismatch }
            guard result.sha256 == metadata.sha256 else { throw FileStoreError.checksumMismatch }
            try fileManager.moveItem(at: partial, to: destination)
            try? fileManager.removeItem(at: resourceURL)
            return ReceivedFile(
                url: destination,
                displayName: safeName,
                mimeType: metadata.mimeType,
                sizeBytes: result.size,
                sha256: result.sha256
            )
        } catch {
            try? fileManager.removeItem(at: partial)
            try? fileManager.removeItem(at: destination)
            throw error
        }
    }

    func delete(url: URL) {
        try? fileManager.removeItem(at: url)
    }

    nonisolated static func sanitizeFileName(_ raw: String) -> String {
        let leaf = raw.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? raw
        let invalid = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\:*?\"<>|"))
        let mapped = leaf.unicodeScalars.map { invalid.contains($0) ? "_" : String($0) }.joined()
        let cleaned = mapped.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "^\\.+", with: "", options: .regularExpression)
        if cleaned.isEmpty { return "received-file" }
        var limited = ""
        for character in cleaned {
            let next = limited + String(character)
            if next.utf8.count > 180 { break }
            limited = next
        }
        return limited.isEmpty ? "received-file" : limited
    }

    private func hasCapacity(in directory: URL, bytes: Int64) -> Bool {
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let capacity = try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage
        let margin = max(32 * 1024 * 1024, bytes / 20)
        let (required, overflow) = bytes.addingReportingOverflow(margin)
        return !overflow && Int64(capacity ?? 0) >= required
    }

    private func uniqueInboxName(_ preferred: String) -> String {
        let initial = inboxDirectory.appendingPathComponent(preferred)
        if !fileManager.fileExists(atPath: initial.path) { return preferred }
        let source = URL(fileURLWithPath: preferred)
        let stem = source.deletingPathExtension().lastPathComponent
        let ext = source.pathExtension.isEmpty ? "" : ".\(source.pathExtension)"
        var suffix = 2
        while fileManager.fileExists(atPath: inboxDirectory.appendingPathComponent("\(stem) (\(suffix))\(ext)").path) {
            suffix += 1
        }
        return "\(stem) (\(suffix))\(ext)"
    }

    private func copyAndHash(from source: URL, to destination: URL) throws -> (size: Int64, sha256: String) {
        fileManager.createFile(atPath: destination.path, contents: nil)
        let input = try FileHandle(forReadingFrom: source)
        let output = try FileHandle(forWritingTo: destination)
        defer {
            try? input.close()
            try? output.close()
        }

        var hasher = SHA256()
        var total: Int64 = 0
        while let chunk = try input.read(upToCount: 1024 * 1024), !chunk.isEmpty {
            try output.write(contentsOf: chunk)
            hasher.update(data: chunk)
            total += Int64(chunk.count)
        }
        try output.synchronize()
        let hash = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return (total, hash)
    }

    private func hashFile(_ url: URL) throws -> (size: Int64, sha256: String) {
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        var hasher = SHA256()
        var total: Int64 = 0
        while let chunk = try input.read(upToCount: 1024 * 1024), !chunk.isEmpty {
            hasher.update(data: chunk)
            total += Int64(chunk.count)
        }
        let hash = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return (total, hash)
    }
}

enum FileStoreError: LocalizedError {
    case unsupportedType
    case insufficientStorage
    case emptyFile
    case fileChanged
    case sizeMismatch
    case checksumMismatch

    var errorDescription: String? {
        switch self {
        case .unsupportedType: return "Only PDFs, images, and videos are supported."
        case .insufficientStorage: return "There is not enough free space to stage this file."
        case .emptyFile: return "Empty files are not supported."
        case .fileChanged: return "The selected file changed while it was being staged."
        case .sizeMismatch: return "The received byte count did not match the sender's metadata."
        case .checksumMismatch: return "The received SHA-256 did not match the sender's metadata."
        }
    }
}
