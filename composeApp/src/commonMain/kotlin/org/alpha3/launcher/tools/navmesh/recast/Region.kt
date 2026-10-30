// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of the watershed parts of Recast's RecastRegion.cpp (zlib licence, Copyright (c) 2009-2010 Mikko Mononen).
package org.alpha3.launcher.tools.navmesh.recast

private class LevelStack(capacity: Int = 256) {
    var x = IntArray(capacity)
    var y = IntArray(capacity)
    var index = IntArray(capacity)
    var size = 0

    fun clear() { size = 0 }

    fun push(px: Int, py: Int, pi: Int) {
        if (size == x.size) {
            x = x.copyOf(size * 2); y = y.copyOf(size * 2); index = index.copyOf(size * 2)
        }
        x[size] = px; y[size] = py; index[size] = pi
        size++
    }
}

private fun neighborSpan(chf: CompactHeightfield, x: Int, y: Int, s: Int, dir: Int): Int =
    chf.cellIndex[(x + rcGetDirOffsetX(dir)) + (y + rcGetDirOffsetY(dir)) * chf.width] + chf.getCon(s, dir)

private fun calculateDistanceField(chf: CompactHeightfield, src: IntArray): Int {
    val n = chf.spanCount
    val nbr = chf.nbr
    val areas = chf.areas
    val cellIndex = chf.cellIndex
    val cellCount = chf.cellCount
    val cells = chf.width * chf.height
    src.fill(0xffff, 0, n)

    for (i in 0 until n) {
        val area = areas[i]
        val b = i * 4
        if (!(nbr[b] >= 0 && areas[nbr[b]] == area && nbr[b + 1] >= 0 && areas[nbr[b + 1]] == area &&
                nbr[b + 2] >= 0 && areas[nbr[b + 2]] == area && nbr[b + 3] >= 0 && areas[nbr[b + 3]] == area)) src[i] = 0
    }

    for (i in 0 until n) {
        relaxDist(src, nbr, i, 0, 3)
        relaxDist(src, nbr, i, 3, 2)
    }
    for (c in cells - 1 downTo 0) {
        val end = cellIndex[c] + cellCount[c]
        for (i in cellIndex[c] until end) {
            relaxDist(src, nbr, i, 2, 1)
            relaxDist(src, nbr, i, 1, 0)
        }
    }

    var maxDist = 0
    for (i in 0 until n) if (src[i] > maxDist) maxDist = src[i]
    return maxDist
}

private fun relaxDist(src: IntArray, nbr: IntArray, i: Int, d1: Int, d2: Int) {
    val ai = nbr[i * 4 + d1]
    if (ai < 0) return
    if (src[ai] + 2 < src[i]) src[i] = src[ai] + 2
    val aai = nbr[ai * 4 + d2]
    if (aai >= 0 && src[aai] + 3 < src[i]) src[i] = src[aai] + 3
}

private fun boxBlur(chf: CompactHeightfield, thr0: Int, src: IntArray, dst: IntArray) {
    val thr = thr0 * 2
    val nbr = chf.nbr
    for (i in 0 until chf.spanCount) {
        val cd = src[i]
        if (cd <= thr) { dst[i] = cd; continue }
        var d = cd
        for (dir in 0 until 4) {
            val ai = nbr[i * 4 + dir]
            if (ai >= 0) {
                d += src[ai]
                val ai2 = nbr[ai * 4 + ((dir + 1) and 3)]
                d += if (ai2 >= 0) src[ai2] else cd
            } else {
                d += cd * 2
            }
        }
        dst[i] = ((d + 5) / 9) and 0xffff
    }
}

internal fun rcBuildDistanceField(chf: CompactHeightfield) {
    val src = IntArray(chf.spanCount)
    val dst = IntArray(chf.spanCount)
    chf.maxDistance = calculateDistanceField(chf, src)
    boxBlur(chf, 1, src, dst)
    chf.dist = dst
}

