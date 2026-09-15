import CoreImage
import XCTest

@testable import PocketNode

final class QrCodeGeneratorTests: XCTestCase {
    private let testnetAddress = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4"

    func testPayloadEqualsTheBareAddress() {
        XCTAssertEqual(QrCodeGenerator.payload(for: testnetAddress), testnetAddress)
    }

    func testImageIsNonNilAndSquare() {
        let image = QrCodeGenerator.image(for: testnetAddress)

        let unwrapped = try? XCTUnwrap(image)
        XCTAssertNotNil(unwrapped)
        XCTAssertEqual(unwrapped?.size.width, unwrapped?.size.height)
    }

    func testEmptyTextProducesNoImage() {
        XCTAssertNil(QrCodeGenerator.image(for: ""))
    }

    /// Round-trips the generated image back through `CIDetector` to prove it
    /// actually encodes the address, not just that some image came out.
    func testGeneratedImageDecodesBackToTheAddress() throws {
        let image = try XCTUnwrap(QrCodeGenerator.image(for: testnetAddress, scale: 10))
        let ciImage = try XCTUnwrap(CIImage(image: image))

        let detector = try XCTUnwrap(
            CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        )
        let features = detector.features(in: ciImage).compactMap { $0 as? CIQRCodeFeature }

        XCTAssertEqual(features.count, 1)
        XCTAssertEqual(features.first?.messageString, testnetAddress)
    }
}
