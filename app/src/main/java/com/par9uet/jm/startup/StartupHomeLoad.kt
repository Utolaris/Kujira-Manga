package com.par9uet.jm.startup

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

/** Includes Home's first requests (and a recommendation failure's fallback) in startup ordering. */
class StartupHomeLoad {
    private val lock = Any()
    private val jobs = mutableSetOf<Job>()
    private var finished = false

    fun track(job: Job) {
        synchronized(lock) {
            if (!finished && !job.isCompleted) {
                jobs += job
                job.invokeOnCompletion { synchronized(lock) { jobs -= job } }
            }
        }
    }

    suspend fun awaitIdle() {
        while (true) {
            val active = synchronized(lock) {
                jobs.toList().also { if (it.isEmpty()) finished = true }
            }
            if (active.isEmpty()) return
            active.joinAll()
        }
    }
}
