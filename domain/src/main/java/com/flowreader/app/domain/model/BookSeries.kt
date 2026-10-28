package com.flowreader.app.domain.model

/** A series is independent of shelf groups, favourites and book metadata refreshes. */
object BookSeries {
    fun requireName(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 80 && it.none(Char::isISOControl)) { "系列名称需为 1–80 个字符" }
    }

    fun ordered(books: List<Book>): List<Book> = books.sortedWith(compareBy<Book> { it.seriesOrder }.thenBy { it.id })

    fun next(book: Book, library: List<Book>): Book? {
        val name = book.seriesName ?: return null
        val volumes = ordered(library.filter { it.seriesName == name })
        val index = volumes.indexOfFirst { it.id == book.id }
        return if (index >= 0) volumes.getOrNull(index + 1) else null
    }

    /** Collapse after filtering: a series never smuggles hidden/non-favourite books onto a shelf. */
    fun shelf(visible: List<Book>): List<SeriesShelfEntry> {
        val emitted = hashSetOf<String>()
        val grouped = visible.filter { !it.seriesName.isNullOrBlank() }.groupBy { it.seriesName!! }
        return visible.mapNotNull { book ->
            val name = book.seriesName?.takeIf { it.isNotBlank() }
            if (name == null) SeriesShelfEntry("book:${book.id}", book, null, listOf(book))
            else if (emitted.add(name)) {
                val members = ordered(grouped.getValue(name))
                val cover = members.maxByOrNull { it.lastReadTime?.time ?: Long.MIN_VALUE } ?: members.first()
                SeriesShelfEntry("series:$name", cover, name, members)
            } else null
        }
    }
}

data class SeriesShelfEntry(val key: String, val book: Book, val name: String?, val volumes: List<Book>)
