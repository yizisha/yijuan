package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.feature.reader.ChapterPaginator
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileReaderPageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun key() =
        ReaderPageKey(3, 2, 4, 10, "EPUB 章", ReadingSettings(), "default", 600, 900, 12, "images", "marks", "sha256", "scale:1")
    private fun pages() = listOf(
        listOf(ChapterPaginator.Fragment("注音与😀正文", 8, true, false, false, listOf(SourceRange("chapter:2", 8, 15)), "block:2"))
    )

    @Test fun reopeningRestoresExactFragmentsWithoutRemeasuring() = runBlocking {
        val directory = temporary.newFolder()
        var measured = 0
        val first = ReaderPageCache(store = FileReaderPageStore(directory), persistenceScope = this)
        first.getOrCompute(key(), this) {
            measured++
            pages()
        }
        yield()
        val reopened = ReaderPageCache(store = FileReaderPageStore(directory), persistenceScope = this)
        assertEquals(
            pages(),
            reopened.getOrCompute(key(), this) {
                measured++
                emptyList()
            }
        )
        assertEquals(1, measured)
        for (different in listOf(
            key().copy(widthPx = 700),
            key().copy(environmentKey = "scale:1.4"),
            key().copy(contentFingerprint = "edited-book"),
            key().copy(marksKey = "changed-ruby"),
            key().copy(imageKey = "changed-image")
        )) {
            assertNull(FileReaderPageStore(directory).get(different))
        }
    }

    @Test fun corruptFilesAreMissesAndLeaveTheBookUntouched() {
        val directory = temporary.newFolder()
        val book = temporary.newFile("book.epub").apply { writeText("original") }
        val store = FileReaderPageStore(directory)
        store.put(key(), pages())
        directory.listFiles()!!.single().writeText("interrupted write")
        assertNull(store.get(key()))
        assertEquals("original", book.readText())
        assertEquals(0, directory.listFiles()!!.size)
        File(directory, "uncommitted.tmp").writeText("temporary")
        assertNull(store.get(key()))
    }

    @Test fun diskEntriesAndBytesAreBounded() {
        val directory = temporary.newFolder()
        val store = FileReaderPageStore(directory, maxEntries = 2)
        store.put(key(), pages())
        directory.listFiles()!!.single().setLastModified(100)
        store.put(key().copy(chapterIndex = 3), pages())
        store.put(key().copy(chapterIndex = 4), pages())
        assertNull(store.get(key()))
        assertEquals(2, directory.listFiles()!!.size)
        val tiny = FileReaderPageStore(temporary.newFolder(), maxBytes = 1)
        tiny.put(key(), pages())
        assertNull(tiny.get(key()))
    }

    @Test fun largeUtf8FragmentsAvoidTheWriteUtf64KilobyteLimit() {
        val text = "中文😀".repeat(15_000)
        val fragments = listOf(listOf(ChapterPaginator.Fragment(text, 0, false, false, false)))
        val store = FileReaderPageStore(temporary.newFolder())
        store.put(key(), fragments)
        assertEquals(fragments, store.get(key()))
    }

    @Test fun legacySurrogateSplitsRoundTripWithoutChangingTextOrCoordinates() {
        val fragments = listOf(
            listOf(ChapterPaginator.Fragment("正文\uD83D", 0, false, false, false)),
            listOf(ChapterPaginator.Fragment("\uDE00之后", 3, false, false, false))
        )
        val store = FileReaderPageStore(temporary.newFolder())
        store.put(key(), fragments)
        assertEquals(fragments, store.get(key()))
    }
}
