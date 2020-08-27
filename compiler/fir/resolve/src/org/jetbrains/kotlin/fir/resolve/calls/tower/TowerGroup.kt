/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower

sealed class TowerGroupKind(protected val index: UByte) : Comparable<TowerGroupKind> {
    abstract class WithDepth(index: UByte, val depth: Int) : TowerGroupKind(index) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as WithDepth

            if (index != other.index) return false
            if (depth != other.depth) return false

            return true
        }

        override fun hashCode(): Int {
            return 31 * depth + index.toInt()
        }
    }

    object Start : TowerGroupKind(0b0u)

    object ClassifierPrioritized : TowerGroupKind(1u)

    object Qualifier : TowerGroupKind(2u)

    object Classifier : TowerGroupKind(3u)

    class TopPrioritized(depth: Int) : WithDepth(4u, depth)

    object Member : TowerGroupKind(5u)

    class Local(depth: Int) : WithDepth(6u, depth)

    class ImplicitOrNonLocal(depth: Int, val kindForDebugSake: String) : WithDepth(7u, depth)

    object InvokeExtension : TowerGroupKind(8u)

    object QualifierValue : TowerGroupKind(9u)

    object Last : TowerGroupKind(0b1111u)

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
    //private val kinds: Array<TowerGroupKind>,
    private val code: ULong,
    private val invokeResolvePriority: InvokeResolvePriority = InvokeResolvePriority.NONE
) : Comparable<TowerGroup> {
    companion object {
        private const val KIND_SIZE_BITS: Byte = 4
        private const val DEPTH_SIZE_BITS: Byte = 10
        private val USED_BITS_MASK: ULong = 0b111111u // max size 64 bits
        private const val TOTAL_BITS = 64

        private fun subscript(code: ULong, kind: TowerGroupKind): TowerGroup {
            val usedBits = (code and USED_BITS_MASK).toInt()
            val remainingBits = TOTAL_BITS - usedBits
            return when (kind) {
                is TowerGroupKind.WithDepth -> {
                    val kindPos = remainingBits - KIND_SIZE_BITS
                    val depthPos = kindPos - DEPTH_SIZE_BITS

                    TowerGroup(code or kind.index.toULong().shl(kindPos) or kind.depth.toULong().shl(depthPos) or depthPos.toULong())
                }
                else -> {
                    val shift = remainingBits - KIND_SIZE_BITS
                    TowerGroup(code or kind.index.toULong().shl(shift) or shift.toULong())
                }
            }
        }

        private fun kindOf(kind: TowerGroupKind): TowerGroup {
            return subscript(0u, kind)
        }


        val Start = kindOf(TowerGroupKind.Start)
        val EmptyRoot = TowerGroup(0u)

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

    private fun kindOf(kind: TowerGroupKind): TowerGroup = subscript(code, kind)

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
        return TowerGroup(code, invokeResolvePriority)
    }

    override fun compareTo(other: TowerGroup): Int {
        val result = code.compareTo(other.code)
        if (result != 0) return result
        return invokeResolvePriority.compareTo(other.invokeResolvePriority)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TowerGroup

        if (code != other.code) return false
//        if (unusedBits != other.unusedBits) return false
        if (invokeResolvePriority != other.invokeResolvePriority) return false

        return true
    }

    override fun toString(): String {
        return "TowerGroup(code=${code.toString(2)}, invokeResolvePriority=$invokeResolvePriority)"
    }

    override fun hashCode(): Int {
        var result = code.hashCode()
//        result = 31 * result + unusedBits
        result = 31 * result + invokeResolvePriority.hashCode()
        return result
    }


}

enum class InvokeResolvePriority {
    NONE, INVOKE_RECEIVER, COMMON_INVOKE, INVOKE_EXTENSION;
}
