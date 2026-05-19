package com.rjnr.pocketnode.ui.update

import androidx.lifecycle.ViewModel
import com.rjnr.pocketnode.data.update.DownloadState
import com.rjnr.pocketnode.data.update.UpdateDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin Compose-side bridge over the singleton [UpdateDownloader]. Lives at
 * MainScreen scope so the update banner sits above the bottom navigation
 * regardless of which tab is showing.
 *
 * Why a ViewModel and not direct singleton injection: Compose composables
 * cannot inject Hilt singletons directly; the standard way to surface a
 * @Singleton flow into a composable is via a Hilt ViewModel. This class
 * adds zero state of its own.
 */
@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    private val updateDownloader: UpdateDownloader,
) : ViewModel() {

    val state: StateFlow<DownloadState> = updateDownloader.state

    /** User tapped the "Update" CTA on a finished download. */
    fun installNow() = updateDownloader.installNow()

    /** User tapped Cancel on an in-progress download. */
    fun cancel() = updateDownloader.cancel()

    /** User tapped Later/Dismiss to hide a terminal banner. */
    fun dismiss() = updateDownloader.resetState()

    /** User tapped "Tap to retry" on a failed download. */
    fun retry() = updateDownloader.retry()

    /**
     * Called from MainScreen's lifecycle observer when the activity resumes.
     * If we were Installing and the user came back (cancelled / install
     * conflict / etc.) we reset state and drop the on-disk APK.
     */
    fun onActivityResumed() = updateDownloader.onInstallerReturned()
}
