// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal actual fun <T, R> parallelMap(items: List<T>, threads: Int, onDone: (Int) -> Unit, f: (T) -> R): List<R> {
    if (items.isEmpty()) return emptyList()
    val pool = Executors.newFixedThreadPool(threads.coerceIn(1, items.size))
    try {
        val completion = ExecutorCompletionService<Unit>(pool)
        val results = arrayOfNulls<Any?>(items.size)
        val futures = ArrayList<Future<Unit>>()
        for ((i, item) in items.withIndex()) futures.add(completion.submit(Callable { results[i] = f(item) }))
        for (n in 1..items.size) {
            try {
                completion.take().get()
            } catch (e: java.util.concurrent.ExecutionException) {
                futures.forEach { it.cancel(true) }
                throw e.cause ?: e
            }
            onDone(n)
        }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<R>
    } finally {
        pool.shutdownNow()
    }
}
