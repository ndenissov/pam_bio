/*
 * Copyright 2026 Nikita Denissov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ovh.dep.pam.network

import android.util.Log

/**
 * Logging wrapper that calls [android.util.Log] on Android and degrades
 * gracefully when running local JVM unit tests where android.util.Log is stubbed.
 */
internal object NetLog {
    fun d(tag: String, msg: String) = safeCall { Log.d(tag, msg) }
    fun i(tag: String, msg: String) = safeCall { Log.i(tag, msg) }
    fun w(tag: String, msg: String) = safeCall { Log.w(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable? = null) = safeCall {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }

    private inline fun safeCall(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
            // Stubbed android.util.Log in pure JVM unit tests.
        }
    }
}

