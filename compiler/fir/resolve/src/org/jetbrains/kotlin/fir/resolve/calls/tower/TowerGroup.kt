/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower

sealed class TowerGroupKind(val index: Byte) : Comparable<TowerGroupKind> {
    abstract class WithDepth(index: Byte, val depth: Int) : TowerGroupKind(index) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as WithDepth

            if (index != other.index) return false
            if (depth != other.depth) return false

            return true
        }

        override fun hashCode(): Int {
            return 31 * depth + index
        }
    }

    object Start : TowerGroupKind(0b0)

    object ClassifierPrioritized : TowerGroupKind(1)

    object Qualifier : TowerGroupKind(2)

    object Classifier : TowerGroupKind(3)

    class TopPrioritized(depth: Int) : WithDepth(4, depth)

    object Member : TowerGroupKind(5)

    class Local(depth: Int) : WithDepth(6, depth)

    class ImplicitOrNonLocal(depth: Int, val kindForDebugSake: String) : WithDepth(7, depth)

    object InvokeExtension : TowerGroupKind(8)

    object QualifierValue : TowerGroupKind(9)

    object Last : TowerGroupKind(0b1111)

    override fun compareTo(other: TowerGroupKind): Int {
        val indexResult = index.compareTo(other.index)
        if (indexResult != 0) return indexResult
        if (this is WithDepth && other is WithDepth) {
            return depth.compareTo(other.depth)
        }
        return 0
    }

    @Suppress("FunctionName")
    companion object {
        // These two groups intentionally have the same priority
        fun Implicit(depth: Int): TowerGroupKind = ImplicitOrNonLocal(depth, "Implicit")
        fun NonLocal(depth: Int): TowerGroupKind = ImplicitOrNonLocal(depth, "NonLocal")
    }
}

