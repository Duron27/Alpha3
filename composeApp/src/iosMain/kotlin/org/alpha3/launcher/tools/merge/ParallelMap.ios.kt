// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

internal actual fun <T, R> parallelMap(items: List<T>, threads: Int, onDone: (Int) -> Unit, f: (T) -> R): List<R> =
    items.mapIndexed { i, item -> f(item).also { onDone(i + 1) } }
