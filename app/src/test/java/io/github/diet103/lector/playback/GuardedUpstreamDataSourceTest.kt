package io.github.diet103.lector.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Billing defense #4 (PLAN §4): the guard sits between cache and network, and any upstream
 * open at a nonzero byte offset must fail loudly instead of re-billing the full text and
 * splicing a different generation. None of the streaming-suite scenarios legitimately reach
 * this state, so it gets pinned down directly.
 */
// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GuardedUpstreamDataSourceTest {

    private class RecordingDataSource : DataSource {
        val opened = mutableListOf<DataSpec>()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opened += dataSpec
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = opened.lastOrNull()?.uri

        override fun close() = Unit
    }

    private val upstream = RecordingDataSource()
    private val guarded = GuardedUpstreamDataSource(upstream)

    @Test
    fun `start-of-stream open passes through to the network source`() {
        val spec = DataSpec.Builder().setUri(Uri.parse("https://api.test/audio")).build()

        guarded.open(spec)

        assertEquals(listOf(spec), upstream.opened)
    }

    @Test
    fun `nonzero offset is refused before any network request happens`() {
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://api.test/audio"))
            .setPosition(1024)
            .build()

        val error = assertThrows(IOException::class.java) { guarded.open(spec) }

        assertTrue(error.message.orEmpty().contains("re-bill"))
        assertEquals(0, upstream.opened.size)
    }
}
