package com.rjnr.pocketnode.ui.util

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Localizable user-facing message that ViewModels emit without holding
 * a `Context`. Composables resolve via [resolve] (or the suspending
 * `.resolveString(context)`).
 *
 * Two variants:
 *
 *  - [Resource] — `@StringRes` ID with optional positional format args.
 *    Use for every translatable string the VM produces.
 *  - [Raw] — verbatim text that escapes localization (chain error
 *    passthrough, transaction hashes shown inline, etc.). Use only
 *    when the source is genuinely untranslatable.
 *
 * Why a sealed type rather than `@StringRes Int`: most error paths
 * include dynamic content ("Network switch failed: ${e.message}"),
 * which can't be a pure resource ID. Carrying the args alongside the
 * ID keeps the VM stringResource-free and i18n-friendly while still
 * supporting format placeholders.
 *
 * Established by #133 to unblock the translation work in #132.
 */
sealed class UiMessage {
    data class Resource(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiMessage()

    /** Verbatim, non-localizable text. Avoid for new code unless the source is untranslatable. */
    data class Raw(val text: String) : UiMessage()
}

@Composable
fun UiMessage.resolve(): String = when (this) {
    is UiMessage.Resource -> stringResource(id, *args.toTypedArray())
    is UiMessage.Raw -> text
}

/**
 * Non-@Composable resolver. Used by snackbar `showSnackbar` callbacks
 * and other coroutine-context resolvers that can't call `stringResource`.
 *
 * Callers obtain the [android.content.Context] from `LocalContext.current`
 * at the @Composable layer and pass it in. Never call from a ViewModel.
 */
fun UiMessage.resolveString(context: android.content.Context): String = when (this) {
    is UiMessage.Resource ->
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
    is UiMessage.Raw -> text
}
