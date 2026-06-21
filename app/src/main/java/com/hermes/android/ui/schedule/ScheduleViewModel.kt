package com.hermes.android.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.android.data.HermesRepository
import com.hermes.android.data.model.ScheduledTask
import com.hermes.android.schedule.ReminderScheduler
import com.hermes.android.ui.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScheduleUiState(
    val loading: Boolean = true,
    val tasks: List<ScheduledTask> = emptyList(),
    val error: String? = null,
)

/**
 * Backs the Schedule screen: lists the active local Hermes cron jobs and keeps the
 * mirrored Android alarms in sync via [ReminderScheduler]. Cancelling a task
 * deletes the cron job server-side and drops its local alarm.
 */
class ScheduleViewModel(
    private val repository: HermesRepository,
    private val scheduler: ReminderScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.listScheduledTasks()
                .onSuccess { tasks ->
                    withContext(Dispatchers.Default) { scheduler.reconcile(tasks) }
                    _state.update { it.copy(loading = false, tasks = tasks, error = null) }
                }
                .onFailure { err ->
                    _state.update { it.copy(loading = false, error = err.toUserMessage()) }
                }
        }
    }

    /** Deletes a cron job and cancels its local alarm. Optimistic, with rollback. */
    fun cancel(id: String) {
        val removed = _state.value.tasks.firstOrNull { it.id == id }
        _state.update { it.copy(tasks = it.tasks.filterNot { t -> t.id == id }) }
        viewModelScope.launch {
            withContext(Dispatchers.Default) { scheduler.cancel(id) }
            repository.deleteScheduledTask(id)
                .onSuccess { refresh() }
                .onFailure { err ->
                    // The job still exists server-side, so re-arm the alarm we just
                    // cancelled — otherwise the restored row would have no reminder
                    // until the next reconcile.
                    if (removed != null) {
                        withContext(Dispatchers.Default) { scheduler.arm(removed) }
                    }
                    // Restore just the removed row and surface the error (refresh would
                    // clear it and silently re-add the still-present row).
                    _state.update { st ->
                        val tasks = if (removed != null && st.tasks.none { it.id == id }) {
                            (st.tasks + removed).sortedBy { it.nextRunAtMs }
                        } else {
                            st.tasks
                        }
                        st.copy(tasks = tasks, error = err.toUserMessage())
                    }
                }
        }
    }
}