private fun floodRegion(x: Int, y: Int, i: Int, level: Int, r: Int, chf: CompactHeightfield, srcReg: IntArray, srcDist: IntArray, stack: LevelStack): Boolean {
    val w = chf.width
    val area = chf.areas[i]
    stack.clear()
    stack.push(x, y, i)
    srcReg[i] = r
    srcDist[i] = 0
    val lev = if (level >= 2) level - 2 else 0
    var count = 0

    while (stack.size > 0) {
        stack.size--
        val cx = stack.x[stack.size]
        val cy = stack.y[stack.size]
        val ci = stack.index[stack.size]

        var ar = 0
        for (dir in 0 until 4) {
            if (chf.getCon(ci, dir) != RC_NOT_CONNECTED) {
                val ax = cx + rcGetDirOffsetX(dir)
                val ay = cy + rcGetDirOffsetY(dir)
                val ai = chf.cellIndex[ax + ay * w] + chf.getCon(ci, dir)
                if (chf.areas[ai] != area) continue
                val nr = srcReg[ai]
                if (nr and RC_BORDER_REG != 0) continue
                if (nr != 0 && nr != r) { ar = nr; break }
                val dir2 = (dir + 1) and 3
                if (chf.getCon(ai, dir2) != RC_NOT_CONNECTED) {
                    val ai2 = chf.cellIndex[(ax + rcGetDirOffsetX(dir2)) + (ay + rcGetDirOffsetY(dir2)) * w] + chf.getCon(ai, dir2)
                    if (chf.areas[ai2] != area) continue
                    val nr2 = srcReg[ai2]
                    if (nr2 != 0 && nr2 != r) { ar = nr2; break }
                }
            }
        }
        if (ar != 0) {
            srcReg[ci] = 0
            continue
        }
        count++

        for (dir in 0 until 4) {
            if (chf.getCon(ci, dir) != RC_NOT_CONNECTED) {
                val ax = cx + rcGetDirOffsetX(dir)
                val ay = cy + rcGetDirOffsetY(dir)
                val ai = chf.cellIndex[ax + ay * w] + chf.getCon(ci, dir)
                if (chf.areas[ai] != area) continue
                if (chf.dist[ai] >= lev && srcReg[ai] == 0) {
                    srcReg[ai] = r
                    srcDist[ai] = 0
                    stack.push(ax, ay, ai)
                }
            }
        }
    }
    return count > 0
}

private fun expandRegions(maxIter: Int, level: Int, chf: CompactHeightfield, srcReg: IntArray, srcDist: IntArray, stack: LevelStack, fillStack: Boolean) {
    val w = chf.width
    val h = chf.height
    if (fillStack) {
        stack.clear()
        for (y in 0 until h) for (x in 0 until w) {
            val c = x + y * w
            for (i in chf.cellIndex[c] until chf.cellIndex[c] + chf.cellCount[c]) {
                if (chf.dist[i] >= level && srcReg[i] == 0 && chf.areas[i] != RC_NULL_AREA) stack.push(x, y, i)
            }
        }
    } else {
        for (j in 0 until stack.size) if (srcReg[stack.index[j]] != 0) stack.index[j] = -1
    }

    val nbr = chf.nbr
    val areasArr = chf.areas
    var dirtyIndex = IntArray(64)
    var dirtyRegion = IntArray(64)
    var dirtyDist = IntArray(64)
    var iter = 0
    while (stack.size > 0) {
        var failed = 0
        var dirty = 0
        for (j in 0 until stack.size) {
            val x = stack.x[j]
            val y = stack.y[j]
            val i = stack.index[j]
            if (i < 0) { failed++; continue }
            var r = srcReg[i]
            var d2 = 0xffff
            val area = chf.areas[i]
            for (dir in 0 until 4) {
                val ai = nbr[i * 4 + dir]
                if (ai < 0 || areasArr[ai] != area) continue
                val reg = srcReg[ai]
                if (reg > 0 && (reg and RC_BORDER_REG) == 0) {
                    if (srcDist[ai] + 2 < d2) {
                        r = reg
                        d2 = (srcDist[ai] + 2) and 0xffff
                    }
                }
            }
            if (r != 0) {
                stack.index[j] = -1
                if (dirty == dirtyIndex.size) {
                    dirtyIndex = dirtyIndex.copyOf(dirty * 2); dirtyRegion = dirtyRegion.copyOf(dirty * 2); dirtyDist = dirtyDist.copyOf(dirty * 2)
                }
                dirtyIndex[dirty] = i; dirtyRegion[dirty] = r; dirtyDist[dirty] = d2
                dirty++
            } else {
                failed++
            }
        }
        for (k in 0 until dirty) {
            srcReg[dirtyIndex[k]] = dirtyRegion[k]
            srcDist[dirtyIndex[k]] = dirtyDist[k]
        }
        if (failed == stack.size) break
        if (level > 0) {
            ++iter
            if (iter >= maxIter) break
        }
    }
}

