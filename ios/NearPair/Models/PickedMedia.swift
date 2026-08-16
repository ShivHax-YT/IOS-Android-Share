import CoreTransferable
import Foundation
import UniformTypeIdentifiers

struct PickedMedia: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .image) { media in
            SentTransferredFile(media.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "jpg" : received.file.pathExtension
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(ext)
            try FileManager.default.copyItem(at: received.file, to: destination)
            return PickedMedia(url: destination)
        }

        FileRepresentation(contentType: .movie) { media in
            SentTransferredFile(media.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(ext)
            try FileManager.default.copyItem(at: received.file, to: destination)
            return PickedMedia(url: destination)
        }
    }
}

