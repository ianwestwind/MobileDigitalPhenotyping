package edu.stanford.screenomics.cachemanagement

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import edu.stanford.screenomics.core.management.LifecycleAwareCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AndroidX [androidx.lifecycle.Lifecycle] adapter for [LifecycleAwareCacheManager].
 *
 * Host lifecycle signals are translated into manager-directed TTL sweeps and teardown cleanup.
 * This class does **not** introduce a global cache; it only forwards events to the injected manager instance.
 */
class AndroidCacheManagerLifecycleBridge(
    private val owner: LifecycleOwner,
    private val orchestrator: LifecycleAwareCacheManager,
) : DefaultLifecycleObserver {

    fun attach() {
        owner.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            orchestrator.onHostForegrounded()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            orchestrator.onHostBackgrounded()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(this)
        owner.lifecycleScope.launch(NonCancellable) {
            withContext(Dispatchers.Default) {
                orchestrator.onHostDestroyed()
            }
        }
    }
}
