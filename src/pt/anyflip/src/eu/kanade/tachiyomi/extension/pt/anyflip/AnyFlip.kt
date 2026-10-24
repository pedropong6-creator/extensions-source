package eu.kanade.tachiyomi.extension.pt.anyflip

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class AnyFlip : KeiSource() {

    override val supportsLatest: Boolean = false

    override fun getHomeUrl(): String = "https://anyflip.com/homepage/dbeui/"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val books = discoverBooks()

        val pageSize = 20
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, books.size)

        if (fromIndex >= books.size) {
            return MangasPage(emptyList(), false)
        }

        val mangas = books
            .subList(fromIndex, toIndex)
            .map { it.toManga() }

        return MangasPage(
            mangas = mangas,
            hasNextPage = toIndex < books.size,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (page > 1) {
            return MangasPage(emptyList(), false)
        }

        val q = query.trim()

        if (q.isEmpty()) {
            return getPopularManga(1)
        }

        val mangas = discoverBooks()
            .filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.description.orEmpty().contains(q, ignoreCase = true)
            }
            .map { it.toManga() }

        return MangasPage(mangas, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val bookId = extractBookId(url) ?: return null

        return runCatching {
            loadBook(bookId).toManga()
        }.getOrNull()
    }

    override fun getMangaUrl(manga: SManga): String = "$ONLINE_BASE/${manga.url.trim('/')}/"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.trim('/')

        val book = loadBook(id)

        val details = book.toManga().apply {
            url = id
        }

        val chapterList = if (fetchChapters || chapters.isEmpty()) {
            listOf(
                SChapter.create().apply {
                    url = id
                    name = "Edição completa"
                    chapter_number = 1f
                },
            )
        } else {
            chapters
        }

        return SMangaUpdate(details, chapterList)
    }

    override fun getChapterUrl(chapter: SChapter): String = "$ONLINE_BASE/${chapter.url.trim('/')}/"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.trim('/')
        val book = loadBook(id)

        return book.pages.mapIndexed { index, imageUrl ->
            Page(
                index = index,
                imageUrl = imageUrl,
            )
        }
    }

    /**
     * Gets the publications from the AnyFlip homepage API.
     *
     * The numeric `bookid` is only an internal database ID.
     *
     * `bLink` is the actual publication identifier used in:
     *
     * https://online.anyflip.com/dbeui/{bLink}/
     */
    private suspend fun discoverBooks(): List<BookSummary> {
        val books = mutableListOf<BookSummary>()
        val seenLinks = mutableSetOf<String>()

        val pageSize = 20
        var page = 1

        while (true) {
            val url = "https://anyflip.com/hostInfo/get-homepage-books1.php"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("pageSize", pageSize.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("userid", USER_ID)
                .addQueryParameter("myUid", "-1")
                .addQueryParameter("lastTime", "2026-10-23")
                .build()

            val response = client.get(url)
            val text = response.body.string()

            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}')

            require(jsonStart >= 0 && jsonEnd > jsonStart) {
                "Invalid AnyFlip homepage response"
            }

            val root = Json.parseToJsonElement(
                text.substring(jsonStart, jsonEnd + 1),
            ).jsonObject

            val values = root["values"]
                ?.jsonArray
                .orEmpty()

            if (values.isEmpty()) {
                break
            }

            var newBooks = 0

            for (value in values) {
                val book = value.jsonObject

                val bLink = book["bLink"]
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    ?: continue

                if (!seenLinks.add(bLink)) {
                    continue
                }

                val title = book["title"]
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    .orEmpty()

                if (title.isEmpty()) {
                    continue
                }

                val description = book["description"]
                    ?.jsonPrimitive
                    ?.content
                    ?.replace(
                        Regex(
                            "<br\\s*/?>",
                            RegexOption.IGNORE_CASE,
                        ),
                        "\n",
                    )
                    ?.replace(
                        Regex("<[^>]+>"),
                        "",
                    )
                    ?.replace("&quot;", "\"")
                    ?.replace("&amp;", "&")
                    ?.replace("&lt;", "<")
                    ?.replace("&gt;", ">")
                    ?.trim()

                val thumbnail = book["book_img"]
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()

                books += BookSummary(
                    id = bLink,
                    title = title,
                    description = description,
                    thumbnail = thumbnail,
                )

                newBooks++
            }

            if (values.size < pageSize || newBooks == 0) {
                break
            }

            page++
        }

        return books
    }

    /**
     * Loads the actual AnyFlip publication.
     *
     * Unlike the homepage API, this uses the `bLink`, for example:
     *
     * https://online.anyflip.com/dbeui/zlmz/
     */
    private suspend fun loadBook(id: String): Book {
        val url = "$ONLINE_BASE/$id/mobile/javascript/config.js"

        val response = client.get(
            url.toHttpUrl().newBuilder()
                .build(),
        )

        val text = response.body.string()

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')

        require(start >= 0 && end > start) {
            "Invalid AnyFlip config.js for book $id"
        }

        val root = Json.parseToJsonElement(
            text.substring(start, end + 1),
        ).jsonObject

        val meta = root["meta"]?.jsonObject

        val title = meta
            ?.get("title")
            ?.jsonPrimitive
            ?.content
            ?: error("AnyFlip title not found for book $id")

        val description = meta
            ?.get("description")
            ?.jsonPrimitive
            ?.content
            ?.replace(
                Regex(
                    "<br\\s*/?>",
                    RegexOption.IGNORE_CASE,
                ),
                "\n",
            )
            ?.replace(
                Regex("<[^>]+>"),
                "",
            )
            ?.replace("&quot;", "\"")
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.trim()

        val pageEntries = root["fliphtml5_pages"]
            ?.jsonArray
            .orEmpty()

        val pages = pageEntries.mapNotNull { entry ->
            val raw = entry
                .jsonObject["n"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonPrimitive
                ?.content
                ?: return@mapNotNull null

            normalizeAssetUrl(id, raw)
        }

        require(pages.isNotEmpty()) {
            "No pages found in AnyFlip config.js for book $id"
        }

        val thumbnail = pageEntries
            .firstOrNull()
            ?.jsonObject
            ?.get("t")
            ?.jsonPrimitive
            ?.content
            ?.let {
                normalizeAssetUrl(id, it)
            }

        return Book(
            id = id,
            title = title,
            description = description,
            thumbnail = thumbnail,
            pages = pages,
        )
    }

    private fun normalizeAssetUrl(
        id: String,
        raw: String,
    ): String {
        var path = raw
            .replace("\\", "")
            .removePrefix("../")

        if (!path.startsWith("files/")) {
            path = "files/large/$path"
        }

        return "$ONLINE_BASE/$id/$path"
    }

    private fun BookSummary.toManga(): SManga = SManga.create().apply {
        url = id
        title = this@toManga.title
        description = this@toManga.description
        thumbnail_url = thumbnail
        status = SManga.COMPLETED
        genre = "Turma da Mônica Jovem"
    }

    private fun Book.toManga(): SManga = SManga.create().apply {
        url = id
        title = this@toManga.title
        description = this@toManga.description
        thumbnail_url = this@toManga.thumbnail
        status = SManga.COMPLETED
        genre = "Turma da Mônica Jovem"
    }

    private fun extractBookId(url: HttpUrl): String? {
        val segments = url.pathSegments.filter {
            it.isNotBlank()
        }

        return when {
            url.host == "online.anyflip.com" &&
                segments.size >= 2 &&
                segments[0] == "dbeui" ->
                segments[1]

            url.host == "anyflip.com" &&
                segments.size >= 2 &&
                segments[0] == "dbeui" ->
                segments[1]

            else -> null
        }
    }

    private data class BookSummary(
        val id: String,
        val title: String,
        val description: String?,
        val thumbnail: String?,
    )

    private data class Book(
        val id: String,
        val title: String,
        val description: String?,
        val thumbnail: String?,
        val pages: List<String>,
    )

    companion object {
        private const val USER_ID = "52027903"

        private const val ONLINE_BASE =
            "https://online.anyflip.com/dbeui"
    }
}
