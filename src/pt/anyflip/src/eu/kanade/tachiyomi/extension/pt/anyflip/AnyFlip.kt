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

    override fun getHomeUrl(): String =
        "https://anyflip.com/homepage/dbeui/"

    override suspend fun getPopularManga(page: Int): MangasPage {
        val ids = discoverBookIds()

        val mangas = ids.mapNotNull { id ->
            runCatching {
                loadBook(id).toManga()
            }.getOrNull()
        }

        val pageSize = 20
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, mangas.size)

        if (fromIndex >= mangas.size) {
            return MangasPage(emptyList(), false)
        }

        return MangasPage(
            mangas = mangas.subList(fromIndex, toIndex),
            hasNextPage = toIndex < mangas.size,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getPopularManga(page)

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

        val ids = discoverBookIds()

        val mangas = ids.mapNotNull { id ->
            runCatching {
                loadBook(id).toManga()
            }.getOrNull()
        }.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.description.orEmpty().contains(q, ignoreCase = true)
        }

        return MangasPage(mangas, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = extractBookId(url) ?: return null

        return runCatching {
            loadBook(id).toManga()
        }.getOrNull()
    }

    override fun getMangaUrl(manga: SManga): String =
        "https://anyflip.com/dbeui/${manga.url}/"

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

    override fun getChapterUrl(chapter: SChapter): String =
        "https://anyflip.com/dbeui/${chapter.url.trim('/')}/"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.trim('/')
        val book = loadBook(id)

        return book.pages.mapIndexed { index, imageUrl ->
            Page(
                index,
                imageUrl = imageUrl,
            )
        }
    }

    /**
     * Gets all publication IDs belonging to the AnyFlip account.
     *
     * The public homepage is protected by Cloudflare, so we don't scrape
     * the homepage itself. Instead, we use the endpoint the homepage
     * uses to retrieve its publications:
     *
     * https://anyflip.com/hostInfo/get-homepage-books1.php
     */
    private suspend fun discoverBookIds(): List<String> {
        val pageSize = 20
        val allIds = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()

        var page = 1

        while (true) {
            val url = "https://anyflip.com/hostInfo/get-homepage-books1.php"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("pageSize", pageSize.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("userid", USER_ID)
                .addQueryParameter("myUid", "-1")
                .addQueryParameter("lastTime", java.time.LocalDate.now().toString())
                .build()

            val response = client.get(url)
            val text = response.body.string()

            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')

            require(start >= 0 && end > start) {
                "Invalid AnyFlip homepage books response"
            }

            val root = Json.parseToJsonElement(
                text.substring(start, end + 1),
            ).jsonObject

            val books = root["values"]
                ?.jsonArray
                .orEmpty()

            if (books.isEmpty()) {
                break
            }

            var newIds = 0

            for (book in books) {
                val id = book
                    .jsonObject["bookid"]
                    ?.jsonPrimitive
                    ?.content
                    ?: continue

                if (seenIds.add(id)) {
                    allIds += id
                    newIds++
                }
            }

            if (books.size < pageSize || newIds == 0) {
                break
            }

            page++
        }

        return allIds
    }

    private suspend fun loadBook(id: String): Book {
        val url = "$ONLINE_BASE/$id/mobile/javascript/config.js"

        val response = client.get(url)
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

    private fun Book.toManga(): SManga =
        SManga.create().apply {
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
            url.host == "anyflip.com" &&
                segments.size >= 2 &&
                segments[0] == "dbeui" ->
                segments[1]

            url.host == "online.anyflip.com" &&
                segments.size >= 2 &&
                segments[0] == "dbeui" ->
                segments[1]

            else -> null
        }
    }

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
```
