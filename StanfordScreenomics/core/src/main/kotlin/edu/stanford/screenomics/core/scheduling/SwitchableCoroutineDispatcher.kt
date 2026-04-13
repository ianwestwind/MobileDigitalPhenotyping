package edu.stanford.screenomics.core.scheduling

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [CoroutineDispatcher] that **forwards** to a replaceable delegate so [DefaultTaskScheduler] can retune
 * parallelism under resource stress **without** allocating unbounded new dispatcher graphs each tick.
 *
 * Only [dispatch] / [isDispatchNeeded] are overridden: the base [CoroutineDispatcher] wires continuations through
 * those methods, while [CoroutineDispatcher.interceptContinuation] is **final** in current kotlinx.coroutines and
 * must not be overridden.
 */
class SwitchableCoroutineDispatcher(
    val label: String,
    initial: CoroutineDispatcher,
) : CoroutineDispatcher() {

    private val ref = AtomicReference(initial)

    fun replace(delegate: CoroutineDispatcher) {
        ref.set(delegate)
    }

    fun current(): CoroutineDispatcher = ref.get()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = ref.get().isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        ref.get().dispatch(context, block)
    }
}
