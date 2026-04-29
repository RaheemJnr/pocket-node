package com.rjnr.pocketnode.ui.education

import androidx.annotation.StringRes

data class EducationContent(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val faqAnchor: String?
)
