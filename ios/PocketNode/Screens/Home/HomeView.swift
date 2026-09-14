import PocketNodeCore
import SwiftUI

struct HomeView: View {
    @Environment(AppContainer.self) private var container

    private var theme: Theme { container.theme }

    var body: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Wallet coming in M2")
                    .font(.headline)
                Text("Balance, send and receive land in the next milestone. The embedded light client is already running underneath.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(theme.primary.opacity(0.35), lineWidth: 1)
            )

            // Proves the Kotlin Multiplatform framework is linked and callable.
            Text(SharedCore.shared.describe())
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(theme.background)
    }
}
