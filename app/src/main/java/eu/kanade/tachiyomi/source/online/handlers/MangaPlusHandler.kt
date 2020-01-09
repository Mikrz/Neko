package eu.kanade.tachiyomi.source.online.handlers

import MangaPlusSerializer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.*


class MangaPlusHandler(val currentClient: OkHttpClient) {
    val baseUrl = "https://jumpg-webapi.tokyo-cdn.com/api"
    val headers = Headers.Builder()
            .add("Origin", WEB_URL)
            .add("Referer", WEB_URL)
            .add("User-Agent", USER_AGENT)
            .add("SESSION-TOKEN", UUID.randomUUID().toString()).build()

    val client: OkHttpClient = currentClient.newBuilder()
            .addInterceptor { imageIntercept(it) }
            .build()

    fun fetchPageList(chapterId: String): List<Page> {
        val response = client.newCall(pageListRequest(chapterId)).execute()
        return pageListParse(response)
    }

    private fun pageListRequest(chapterId: String): Request {
        return GET("$baseUrl/manga_viewer?chapter_id=$chapterId&split=yes&img_quality=high", headers)
    }

    private fun pageListParse(response: Response): List<Page> {
        val result = ProtoBuf.load(MangaPlusSerializer, response.body!!.bytes())

        if (result.success == null) {
            throw Exception("error getting images")
        }

        return result.success.mangaViewer!!.pages
                .mapNotNull { it.page }
                .mapIndexed { i, page ->
                    val encryptionKey = if (page.encryptionKey == null) "" else "&encryptionKey=${page.encryptionKey}"
                    Page(i, "", "${page.imageUrl}$encryptionKey")
                }
    }

    private fun imageIntercept(chain: Interceptor.Chain): Response {
        var request = chain.request()

        if (!request.url.queryParameterNames.contains("encryptionKey")) {
            return chain.proceed(request)
        }

        val encryptionKey = request.url.queryParameter("encryptionKey")!!

        // Change the url and remove the encryptionKey to avoid detection.
        val newUrl = request.url.newBuilder().removeAllQueryParameters("encryptionKey").build()
        request = request.newBuilder().url(newUrl).build()

        val response = chain.proceed(request)

        val image = decodeImage(encryptionKey, response.body!!.bytes())

        val body = image.toResponseBody("image/jpeg".toMediaTypeOrNull())
        return response.newBuilder().body(body).build()
    }

    private fun decodeImage(encryptionKey: String, image: ByteArray): ByteArray {
        val keyStream = HEX_GROUP
                .findAll(encryptionKey)
                .map { it.groupValues[1].toInt(16) }
                .toList()

        val content = image
                .map { it.toInt() }
                .toMutableList()

        val blockSizeInBytes = keyStream.size

        for ((i, value) in content.iterator().withIndex()) {
            content[i] = value xor keyStream[i % blockSizeInBytes]
        }

        return ByteArray(content.size) { pos -> content[pos].toByte() }
    }


    companion object {
        private const val WEB_URL = "https://mangaplus.shueisha.co.jp"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.92 Safari/537.36"
        private val HEX_GROUP = "(.{1,2})".toRegex()
    }
}