package io.github.diet103.lector.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The store that reverses PLAN §4's "selected text is never persisted", so its guarantees are
 * worth pinning: re-reads don't duplicate, the table stays bounded, and clearing really clears.
 */
// sdk 35: Robolectric's SDK 36 image needs a Java 21 test runtime, and the toolchain is JDK 17.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HistoryStoreTest {

    private lateinit var store: HistoryStore

    @Before
    fun setUp() {
        store = HistoryStore(ApplicationProvider.getApplicationContext<Context>())
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun entry(
        key: String,
        text: String = "Some text for $key",
        title: String? = null,
        source: ReadSource = ReadSource.SELECTION,
        lastReadAt: Long = 1_000L,
        createdAt: Long = lastReadAt
    ) = HistoryEntry(
        key = key,
        text = text,
        title = title,
        source = source,
        sourceUrl = null,
        voiceId = "voice1",
        modelId = "eleven_flash_v2_5",
        outputFormat = "mp3_44100_128",
        createdAt = createdAt,
        lastReadAt = lastReadAt,
        durationMs = null,
        audioBytes = null
    )

    @Test
    fun `a recorded read comes back whole`() {
        store.record(
            entry(key = "abc", text = "Hello there.", title = "A title", source = ReadSource.LINK)
                .copy(sourceUrl = "https://example.com/a", durationMs = 4200, audioBytes = 90_000)
        )

        val found = store.byKey("abc")!!
        assertEquals("Hello there.", found.text)
        assertEquals("A title", found.title)
        assertEquals(ReadSource.LINK, found.source)
        assertEquals("https://example.com/a", found.sourceUrl)
        assertEquals(4200L, found.durationMs)
        assertEquals(90_000L, found.audioBytes)
    }

    @Test
    fun `null title, url, duration and bytes survive the round trip`() {
        store.record(entry(key = "sparse"))

        val found = store.byKey("sparse")!!
        assertNull(found.title)
        assertNull(found.sourceUrl)
        assertNull(found.durationMs)
        assertNull(found.audioBytes)
    }

    // The key is the content hash, so re-reading the same text must move one row rather than make
    // a second — otherwise a favourite article fills the list with itself.
    @Test
    fun `re-reading the same text updates one row and keeps the original first-read time`() {
        store.record(entry(key = "same", lastReadAt = 1_000, createdAt = 1_000))
        store.record(entry(key = "same", lastReadAt = 9_000, createdAt = 9_000))

        assertEquals(1, store.count())
        val found = store.byKey("same")!!
        assertEquals("first read stays truthful", 1_000L, found.createdAt)
        assertEquals(9_000L, found.lastReadAt)
    }

    @Test
    fun `recent is ordered by most recently read`() {
        store.record(entry(key = "old", lastReadAt = 100))
        store.record(entry(key = "newest", lastReadAt = 300))
        store.record(entry(key = "middle", lastReadAt = 200))

        assertEquals(listOf("newest", "middle", "old"), store.recent().map { it.key })
        assertEquals("newest", store.mostRecent()!!.key)
    }

    @Test
    fun `mostRecent is null on an empty history`() {
        assertNull(store.mostRecent())
    }

    @Test
    fun `search matches text and title, case-insensitively`() {
        store.record(entry(key = "a", text = "The quick brown fox"))
        store.record(entry(key = "b", text = "nothing relevant", title = "Brown study"))
        store.record(entry(key = "c", text = "unrelated entirely"))

        assertEquals(setOf("a", "b"), store.search("brown").map { it.key }.toSet())
        assertEquals(setOf("a", "b"), store.search("BROWN").map { it.key }.toSet())
    }

    // A search box that empties to "no results" reads as broken.
    @Test
    fun `a blank search returns everything`() {
        store.record(entry(key = "a"))
        store.record(entry(key = "b"))

        assertEquals(2, store.search("   ").size)
    }

    @Test
    fun `search with no matches returns nothing`() {
        store.record(entry(key = "a", text = "The quick brown fox"))

        assertTrue(store.search("aardvark").isEmpty())
    }

    // LIKE wildcards typed into the search box must match literally, not select the whole table.
    // Searching "%" returns the row that literally contains a percent sign — and only that row.
    // Unescaped, it would match everything.
    @Test
    fun `LIKE wildcards in the query match literally`() {
        store.record(entry(key = "a", text = "plain text"))
        store.record(entry(key = "b", text = "literally 50% off"))
        store.record(entry(key = "c", text = "snake_case name"))

        assertEquals(listOf("b"), store.search("50%").map { it.key })
        assertEquals(listOf("b"), store.search("%").map { it.key })
        assertEquals(listOf("c"), store.search("_").map { it.key })
        // "_" as a wildcard would match any single character, so "e_t" would hit "plain text".
        assertTrue(store.search("e_t").isEmpty())
    }

    // A replay knows the key but nothing about where the text originally came from. Recording one
    // as if it were fresh would relabel a screenshot read as shared text and drop its title.
    @Test
    fun `touch moves a read to the top without rewriting how it got here`() {
        store.record(
            entry(key = "k", title = "Original title", source = ReadSource.SCREENSHOT, lastReadAt = 100)
        )

        assertTrue(store.touch("k", 900))

        val found = store.byKey("k")!!
        assertEquals(900L, found.lastReadAt)
        assertEquals(ReadSource.SCREENSHOT, found.source)
        assertEquals("Original title", found.title)
        assertEquals(100L, found.createdAt)
        assertEquals(1, store.count())
    }

    @Test
    fun `touch reports false for a read that is not stored`() {
        assertEquals(false, store.touch("never-seen", 900))
    }

    @Test
    fun `markCompleted fills in what only the end of playback knows`() {
        store.record(entry(key = "k"))
        assertNull(store.byKey("k")!!.durationMs)

        store.markCompleted("k", durationMs = 12_345, audioBytes = 200_000)

        val found = store.byKey("k")!!
        assertEquals(12_345L, found.durationMs)
        assertEquals(200_000L, found.audioBytes)
    }

    @Test
    fun `delete removes one entry and leaves the rest`() {
        store.record(entry(key = "a"))
        store.record(entry(key = "b"))

        store.delete("a")

        assertNull(store.byKey("a"))
        assertEquals(1, store.count())
    }

    @Test
    fun `clearAll empties the history`() {
        repeat(5) { store.record(entry(key = "k$it")) }

        store.clearAll()

        assertEquals(0, store.count())
        assertTrue(store.recent().isEmpty())
    }

    // An unbounded log of everything someone has ever read is a privacy problem on its own.
    @Test
    fun `the table is trimmed to the most recent MAX_ENTRIES`() {
        repeat(HistoryStore.MAX_ENTRIES + 20) { i ->
            store.record(entry(key = "k$i", lastReadAt = i.toLong()))
        }

        assertEquals(HistoryStore.MAX_ENTRIES, store.count())
        // The oldest went, the newest stayed.
        assertNull(store.byKey("k0"))
        assertNull(store.byKey("k19"))
        assertEquals("k${HistoryStore.MAX_ENTRIES + 19}", store.mostRecent()!!.key)
    }

    @Test
    fun `revision advances on every write so the UI can re-query`() {
        val start = store.revision.value

        store.record(entry(key = "a"))
        val afterRecord = store.revision.value
        store.markCompleted("a", 1, 2)
        val afterComplete = store.revision.value
        store.delete("a")
        val afterDelete = store.revision.value
        store.clearAll()

        assertTrue(afterRecord > start)
        assertTrue(afterComplete > afterRecord)
        assertTrue(afterDelete > afterComplete)
        assertTrue(store.revision.value > afterDelete)
    }

    // Enum names are the stored form, so a row written by a newer build must not crash an older one.
    @Test
    fun `an unknown source name reads back as shared text rather than throwing`() {
        assertEquals(ReadSource.SHARED_TEXT, ReadSource.fromName("SOMETHING_FROM_THE_FUTURE"))
        assertEquals(ReadSource.SHARED_TEXT, ReadSource.fromName(null))
        assertEquals(ReadSource.SCREENSHOT, ReadSource.fromName("SCREENSHOT"))
    }

    @Test
    fun `snippet flattens whitespace and marks truncation`() {
        val long = entry(key = "s", text = "  A  read\nwith\tmessy   whitespace  ")
        assertEquals("A read with messy whitespace", long.snippet())

        val truncated = entry(key = "t", text = "abcdefghij").snippet(max = 4)
        assertEquals("abcd…", truncated)
    }
}
