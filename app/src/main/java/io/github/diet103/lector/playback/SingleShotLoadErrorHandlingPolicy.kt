package io.github.diet103.lector.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Never auto-retry: every retry is a fresh POST and a fresh bill. Errors surface to the user,
 * and retrying is always an explicit, knowing action.
 */
class SingleShotLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        C.TIME_UNSET

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1
}
