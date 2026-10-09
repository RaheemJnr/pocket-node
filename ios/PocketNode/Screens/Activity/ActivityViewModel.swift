import Foundation
import PocketNodeCore

/// One row as the list draws it: the transaction, plus whether a date header
/// belongs above it and what that header says.
///
/// The grouping is decided here rather than in the row closure because a
/// SwiftUI `ForEach` body is re-evaluated with the index it was built with,
/// against whatever the array holds at that moment. Looking at the previous
/// row meant `items[index - 1]`, and any assembly that shortened or reordered
/// the array while a closure was still live indexed out of range and trapped
/// (`Array._checkSubscript`). Deciding it once, over one array snapshot,
/// removes the question rather than guarding it.
struct ActivityListRow: Identifiable {
    let item: ActivityItem
    let showsGroupHeader: Bool
    let groupLabel: String

    /// Unique within a wallet and network, which is the only scope a page is
    /// ever read in: the shared table's primary key is (txHash, network).
    var id: String { item.record.txHash }
}

/// Turns a page of transactions into rows with their date headers.
///
/// A free function on purpose: it takes everything it needs, touches no state,
/// and so is the one piece of this screen worth testing directly.
enum ActivityGrouping {

    /// Assigns a header to the first row of each date group.
    ///
    /// [items] must be the whole displayed list, not one page: a group can
    /// straddle a page boundary, and deciding per page would print a second
    /// "TODAY" in the middle of the list.
    ///
    /// [now] is taken once for the whole assembly. Two rows either side of
    /// midnight would otherwise be grouped against two different "today"s.
    static func assemble(
        _ items: [ActivityItem],
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> [ActivityListRow] {
        var rows: [ActivityListRow] = []
        rows.reserveCapacity(items.count)
        var previousLabel: String?
        for item in items {
            // A failed transaction is never in a block, so it has no block
            // timestamp and would otherwise sit under "PENDING" next to
            // transactions that are still on their way. It is the opposite of
            // pending: nothing is coming. Its own header says so.
            let label = item.displayState == TxDisplayState.failed
                ? "FAILED"
                : ActivityFormat.dateGroup(
                    blockTimestampHex: item.record.blockTimestampHex,
                    now: now,
                    calendar: calendar
                )
            rows.append(
                ActivityListRow(
                    item: item,
                    showsGroupHeader: label != previousLabel,
                    groupLabel: label
                )
            )
            previousLabel = label
        }
        return rows
    }
}

/// Backs the activity list: which filter is on, which rows are loaded, and
/// whether more of them are on the way.
///
/// Offset paging rather than a cursor, because the shared store pages by
/// offset. The rules are the ones a list needs and nothing more: a filter
/// change starts over at page 0, a page shorter than a full one means the end,
/// and only one page load runs at a time.
@MainActor
@Observable
final class ActivityViewModel {

    /// The loaded transactions, in list order.
    ///
    /// Deliberately `private`: the crash this screen shipped with came from a
    /// view indexing this array from inside a `ForEach` body. Everything the
    /// view needs is derived and exposed below ([rows], [isEmpty],
    /// [isLastLoadedRow]), so the same mistake is now a compile error rather
    /// than a trap at runtime.
    private var items: [ActivityItem] = []

    /// What the list draws: [items] with each row's date header already
    /// decided. Always assembled from the same array in the same statement, so
    /// a row and its header can never disagree.
    private(set) var rows: [ActivityListRow] = []

    /// Whether anything is loaded. The three content states branch on this.
    var isEmpty: Bool { items.isEmpty }

    private(set) var filter: ActivityFilter = ActivityFilter.all

    /// True only for the first load of a filter, which is what the screen shows
    /// a full-height spinner for.
    private(set) var isLoading = false

    /// True while a pull-to-refresh or an appear-triggered history walk runs.
    private(set) var isRefreshing = false

    /// True while a further page is being read, which draws a trailing spinner.
    private(set) var isLoadingMore = false