private fun sortCellsByLevel(startLevel0: Int, chf: CompactHeightfield, srcReg: IntArray, nbStacks: Int, stacks: Array<LevelStack>, logLevelsPerStack: Int) {
    val w = chf.width
    val h = chf.height
    val startLevel = startLevel0 shr logLevelsPerStack
    for (j in 0 until nbStacks) stacks[j].clear()
    for (y in 0 until h) for (x in 0 until w) {
        val c = x + y * w
        for (i in chf.cellIndex[c] until chf.cellIndex[c] + chf.cellCount[c]) {
            if (chf.areas[i] == RC_NULL_AREA || srcReg[i] != 0) continue
            val level = chf.dist[i] shr logLevelsPerStack
            var sId = startLevel - level
            if (sId >= nbStacks) continue
            if (sId < 0) sId = 0
            stacks[sId].push(x, y, i)
        }
    }
}

private fun appendStacks(src: LevelStack, dst: LevelStack, srcReg: IntArray) {
    for (j in 0 until src.size) {
        val i = src.index[j]
        if (i < 0 || srcReg[i] != 0) continue
        dst.push(src.x[j], src.y[j], i)
    }
}

private class Region(var id: Int) {
    var spanCount = 0
    var areaType = 0
    var remap = false
    var visited = false
    var overlap = false
    var connectsToBorder = false
    var connections = ArrayList<Int>()
    var floors = ArrayList<Int>()
}

private fun removeAdjacentNeighbours(reg: Region) {
    var i = 0
    while (i < reg.connections.size && reg.connections.size > 1) {
        val ni = (i + 1) % reg.connections.size
        if (reg.connections[i] == reg.connections[ni]) reg.connections.removeAt(i) else ++i
    }
}

private fun replaceNeighbour(reg: Region, oldId: Int, newId: Int) {
    var neiChanged = false
    for (i in reg.connections.indices) if (reg.connections[i] == oldId) { reg.connections[i] = newId; neiChanged = true }
    for (i in reg.floors.indices) if (reg.floors[i] == oldId) reg.floors[i] = newId
    if (neiChanged) removeAdjacentNeighbours(reg)
}

private fun canMergeWithRegion(a: Region, b: Region): Boolean {
    if (a.areaType != b.areaType) return false
    var n = 0
    for (c in a.connections) if (c == b.id) n++
    if (n > 1) return false
    for (f in a.floors) if (f == b.id) return false
    return true
}

private fun addUniqueFloorRegion(reg: Region, n: Int) {
    if (n !in reg.floors) reg.floors.add(n)
}

private fun mergeRegions(a: Region, b: Region): Boolean {
    val aid = a.id
    val bid = b.id
    val acon = ArrayList(a.connections)
    val bcon = b.connections
    val insa = acon.indexOf(bid)
    if (insa == -1) return false
    val insb = bcon.indexOf(aid)
    if (insb == -1) return false
    a.connections = ArrayList()
    val na = acon.size
    for (i in 0 until na - 1) a.connections.add(acon[(insa + 1 + i) % na])
    val nb = bcon.size
    for (i in 0 until nb - 1) a.connections.add(bcon[(insb + 1 + i) % nb])
    removeAdjacentNeighbours(a)
    for (f in b.floors) addUniqueFloorRegion(a, f)
    a.spanCount += b.spanCount
    b.spanCount = 0
    b.connections = ArrayList()
    return true
}

private fun isRegionConnectedToBorder(reg: Region): Boolean = reg.connections.any { it == 0 }

private fun isSolidEdge(chf: CompactHeightfield, srcReg: IntArray, x: Int, y: Int, i: Int, dir: Int): Boolean {
    var r = 0
    if (chf.getCon(i, dir) != RC_NOT_CONNECTED) r = srcReg[neighborSpan(chf, x, y, i, dir)]
    return r != srcReg[i]
}

