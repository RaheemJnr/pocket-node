import SwiftUI

/// The send form: who, how much, what it will cost.
///
/// Deliberately the same shape as Android's `SendScreen`, down to the field
/// order, the helper line and the wording of every message, so a user with
/// both apps reads the same screen twice. What is NOT here is also deliberate:
/// no bulk mode and no contact picker, neither of which iOS has yet.
///
/// Nothing on this screen builds or signs anything. The Send button opens
/// ``SendReviewSheet``, and only its Confirm reaches a key.
struct SendView: View {
    @Bindable var model: SendViewModel
    let theme: Theme

    /// Builds the scanner's view model, so this view never has to know about
    /// cameras or preferences. `AppContainer` supplies it.
    let makeScanner: (@escaping (String) -> Void) -> QrScannerViewModel

    /// Pops back to Home once a confirmed send has been dismissed.
    let onFinished: () -> Void

    /// True only for the throwaway wallet `POCKETNODE_SKIP_ONBOARDING` seeds.
    /// The debug drive hooks refuse to run without it; see
    /// ``prefillForTestingIfRequested()``.
    var isSeededTestWallet = false

    /// What the amount field is showing, as opposed to what the model kept.
    ///
    /// The field is bound to this rather than straight to the view model
    /// because a `Binding` whose setter sanitises cannot correct the field it
    /// is bound to: SwiftUI pushes a new string down only when the binding's
    /// value differs from the one it last rendered, and dropping a ninth
    /// decimal gives back exactly the string the previous keystroke already
    /// rendered. The extra character the user typed would then stay on screen
    /// while the amount behind it was already truncated, which is a screen
    /// saying one number and a send meaning another. See ``syncAmount(_:)``.
    @State private var amountText = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                availableRow
                recipientField
                amountField
                if let dustWarning = model.dustWarning {
                    warningCard(dustWarning)
                        .accessibilityIdentifier("send.dustWarning")
                }
                feeRow
                Divider()
                if model.hasWatchedTransaction, !model.showsStatusSheet {
                    reopenStatusRow
                }
                submitButton
            }
            .padding()
        }
        .background(theme.background)
        .navigationTitle("Send CKB")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $model.isScannerPresented) {
            NavigationStack {
                QrScannerView(viewModel: makeScanner { model.acceptScanned(address: $0) })
                    .navigationTitle("Scan")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { model.isScannerPresented = false }
                        }
                    }
            }
        }
        .sheet(isPresented: reviewBinding) {
            if let review = model.review {
                SendReviewSheet(
                    review: review,
                    theme: theme,
                    acknowledged: $model.sweepAcknowledged,
                    onConfirm: { Task { await model.confirm() } },
                    onCancel: { model.cancelReview() }
                )
            }
        }
        .sheet(isPresented: statusBinding) {
            SendStatusSheet(
                status: model.status,
                network: model.network,
                theme: theme,
                onDismiss: { model.dismissStatus() },
                onHide: { model.hideStatus() }
            )
            // A swipe on an in-flight sheet is a Hide, not a cancellation:
            // `statusBinding` routes it there. Only a settled sheet's swipe is
            // the destructive one, because by then there is nothing left to
            // poll for.
        }
        .overlay {
            if let alert = model.alert {
                SendFailureDialog(
                    alert: alert,
                    theme: theme,
                    onDismiss: { model.dismissAlert() },
                    onRetry: { Task { await model.retry() } }
                )
            }
        }
        .onChange(of: model.isFinished) { _, finished in
            if finished { onFinished() }
        }
        .onAppear(perform: prefillForTestingIfRequested)
        // `children: .contain` first, or the identifier below is pushed down
        // onto every field and button inside and they all lose their own.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("send.root")
    }

    // MARK: - Available

    private var availableRow: some View {
        HStack {
            Text("Available")
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
            Text("\(model.availableText) CKB")
                .fontWeight(.medium)
                .monospacedDigit()
                .accessibilityIdentifier("send.available")
        }
        .font(.subheadline)
    }

    // MARK: - Recipient

    private var recipientField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Recipient Address")
                .font(.footnote.weight(.medium))

            HStack(spacing: 8) {
                TextField("Enter CKB Address", text: recipientBinding, axis: .vertical)
                    .lineLimit(1...3)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .disabled(model.isBusy)
                    .accessibilityIdentifier("send.recipient")

                Button {
                    model.isScannerPresented = true
                } label: {
                    Image(systemName: "qrcode.viewfinder")
                        .imageScale(.large)
                }
                .accessibilityLabel("Scan")
                .accessibilityIdentifier("send.scan")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 8))

            if let indicator = model.addressIndicator {
                Label {
                    Text(indicator.message)
                } icon: {
                    Image(systemName: icon(for: indicator))
                }
                .font(.caption)
                .foregroundStyle(colour(for: indicator))
                .accessibilityIdentifier("send.addressState")
            }
        }
    }

    // MARK: - Amount

    private var amountField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Amount")
                .font(.footnote.weight(.medium))

            HStack(spacing: 8) {
                TextField("Enter Amount", text: $amountText)
                    .keyboardType(.decimalPad)
                    .disabled(model.isBusy)
                    .accessibilityIdentifier("send.amount")

                Button("MAX") {
                    Task { await model.setMaxAmount() }
                }
                .font(.caption2.weight(.bold))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(theme.primary.opacity(0.1), in: Capsule())
                .disabled(!model.isMaxEnabled)
                .accessibilityIdentifier("send.max")

                Text("CKB")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 8))

            Text("Min: 61 CKB · Max 8 decimal places")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        // Typing: the model sanitises, and the field is corrected when it
        // disagrees.
        .onChange(of: amountText) { _, typed in syncAmount(typed) }
        // Everything that sets the amount without a keystroke: MAX, the debug
        // drive hooks, and the clear a confirmed send does. `initial: true`
        // also seeds the mirror, so a view rebuilt with a new identity over a
        // view model that already holds an amount does not blank the field.
        .onChange(of: model.amount, initial: true) { _, kept in
            if amountText != kept { amountText = kept }
        }
    }

    /// Hands a keystroke to the model and corrects the field if the model kept
    /// something else.
    ///
    /// The correction is deferred by one main-actor turn rather than applied
    /// inline. SwiftUI is in the middle of processing the change that brought
    /// us here, and writing the state it is already reading would either be
    /// coalesced away or be an "modifying state during view update" warning;
    /// a turn later it is an ordinary state change and the field redraws.
    ///
    /// It cannot loop: the deferred write puts `amountText` at exactly the
    /// value the model kept, so the `onChange` it triggers hands the model a
    /// string it answers with unchanged, and this returns early.
    @MainActor
    private func syncAmount(_ typed: String) {
        model.updateAmount(typed)
        let kept = model.amount
        guard kept != typed else { return }
        Task { @MainActor in
            if amountText != kept { amountText = kept }
        }
    }

    // MARK: - Fee and submit

    private var feeRow: some View {
        HStack {
            Text("Estimated Fee")
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
            Text(model.estimatedFeeText)
                .fontWeight(.medium)
                .monospacedDigit()
                .accessibilityIdentifier("send.fee")
        }
        .font(.subheadline)
    }

    /// Where a hidden sheet goes while its transaction is still being
    /// watched. Without it, Hide would be indistinguishable from a
    /// cancellation and the user would have no way back to the hash.
    private var reopenStatusRow: some View {
        Button {
            model.reopenStatus()
        } label: {
            HStack(spacing: 8) {
                if !model.status.isSettled { ProgressView() }
                Text(model.status.message.isEmpty ? "Transaction in progress" : model.status.message)
                    .font(.footnote)
                    .multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                Image(systemName: "chevron.up")
                    .font(.caption)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 8))
        .accessibilityIdentifier("send.reopenStatus")
    }

    private var submitButton: some View {
        Button {
            Task { await model.submit() }
        } label: {
            HStack(spacing: 8) {
                if model.isBusy {
                    ProgressView()
                    Text(model.busyMessage)
                } else {
                    Text("Send CKB")
                }
            }
            .frame(maxWidth: .infinity, minHeight: 28)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!model.canSubmit)
        .accessibilityIdentifier("send.submit")
    }

    private func warningCard(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Theme.pendingAmber)
            Text(text)
                .font(.footnote)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.pendingAmber.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
    }

    /// Fills the form, and optionally walks it as far as the review sheet or
    /// past Confirm, from the environment.
    ///
    /// It exists for the same reason `POCKETNODE_START_ROUTE` does: a
    /// screenshot or acceptance run on a simulator cannot tap, because driving
    /// the UI from outside the app needs assistive access that a headless run
    /// does not have.
    ///
    /// Debug-only, and gated on the wallet actually being the seeded one, not
    /// merely on `POCKETNODE_SKIP_ONBOARDING` being set.
    ///
    /// The distinction matters. That seed is a no-op when a wallet is already
    /// stored, so the flag alone says nothing about whose wallet is open: a
    /// DEBUG build on a device holding a real wallet, launched with these
    /// variables, would have filled the form in and, with
    /// `POCKETNODE_SEND_CONFIRM`, ticked the sweep box and run a real send
    /// behind a single Face ID prompt. Requiring the seeded wallet's id closes
    /// that: the only wallet these hooks can drive is one with metadata and no
    /// key material, so the confirm hook can only ever reach the key step and
    /// fail there, which is the state it exists to photograph. On any other
    /// wallet, seeded flag or not, it does nothing at all.
    private func prefillForTestingIfRequested() {
        #if DEBUG
        let environment = ProcessInfo.processInfo.environment
        guard isSeededTestWallet,
              environment["POCKETNODE_SKIP_ONBOARDING"] == "1",
              let recipient = environment["POCKETNODE_SEND_RECIPIENT"],
              model.recipient.isEmpty
        else { return }

        model.updateRecipient(recipient)
        if let amount = environment["POCKETNODE_SEND_AMOUNT"] {
            model.updateAmount(amount)
        }
        guard environment["POCKETNODE_SEND_REVIEW"] == "1" else { return }
        Task {
            // The balance arrives from the sync layer some seconds after the
            // screen does, and submitting before it lands is refused as
            // "Insufficient balance" against a balance of zero. A real user
            // cannot type that fast; this wait is what stands in for the
            // seconds they would have taken.
            for _ in 0..<120 where model.availableShannons == 0 {
                try? await Task.sleep(for: .milliseconds(500))
            }
            // The cached balance arrives before the node has a tip, so the
            // first attempt can still be refused with "the wallet is still
            // starting up". Tapping Send again is what a user does about
            // that, and this does the same until the preview succeeds.
            for attempt in 0..<12 where model.review == nil {
                if attempt > 0 { try? await Task.sleep(for: .seconds(5)) }
                model.dismissAlert()
                await model.submit()
            }
            guard environment["POCKETNODE_SEND_CONFIRM"] == "1" else { return }
            model.sweepAcknowledged = true
            await model.confirm()
        }
        #endif
    }

    // MARK: - Bindings

    /// Routed through the view model rather than bound straight to its
    /// property, so the address indicator is recomputed on every keystroke
    /// exactly as Android's `updateRecipient` does.
    ///
    /// A plain setter binding is enough here where the amount needs a mirror
    /// (see ``amountText``): nothing rewrites what was typed into this field,
    /// so the field and the model can never end up holding different strings.
    private var recipientBinding: Binding<String> {
        Binding(get: { model.recipient }, set: { model.updateRecipient($0) })
    }

    private var reviewBinding: Binding<Bool> {
        Binding(
            get: { model.review != nil },
            set: { if !$0 { model.cancelReview() } }
        )
    }

    private var statusBinding: Binding<Bool> {
        Binding(
            get: { model.showsStatusSheet },
            set: { isPresented in
                guard !isPresented else { return }
                // A settled transaction has nothing left to watch, so putting
                // its sheet away is the same as finishing with it. An
                // in-flight one is only hidden.
                if model.status.isSettled {
                    model.dismissStatus()
                } else {
                    model.hideStatus()
                }
            }
        )
    }

    private func icon(for indicator: AddressIndicator) -> String {
        switch indicator {
        case .invalid: return "xmark.circle.fill"
        case .wrongNetwork: return "exclamationmark.triangle.fill"
        case .valid: return "checkmark.circle.fill"
        }
    }

    private func colour(for indicator: AddressIndicator) -> Color {
        switch indicator {
        case .invalid: return Theme.errorRed
        case .wrongNetwork: return Theme.pendingAmber
        case .valid: return theme.primary
        }
    }
}

