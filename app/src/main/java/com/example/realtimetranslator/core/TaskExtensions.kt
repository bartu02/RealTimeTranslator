package com.example.realtimetranslator.core

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until this task finishes, instead of blocking a thread on
 * `Tasks.await`. Written by hand so the project does not need to take on
 * kotlinx-coroutines-play-services for two call sites.
 */
suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        when {
            task.isCanceled -> continuation.cancel()
            task.isSuccessful -> {
                @Suppress("UNCHECKED_CAST")
                continuation.resume(task.result as T)
            }
            else -> continuation.resumeWithException(
                task.exception ?: IllegalStateException("Task failed without an exception")
            )
        }
    }
}

/** Same, for tasks whose result is irrelevant (or `Void`, which would be null). */
suspend fun Task<*>.awaitCompletion() = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        when {
            task.isCanceled -> continuation.cancel()
            task.isSuccessful -> continuation.resume(Unit)
            else -> continuation.resumeWithException(
                task.exception ?: IllegalStateException("Task failed without an exception")
            )
        }
    }
}
