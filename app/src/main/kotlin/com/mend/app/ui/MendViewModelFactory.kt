package com.mend.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.compose.runtime.Composable
import com.mend.app.MendApplication

/**
 * Minimal manual dependency wiring.
 *
 * A DI framework is not carrying its weight at two modules and one
 * repository; when the graph justifies one, this is the single place that has
 * to change.
 */
inline fun <reified VM : androidx.lifecycle.ViewModel> mendViewModelFactory(
    crossinline create: (MendApplication) -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras,
    ): T {
        val app = checkNotNull(extras[APPLICATION_KEY]) { "Application missing from CreationExtras" }
        @Suppress("UNCHECKED_CAST")
        return create(app as MendApplication) as T
    }
}

@Composable
inline fun <reified VM : androidx.lifecycle.ViewModel> mendViewModel(
    noinline create: (MendApplication) -> VM,
): VM = viewModel(factory = mendViewModelFactory(create))