/// The one failure dialog: a refused form and a refused broadcast both land
/// here, which is what Android does and for the same reason.
///
/// An overlay rather than a system `.alert` because the raw reason has to be
/// shown in monospace: it is the only diagnostic that reaches a bug report
/// from a release build, and a proportional font turns a hash into something
/// nobody can retype.
struct SendFailureDialog: View {
    let alert: SendAlert
    let theme: Theme
    let onDismiss: () -> Void
    let onRetry: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.35)
                .ignoresSafeArea()
                .onTapGesture(perform: onDismiss)

            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.largeTitle)
                    .foregroundStyle(Theme.errorRed)

                Text("Transaction Failed")
                    .font(.headline)
                    .accessibilityIdentifier("sendFailure.title")

                Text(alert.message)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("sendFailure.message")

                if let detail = alert.truncatedDetail {
                    Text(detail)
                        .font(.caption.monospaced())
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.tertiary)
                        .accessibilityIdentifier("sendFailure.detail")
                }

                ForEach(alert.tips, id: \.self) { tip in
                    Text(tip)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .padding(10)
                        .frame(maxWidth: .infinity)
                        .background(theme.primary.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
                }

                HStack(spacing: 12) {
                    if alert.canRetry {
                        Button("Retry", action: onRetry)
                            .buttonStyle(.bordered)
                            .frame(maxWidth: .infinity)
                            .accessibilityIdentifier("sendFailure.retry")
                    }
                    Button("OK", action: onDismiss)
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("sendFailure.ok")
                }
            }
            .padding(24)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 20))
            .padding(32)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sendFailure.root")
    }
}
