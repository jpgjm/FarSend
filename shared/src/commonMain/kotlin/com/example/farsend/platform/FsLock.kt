package com.example.farsend.platform

/**
 * Non-reentrant lock guarding short sections (for non-suspending code).
 * JVM uses synchronized, iOS pthread_mutex. Coroutine Mutex can't be used from non-suspend functions.
 */
expect class FsLock() {
    fun <T> withLock(block: () -> T): T
}