@Suppress("FunctionName", "unused", "PropertyName")
class TowerGroup
private constructor(
    private val code: Long,
    private val debugKinds: Array<TowerGroupKind>,
    private val invokeResolvePriority: InvokeResolvePriority = InvokeResolvePriority.NONE
) : Comparable<TowerGroup> {
    companion object {

        private const val KIND_MASK = 0b1111
        private val KIND_SIZE_BITS: Int = Integer.bitCount(KIND_MASK)
        private const val DEPTH_MASK = 0b1111111111
        private val DEPTH_SIZE_BITS: Int = Integer.bitCount(DEPTH_MASK)
        private const val USED_BITS_MASK: Long = 0b111111 // max size 64 bits
        private const val TOTAL_BITS = 64
        private val USABLE_BITS = java.lang.Long.numberOfLeadingZeros(USED_BITS_MASK)

        private val EMPTY_KIND_ARRAY = emptyArray<TowerGroupKind>()

        private const val DEBUG = false // enables tower group debugging

        private fun appendDebugKind(kinds: Array<TowerGroupKind>, kind: TowerGroupKind): Array<TowerGroupKind> {
            return if (DEBUG) {
                kinds + kind
            } else {
                EMPTY_KIND_ARRAY
            }
        }

        private fun debugKindArrayOf(kind: TowerGroupKind): Array<TowerGroupKind> {
            return if (DEBUG) {
                arrayOf(kind)
            } else {
                EMPTY_KIND_ARRAY
            }
        }

        /*
            KKKK.....UUUUUU
            KKKKDDDDDDDDDD.....UUUUUU
            KKKK....000100
            KKKKKKKK....001000

            Start.Start > Start
            00000000...001000 > 0000...000100
         */
        private fun subscript(code: Long, kind: TowerGroupKind): Long {
            val usedBits = (code and USED_BITS_MASK).toInt()
            return when (kind) {
                is TowerGroupKind.WithDepth -> {
                    val kindUsedBits = usedBits + KIND_SIZE_BITS
                    val depthUsedBits = kindUsedBits + DEPTH_SIZE_BITS
                    require(kind.depth <= DEPTH_MASK) {
                        "Depth overflow: requested: ${kind.depth}, allowed: $DEPTH_MASK"
                    }
                    require(depthUsedBits <= USABLE_BITS) {
                        "BitGroup overflow: newUsedBits: $depthUsedBits, original: ${code.toULong().toString(2)}, usedBits: $usedBits"
                    }

                    (code or kind.index.toLong().shl(TOTAL_BITS - kindUsedBits)
                            or kind.depth.toLong().shl(TOTAL_BITS - depthUsedBits)
                            or depthUsedBits.toLong())
                }
                else -> {
                    val usesBits = usedBits + KIND_SIZE_BITS

                    require(usesBits <= USABLE_BITS)
                    code or kind.index.toLong().shl(TOTAL_BITS - usesBits) or usesBits.toLong()
                }
            }
        }

        private fun kindOf(kind: TowerGroupKind): TowerGroup {
            return TowerGroup(subscript(0, kind), debugKindArrayOf(kind))
        }

        val EmptyRoot = TowerGroup(0, EMPTY_KIND_ARRAY)

        val Start = kindOf(TowerGroupKind.Start)

        val ClassifierPrioritized = kindOf(TowerGroupKind.ClassifierPrioritized)

        val Qualifier = kindOf(TowerGroupKind.Qualifier)

        val Classifier = kindOf(TowerGroupKind.Classifier)

        val QualifierValue = kindOf(TowerGroupKind.QualifierValue)

        val Member = kindOf(TowerGroupKind.Member)

        fun Local(depth: Int) = kindOf(TowerGroupKind.Local(depth))

        fun Implicit(depth: Int) = kindOf(TowerGroupKind.Implicit(depth))
        fun NonLocal(depth: Int) = kindOf(TowerGroupKind.NonLocal(depth))

        fun TopPrioritized(depth: Int) = kindOf(TowerGroupKind.TopPrioritized(depth))

        val Last = kindOf(TowerGroupKind.Last)
    }

    private fun kindOf(kind: TowerGroupKind): TowerGroup = TowerGroup(subscript(code, kind), appendDebugKind(debugKinds, kind))

    val Member get() = kindOf(TowerGroupKind.Member)

    fun Local(depth: Int) = kindOf(TowerGroupKind.Local(depth))

    fun Implicit(depth: Int) = kindOf(TowerGroupKind.Implicit(depth))
    fun NonLocal(depth: Int) = kindOf(TowerGroupKind.NonLocal(depth))

    val InvokeExtension get() = kindOf(TowerGroupKind.InvokeExtension)

    fun TopPrioritized(depth: Int) = kindOf(TowerGroupKind.TopPrioritized(depth))

    // Treating `a.foo()` common calls as more prioritized than `a.foo.invoke()`
    // It's not the same as TowerGroupKind because it's not about tower levels, but rather a different dimension semantically.
    // It could be implemented via another TowerGroupKind, but it's not clear what priority should be assigned to the new TowerGroupKind
    fun InvokeResolvePriority(invokeResolvePriority: InvokeResolvePriority): TowerGroup {
        if (invokeResolvePriority == InvokeResolvePriority.NONE) return this
        return TowerGroup(code, debugKinds, invokeResolvePriority)
    }

    private fun debugCompareTo(other: TowerGroup): Int {
        var index = 0
        while (index < debugKinds.size) {
            if (index >= other.debugKinds.size) return 1
            when {
                debugKinds[index] < other.debugKinds[index] -> return -1
                debugKinds[index] > other.debugKinds[index] -> return 1
            }
            index++
        }
        if (index < other.debugKinds.size) return -1

        return invokeResolvePriority.compareTo(other.invokeResolvePriority)
    }

    override fun compareTo(other: TowerGroup): Int = run {
        val result = java.lang.Long.compareUnsigned(code, other.code)
        if (result != 0) return@run result
        return@run invokeResolvePriority.compareTo(other.invokeResolvePriority)
    }.also {
        if (DEBUG) {
            val debugResult = debugCompareTo(other)
            require(debugResult == it) { "Kind comparison incorrect: $this vs $other, expected: $it, $debugResult" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TowerGroup

        if (code != other.code) return false
        if (DEBUG && !this.debugKinds.contentEquals(other.debugKinds))
            error("Equals fail: $this vs $other")
        if (invokeResolvePriority != other.invokeResolvePriority) return false

        return true
    }

    override fun toString(): String {
        return "TowerGroup(code=${code.toString(2)}, debugKinds=${debugKinds.contentToString()}, invokeResolvePriority=$invokeResolvePriority)"
    }

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + invokeResolvePriority.hashCode()
        return result
    }


}

enum class InvokeResolvePriority {
    NONE, INVOKE_RECEIVER, COMMON_INVOKE, INVOKE_EXTENSION;
}