    /// The message under the list, or nil.
    ///
    /// Two sources, kept apart on purpose. A page read that fails is the
    /// immediate problem and wins; a history walk that fails is the one the
    /// user can do something about (Retry), and it must survive the page
    /// reload that follows it rather than being cleared by a cache read that
    /// happened to succeed.
    var error: String? { pageError ?? refreshError }

    private(set) var pageError: String?
    private(set) var refreshError: String?

    /// False once a page came back short, so the list stops asking.
    private(set) var hasMore = true

    /// The transaction whose detail sheet is up.
    var selected: ActivityItem?

    /// The failed transaction awaiting a retry confirmation.
    var retryCandidate: ActivityItem?

    var network: NetworkType { source.network }

    /// Re-sending a failed transaction. Nil until the send path lands, and
    /// the detail sheet says so rather than offering a button that does nothing.
    ///
    /// Answers the user-facing reason the retry did not go out, or nil when it
    /// did (or the user cancelled, which is an answer rather than a failure).
    /// The detail sheet is gone by the time this returns, so the answer is
    /// the only way a rejected retry reaches the screen; see [retryError].
    let onRetry: RetryHandler?

    typealias RetryHandler = @MainActor (String) async -> String?

    /// Why the last retry from this screen did not go out, or nil. What the
    /// screen's retry alert shows; cleared by ``dismissRetryError()``.
    private(set) var retryError: String?

    /// Whether the detail sheet may offer Retry at all.
    var canRetry: Bool { onRetry != nil }

    private let source: any ActivityPaging
    private let retryDelay: Duration
    private var nextPageIndex = 0
    private var loadTask: Task<Void, Never>?

    /// How many automatic retries the current cold start has spent. See
    /// ``scheduleRetryIfStillEmpty()``.
    private var autoRetries = 0
    private var retryTask: Task<Void, Never>?

    /// The calendar day [rows] were assembled against. See
    /// ``refreshGroupingIfDayChanged(now:calendar:)``.
    private var assembledOnDay: Date?

    /// - Parameter retryDelay: how long the cold-start retry waits between
    ///   attempts. A parameter so a test can drive it to near zero; production
    ///   takes ``defaultRetryDelay``, whose product with
    ///   ``maxAutoRetries`` is the same half-minute `SyncService` gives the
    ///   node to start.
    init(
        source: any ActivityPaging,
        onRetry: RetryHandler? = nil,
        retryDelay: Duration = ActivityViewModel.defaultRetryDelay
    ) {
        self.source = source
        self.onRetry = onRetry
        self.retryDelay = retryDelay
        // A broadcast row changing is the only thing that moves a row between
        // display states without the ledger row changing, so the loaded pages
        // are re-read rather than patched: the join lives in the shared feed
        // and re-reading is how this side stays out of it.
        source.onBroadcastsChanged { [weak self] in
            self?.reloadLoadedPages()
        }
    }

    // MARK: - Loading

    /// First load for the current filter. Idempotent per appearance: a list
    /// that already has rows only refreshes its history.
    func onAppear() {
        if items.isEmpty {
            startOver(showSpinner: true)
        }
        refresh()
    }

