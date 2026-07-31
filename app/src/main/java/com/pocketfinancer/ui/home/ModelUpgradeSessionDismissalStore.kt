package com.pocketfinancer.ui.home

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps upgrade dismissals only for the lifetime of this application process. */
@Singleton
class ModelUpgradeSessionDismissalStore @Inject constructor() {
    private val _dismissedTierIds = MutableStateFlow<Set<String>>(emptySet())
    val dismissedTierIds: StateFlow<Set<String>> = _dismissedTierIds.asStateFlow()

    fun dismiss(tierId: String) {
        _dismissedTierIds.value = _dismissedTierIds.value + tierId
    }
}
