package org.daslearning.opensms.next

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.daslearning.opensms.next.data.MessageRepository

@HiltViewModel
class MessageHistoryViewModel @Inject constructor(repository: MessageRepository) : ViewModel() {
    val messages = repository.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
