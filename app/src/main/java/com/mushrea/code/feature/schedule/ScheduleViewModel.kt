package com.mushrea.code.feature.schedule

import androidx.lifecycle.ViewModel
import com.mushrea.code.data.schedule.Schedule
import com.mushrea.code.data.schedule.ScheduleRepository
import com.mushrea.code.data.schedule.ScheduleRun

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val manager: ScheduleManager,
) : ViewModel() {
    val schedules = repository.schedules
    val runs = repository.runs

    fun create(schedule: Schedule) {
        repository.upsert(schedule)
        manager.rescheduleAll()
    }

    fun update(schedule: Schedule) {
        repository.upsert(schedule)
        manager.rescheduleAll()
    }

    fun delete(scheduleId: String) {
        repository.delete(scheduleId)
        manager.cancel(scheduleId)
        manager.rescheduleAll()
    }

    fun setEnabled(
        scheduleId: String,
        enabled: Boolean,
    ) {
        repository.setEnabled(scheduleId, enabled)
        manager.rescheduleAll()
    }

    fun runNow(scheduleId: String) {
        manager.runNow(scheduleId)
    }

    fun runsFor(scheduleId: String): List<ScheduleRun> = repository.runs.value.filter { it.scheduleId == scheduleId }
}
