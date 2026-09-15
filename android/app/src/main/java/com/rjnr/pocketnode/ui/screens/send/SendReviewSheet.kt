package com.rjnr.pocketnode.ui.screens.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.core.format.formatCkbTrimmed
import com.rjnr.pocketnode.ui.util.truncateAddress
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Last stop before a broadcast (#490).
 *
 * The send flow used to go straight from the Send button to the network
 * whenever "Authenticate before sending" was off — the only thing standing
 * between a typo and a final transaction was the "Save to contacts?" dialog,
 * which appears *after* the broadcast. This sheet always appears, and the auth
 * prompt (PIN, biometric, or the V2 CryptoObject unlock) follows Confirm
 * rather than substituting for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendReviewSheet(
    review: SendReview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = com.rjnr.pocketnode.ui.util.centredContentMaxWidth())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.send_review_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.send_review_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            if (review.isBulk) {
                ReviewRow(
                    label = stringResource(R.string.send_review_recipients),
                    value = pluralStringResource(
                        R.plurals.send_review_recipient_count,
                        review.recipientCount,
                        review.recipientCount,
                    ),
                )
            } else {
                ReviewRow(
                    label = stringResource(R.string.send_review_recipient),
                    value = review.recipientName ?: review.recipientAddress.truncateAddress(),
                    // The name answers "who"; the address answers "exactly
                    // which" — a review that hides one of them is not a review.
                    secondary = review.recipientName?.let { review.recipientAddress.truncateAddress() },
                    monospaceValue = review.recipientName == null,
                )
            }

            Spacer(Modifier.height(12.dp))
            ReviewRow(
                label = stringResource(R.string.send_review_amount),
                value = stringResource(R.string.send_review_ckb, formatReviewCkb(review.amountShannons)),
            )
            Spacer(Modifier.height(12.dp))
            ReviewRow(
                label = stringResource(R.string.send_review_fee),
                // Fees use the shared trimmed formatter so this string is
                // character-identical to the "Network fee" the transaction
                // detail sheet shows for the same send (#497). Amount and
                // total keep formatReviewCkb's 2-decimal minimum.
                value = stringResource(
                    R.string.send_review_ckb,
                    formatCkbTrimmed(review.feeShannons),
                ),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            ReviewRow(
                label = stringResource(R.string.send_review_total),
                value = stringResource(R.string.send_review_ckb, formatReviewCkb(review.totalShannons)),
                emphasised = true,
            )

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.send_review_cancel))
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.send_review_confirm))
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(
    label: String,
    value: String,
    secondary: String? = null,
    monospaceValue: Boolean = false,
    emphasised: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = value,
                style = if (emphasised) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Medium,
                fontFamily = if (monospaceValue) FontFamily.Monospace else null,
                textAlign = TextAlign.End,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * Shannons as CKB with 2-8 decimals: enough to show a 0.00001 CKB fee without
 * padding a whole-number amount with six zeroes. Integer-exact via BigDecimal
 * — Double loses shannon precision above ~90M CKB (#321).
 */
internal fun formatReviewCkb(shannons: Long): String {
    val ckb = BigDecimal(shannons).divide(BigDecimal(100_000_000))
    val trimmed = ckb.setScale(8, RoundingMode.DOWN).stripTrailingZeros()
    val decimals = trimmed.scale().coerceIn(2, 8)
    return String.format(Locale.US, "%,.${decimals}f", trimmed)
}
