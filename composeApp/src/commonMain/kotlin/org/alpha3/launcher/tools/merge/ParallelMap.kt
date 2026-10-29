// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

/**
 * Applies [f] to every item on up to [threads] threads and returns the results in the order of [items]. [onDone] is
 * called on the calling thread with the number finished so far, as results arrive. An exception thrown by [f] is
 * rethrown here.
 */
internal expect fun <T, R> parallelMap(items: List<T>, threads: Int, onDone: (Int) -> Unit = {}, f: (T) -> R): List<R>
