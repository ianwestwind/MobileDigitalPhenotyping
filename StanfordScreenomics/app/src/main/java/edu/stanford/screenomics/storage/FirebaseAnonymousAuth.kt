package edu.stanford.screenomics.storage

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Ensures [FirebaseAuth] has a user so Storage / Firestore / RTDB rules that require
 * `request.auth != null` can succeed. Enable **Anonymous** in Firebase Console → Authentication.
 */
object FirebaseAnonymousAuth {

    private const val TAG = "FirebaseAnonymousAuth"

    private val mutex = Mutex()

    suspend fun ensureSignedIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            return
        }
        mutex.withLock {
            if (auth.currentUser != null) {
                return
            }
            runCatching {
                auth.signInAnonymously().await()
                Log.i(TAG, "Anonymous sign-in ok uid=${auth.currentUser?.uid}")
            }.onFailure { e ->
                Log.e(
                    TAG,
                    "Anonymous sign-in failed. Enable Authentication → Sign-in method → Anonymous " +
                        "in the Firebase project that owns your Storage bucket, or relax Storage rules.",
                    e,
                )
            }
        }
    }
}
