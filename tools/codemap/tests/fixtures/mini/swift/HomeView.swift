import SwiftUI

public class HomeModel {
    private let service: WalletService

    public func refresh() -> UInt64 {
        return service.cellCount()
    }
}