private fun walkContour(x0: Int, y0: Int, i0: Int, dir0: Int, chf: CompactHeightfield, srcReg: IntArray, cont: ArrayList<Int>) {
    var x = x0; var y = y0; var i = i0; var dir = dir0
    val startDir = dir
    val starti = i
    var curReg = 0
    if (chf.getCon(i, dir) != RC_NOT_CONNECTED) curReg = srcReg[neighborSpan(chf, x, y, i, dir)]
    cont.add(curReg)

    var iter = 0
    while (++iter < 40000) {
        if (isSolidEdge(chf, srcReg, x, y, i, dir)) {
            var r = 0
            if (chf.getCon(i, dir) != RC_NOT_CONNECTED) r = srcReg[neighborSpan(chf, x, y, i, dir)]
            if (r != curReg) { curReg = r; cont.add(curReg) }
            dir = (dir + 1) and 3
        } else {
            var ni = -1
            val nx = x + rcGetDirOffsetX(dir)
            val ny = y + rcGetDirOffsetY(dir)
            if (chf.getCon(i, dir) != RC_NOT_CONNECTED) ni = chf.cellIndex[nx + ny * chf.width] + chf.getCon(i, dir)
            if (ni == -1) return
            x = nx; y = ny; i = ni
            dir = (dir + 3) and 3
        }
        if (starti == i && startDir == dir) break
    }

    if (cont.size > 1) {
        var j = 0
        while (j < cont.size) {
            val nj = (j + 1) % cont.size
            if (cont[j] == cont[nj]) cont.removeAt(j) else ++j
        }
    }
}

private fun mergeAndFilterRegions(minRegionArea: Int, mergeRegionSize: Int, maxRegionId: Int, chf: CompactHeightfield, srcReg: IntArray): Int {
    val w = chf.width
    val h = chf.height
    val nreg = maxRegionId + 1
    val regions = Array(nreg) { Region(it) }

    for (y in 0 until h) for (x in 0 until w) {
        val c = x + y * w
        val ni = chf.cellIndex[c] + chf.cellCount[c]
        for (i in chf.cellIndex[c] until ni) {
            val r = srcReg[i]
            if (r == 0 || r >= nreg) continue
            val reg = regions[r]
            reg.spanCount++
            for (j in chf.cellIndex[c] until ni) {
                if (i == j) continue
                val floorId = srcReg[j]
                if (floorId == 0 || floorId >= nreg) continue
                if (floorId == r) reg.overlap = true
                addUniqueFloorRegion(reg, floorId)
            }
            if (reg.connections.size > 0) continue
            reg.areaType = chf.areas[i]
            var ndir = -1
            for (dir in 0 until 4) {
                if (isSolidEdge(chf, srcReg, x, y, i, dir)) { ndir = dir; break }
            }
            if (ndir != -1) walkContour(x, y, i, ndir, chf, srcReg, reg.connections)
        }
    }

    val stack = ArrayList<Int>()
    val trace = ArrayList<Int>()
    for (i in 0 until nreg) {
        val reg = regions[i]
        if (reg.id == 0 || (reg.id and RC_BORDER_REG) != 0) continue
        if (reg.spanCount == 0) continue
        if (reg.visited) continue
        var connectsToBorder = false
        var spanCount = 0
        stack.clear()
        trace.clear()
        reg.visited = true
        stack.add(i)
        while (stack.isNotEmpty()) {
            val ri = stack.removeAt(stack.size - 1)
            val creg = regions[ri]
            spanCount += creg.spanCount
            trace.add(ri)
            for (j in creg.connections.indices) {
                if ((creg.connections[j] and RC_BORDER_REG) != 0) { connectsToBorder = true; continue }
                val neireg = regions[creg.connections[j]]
                if (neireg.visited) continue
                if (neireg.id == 0 || (neireg.id and RC_BORDER_REG) != 0) continue
                stack.add(neireg.id)
                neireg.visited = true
            }
        }
        if (spanCount < minRegionArea && !connectsToBorder) {
            for (t in trace) { regions[t].spanCount = 0; regions[t].id = 0 }
        }
    }

    var mergeCount: Int
    do {
        mergeCount = 0
        for (i in 0 until nreg) {
            val reg = regions[i]
            if (reg.id == 0 || (reg.id and RC_BORDER_REG) != 0) continue
            if (reg.overlap) continue
            if (reg.spanCount == 0) continue
            if (reg.spanCount > mergeRegionSize && isRegionConnectedToBorder(reg)) continue
            var smallest = 0xfffffff
            var mergeId = reg.id
            for (j in reg.connections.indices) {
                if ((reg.connections[j] and RC_BORDER_REG) != 0) continue
                val mreg = regions[reg.connections[j]]
                if (mreg.id == 0 || (mreg.id and RC_BORDER_REG) != 0 || mreg.overlap) continue
                if (mreg.spanCount < smallest && canMergeWithRegion(reg, mreg) && canMergeWithRegion(mreg, reg)) {
                    smallest = mreg.spanCount
                    mergeId = mreg.id
                }
            }
            if (mergeId != reg.id) {
                val oldId = reg.id
                val target = regions[mergeId]
                if (mergeRegions(target, reg)) {
                    for (j in 0 until nreg) {
                        if (regions[j].id == 0 || (regions[j].id and RC_BORDER_REG) != 0) continue
                        if (regions[j].id == oldId) regions[j].id = mergeId
                        replaceNeighbour(regions[j], oldId, mergeId)
                    }
                    mergeCount++
                }
            }
        }
    } while (mergeCount > 0)

    for (i in 0 until nreg) {
        regions[i].remap = false
        if (regions[i].id == 0) continue
        if ((regions[i].id and RC_BORDER_REG) != 0) continue
        regions[i].remap = true
    }
    var regIdGen = 0
    for (i in 0 until nreg) {
        if (!regions[i].remap) continue
        val oldId = regions[i].id
        val newId = ++regIdGen
        for (j in i until nreg) {
            if (regions[j].id == oldId) { regions[j].id = newId; regions[j].remap = false }
        }
    }
    for (i in 0 until chf.spanCount) {
        if ((srcReg[i] and RC_BORDER_REG) == 0) srcReg[i] = regions[srcReg[i]].id
    }
    return regIdGen
}

