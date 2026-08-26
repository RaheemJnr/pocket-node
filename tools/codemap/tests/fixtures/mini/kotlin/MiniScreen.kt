package com.example.mini

import androidx.compose.runtime.Composable

@Composable
fun MiniScreen(viewModel: MiniViewModel) {
    val count = viewModel.refresh()
}