    /// Walk the history from the light client, then reload the pages.
    ///
    /// What pull-to-refresh, the error state's Retry and every appearance run.
    /// The walk is the expensive half and the only one that can fail, so a
    /// failure leaves whatever rows are already loaded on screen.
    func refresh() {
        guard !isRefreshing else { return }
        isRefreshing = true
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.source.refreshHistory()
                self.refreshError = nil
            } catch {
                self.refreshError = error.localizedDescription
            }
            self.isRefreshing = false
            // Read BEFORE `startOver`, which empties the array as its first
            // act. Reading it afterwards made the "only while empty" guard
            // below always true, so a wallet that already had rows re-walked
            // its whole history ten times over.
            let hadRows = !self.items.isEmpty
            self.startOver(showSpinner: !hadRows)
            self.scheduleRetryIfStillEmpty(hadRows: hadRows)
        }
    }

    /// Tries the history walk again, a few times, while it is failing and the
    /// list has nothing to show.
    ///
    /// The screen can be opened seconds after launch, before the light client
    /// has started: the shared reader's very first bridge call then answers
    /// null and the walk fails. That is a cold start, not a problem the user
    /// can act on, and leaving "Failed to get transactions" under a Retry
    /// button for the first half-minute of every launch would be telling them
    /// otherwise.
    ///
    /// Bounded and only while empty: a wallet that already has rows keeps them
    /// and the error stays put, and a node that never comes up stops being
    /// asked after ``maxAutoRetries`` so the Retry button means something.
    /// - Parameter hadRows: whether the list had anything on it when the walk
    ///   that just failed started. Not re-read here: by the time this runs the
    ///   array has already been emptied and refilled.
    private func scheduleRetryIfStillEmpty(hadRows: Bool) {
        retryTask?.cancel()
        guard refreshError != nil, !hadRows, autoRetries < Self.maxAutoRetries else {
            if refreshError == nil { autoRetries = 0 }
            return
        }
        autoRetries += 1
        let delay = retryDelay
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self?.refresh()
        }
    }

    /// Switch tabs. A no-op for the filter already on, so tapping the current
    /// tab does not throw the list back to the top.
    func setFilter(_ next: ActivityFilter) {
        guard next != filter else { return }
        filter = next
        startOver(showSpinner: true)
    }

    /// Ask for the next page. Safe to call from the last row's `onAppear`:
    /// it answers nothing while a load is in flight or the end has been seen.
    func loadMore() {
        guard hasMore, !isLoading, !isLoadingMore else { return }
        isLoadingMore = true
        load(pageIndex: nextPageIndex, replacing: false)
    }

    // MARK: - Retry

    /// Re-broadcast a failed transaction and report the outcome.
    ///
    /// The detail sheet dismisses itself as soon as Retry is tapped, so a
    /// failure here (the row is gone, the database refused, the network
    /// rejected the bytes) would otherwise vanish without a word. It lands in
    /// [retryError] instead. A retry that went out moves its row through the
    /// broadcast flow, which re-reads the list on its own.
    func retry(txHash: String) {
        guard let onRetry else { return }
        retryError = nil
        Task { [weak self] in
            let failure = await onRetry(txHash)
            guard let self else { return }
            if let failure {
                self.retryError = failure.isEmpty ? ActivityCopy.retryFailed : failure
            }
        }
    }

    func dismissRetryError() {
        retryError = nil
    }

    // MARK: - Internals

    /// Drop everything and read page 0 again. What a filter change, a refresh
    /// and the first appearance all reduce to.
    private func startOver(showSpinner: Bool) {
        loadTask?.cancel()
        setItems([])
        nextPageIndex = 0
        hasMore = true
        isLoadingMore = false
        isLoading = showSpinner
        load(pageIndex: 0, replacing: true)
    }

    /// Re-read exactly the pages that are already on screen, keeping the
    /// scroll position's worth of rows. Used when a broadcast row changes.
    ///
    /// A `loadMore()` still in flight is cancelled by this, and a cancelled
    /// load leaves its spinner to whoever replaced it. So the replacement
    /// takes the page that load was after into its own read, and clears
    /// [isLoadingMore] when it ends. Without that the trailing spinner stuck
    /// and every later `loadMore()` returned at its guard.
    private func reloadLoadedPages() {
        let pagesLoaded = nextPageIndex
        guard pagesLoaded > 0 else { return }
        let absorbsLoadMore = isLoadingMore
        let pagesToRead = absorbsLoadMore ? pagesLoaded + 1 : pagesLoaded
        loadTask?.cancel()
        let currentFilter = filter
        loadTask = Task { [weak self] in
            guard let self else { return }
            // Same rule as `load`: only the task that is still current owns
            // the spinner. A newer reload re-absorbs the page; a `startOver`
            // clears the flag itself.
            defer {
                if !Task.isCancelled { self.isLoadingMore = false }
            }
            var rebuilt: [ActivityItem] = []
            var lastPageCount = 0
            for index in 0..<pagesToRead {
                guard let page = try? await self.source.page(
                    filter: currentFilter,
                    pageIndex: index
                ) else { return }
                rebuilt.append(contentsOf: page)
                lastPageCount = page.count
            }
            guard !Task.isCancelled, self.filter == currentFilter else { return }
            self.setItems(rebuilt)
            if absorbsLoadMore {
                self.nextPageIndex = pagesToRead
                self.hasMore = lastPageCount >= Self.pageSize
            }
        }
    }

    private func load(pageIndex: Int, replacing: Bool) {
        let currentFilter = filter
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self else { return }
            // Only the task that is still current clears the spinners. A
            // superseded load (a filter switch, a refresh) would otherwise
            // turn them off while its replacement is still running, and the
            // list would flash empty with no indicator.
            defer {
                if !Task.isCancelled {
                    self.isLoading = false
                    self.isLoadingMore = false
                }
            }
            do {
                let page = try await self.source.page(
                    filter: currentFilter,
                    pageIndex: pageIndex
                )
                // A filter switched while this was in flight owns the list now.
                guard !Task.isCancelled, self.filter == currentFilter else { return }
                self.setItems(replacing ? page : self.items + page)
                self.nextPageIndex = pageIndex + 1
                // A short page is the end. The store cannot report a total
                // without a second count query, and this needs no second query.
                self.hasMore = page.count >= Self.pageSize
                self.pageError = nil
            } catch {
                guard !Task.isCancelled else { return }
                self.pageError = error.localizedDescription
            }
        }
    }

    /// The one place [items] and [rows] are written, so they cannot drift.
    private func setItems(
        _ next: [ActivityItem],
        now: Date = Date(),
        calendar: Calendar = .current
    ) {
        items = next
        rows = ActivityGrouping.assemble(next, now: now, calendar: calendar)
        assembledOnDay = calendar.startOfDay(for: now)
    }

    /// Re-decides the date headers if the day has turned since they were last
    /// assembled.
    ///
    /// "TODAY" is a claim about the wall clock, and the list can be on screen
    /// when the clock crosses midnight: without this, yesterday's rows keep a
    /// header that has quietly become a lie. Called from the screen's ticker,
    /// and a no-op on every tick but the one that crosses.
    ///
    /// The ticker only runs while something is in flight, so a list of settled
    /// history left open across midnight is still re-headed by the next
    /// appearance or refresh rather than at the stroke. That is the cheap
    /// half of the problem and this is the half worth paying a comparison for.
    func refreshGroupingIfDayChanged(now: Date, calendar: Calendar = .current) {
        let today = calendar.startOfDay(for: now)
        guard let previous = assembledOnDay, previous != today else { return }
        rows = ActivityGrouping.assemble(items, now: now, calendar: calendar)
        assembledOnDay = today
    }

    /// Whether [item] is the last row currently loaded, which is what the list
    /// uses to ask for the next page.
    ///
    /// By identity rather than by index: an index captured when a row was built
    /// is meaningless a moment later, and comparing a hash against the current
    /// last row is correct whatever the array has become since.
    func isLastLoadedRow(_ item: ActivityItem) -> Bool {
        items.last?.record.txHash == item.record.txHash
    }

    /// The shared `ActivityFeed.PAGE_SIZE`, read off the companion so the two
    /// cannot drift.
    static let pageSize = Int(ActivityFeed.companion.PAGE_SIZE)

    /// Ten tries three seconds apart is half a minute, which is the same
    /// ceiling `SyncService.nodeWaitSeconds` gives the node to come up.
    static let maxAutoRetries = 10
    static let retryDelaySeconds = 3
    static let defaultRetryDelay = Duration.seconds(retryDelaySeconds)
}
