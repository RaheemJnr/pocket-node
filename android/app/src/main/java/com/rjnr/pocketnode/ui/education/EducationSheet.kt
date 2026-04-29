package com.rjnr.pocketnode.ui.education

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.centredContentMaxWidth

/**
 * Stateless bottom-sheet help surface. Caller controls visibility via the
 * `topic` parameter — pass null (don't compose) to hide; non-null to show.
 *
 * `onOpenFaq` is invoked with the topic's `faqAnchor` so the caller can
 * navigate to the FAQ screen with that anchor pre-expanded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EducationSheet(
    topic: EducationTopic,
    onDismiss: () -> Unit,
    onOpenFaq: ((String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = centredContentMaxWidth())
                .align(Alignment.CenterHorizontally)
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(topic.titleRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(topic.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (onOpenFaq != null) {
                    TextButton(onClick = { onOpenFaq(topic.faqAnchor) }) {
                        Text(stringResource(R.string.edu_open_faq))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.edu_got_it))
                }
            }
        }
    }
}
