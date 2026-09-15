import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit

/// Renders a CKB address as a scannable QR code.
///
/// Uses CoreImage's built-in `CIQRCodeGenerator` rather than a third-party
/// library: the address is short enough that error-correction level "M" (the
/// same level Android's ZXing writer defaults to) leaves plenty of margin,
/// and CoreImage ships on every iOS device with nothing to vendor.
enum QrCodeGenerator {
    /// The QR payload for `address`.
    ///
    /// Android encodes the bare address with no scheme prefix (see
    /// `ReceiveScreen.kt`'s `QrCodeImage`); this keeps parity so either app's
    /// camera scanner reads the other's code.
    static func payload(for address: String) -> String {
        address
    }

    /// A crisp, scannable image of `text`, or `nil` if CoreImage refuses to
    /// encode it (for example, empty input).
    ///
    /// `scale` multiplies the generator's native one-point-per-module output
    /// before rasterising, so each module lands on an exact multiple of
    /// pixels with no interpolation blur — the CoreImage equivalent of
    /// nearest-neighbour scaling, since the transform is applied to the
    /// vector-like `CIImage` before it is ever rendered to a bitmap.
    static func image(for text: String, scale: CGFloat = 10) -> UIImage? {
        guard !text.isEmpty else { return nil }

        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"

        guard let output = filter.outputImage else { return nil }

        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }

        return UIImage(cgImage: cgImage)
    }
}