private fun paintRectRegion(minx: Int, maxx: Int, miny: Int, maxy: Int, regId: Int, chf: CompactHeightfield, srcReg: IntArray) {
    val w = chf.width
    for (y in miny until maxy) for (x in minx until maxx) {
        val c = x + y * w
        for (i in chf.cellIndex[c] until chf.cellIndex[c] + chf.cellCount[c]) if (chf.areas[i] != RC_NULL_AREA) srcReg[i] = regId
    }
}

internal fun rcBuildRegions(chf: CompactHeightfield, borderSize: Int, minRegionArea: Int, mergeRegionArea: Int): Boolean {
    val w = chf.width
    val h = chf.height
    val nbStacks = 8
    val lvlStacks = Array(nbStacks) { LevelStack() }
    val stack = LevelStack()
    val srcReg = IntArray(chf.spanCount)
    val srcDist = IntArray(chf.spanCount)

    var regionId = 1
    var level = (chf.maxDistance + 1) and 1.inv()
    val expandIters = 8

    if (borderSize > 0) {
        val bw = minOf(w, borderSize)
        val bh = minOf(h, borderSize)
        paintRectRegion(0, bw, 0, h, regionId or RC_BORDER_REG, chf, srcReg); regionId++
        paintRectRegion(w - bw, w, 0, h, regionId or RC_BORDER_REG, chf, srcReg); regionId++
        paintRectRegion(0, w, 0, bh, regionId or RC_BORDER_REG, chf, srcReg); regionId++
        paintRectRegion(0, w, h - bh, h, regionId or RC_BORDER_REG, chf, srcReg); regionId++
    }
    chf.borderSize = borderSize

    var sId = -1
    while (level > 0) {
        level = if (level >= 2) level - 2 else 0
        sId = (sId + 1) and (nbStacks - 1)
        if (sId == 0) sortCellsByLevel(level, chf, srcReg, nbStacks, lvlStacks, 1)
        else appendStacks(lvlStacks[sId - 1], lvlStacks[sId], srcReg)

        expandRegions(expandIters, level, chf, srcReg, srcDist, lvlStacks[sId], false)

        val cur = lvlStacks[sId]
        for (j in 0 until cur.size) {
            val i = cur.index[j]
            if (i >= 0 && srcReg[i] == 0) {
                if (floodRegion(cur.x[j], cur.y[j], i, level, regionId, chf, srcReg, srcDist, stack)) {
                    if (regionId == 0xffff) return false
                    regionId++
                }
            }
        }
    }

    expandRegions(expandIters * 8, 0, chf, srcReg, srcDist, stack, true)

    chf.maxRegions = mergeAndFilterRegions(minRegionArea, mergeRegionArea, regionId, chf, srcReg)
    for (i in 0 until chf.spanCount) chf.reg[i] = srcReg[i]
    return true
}
