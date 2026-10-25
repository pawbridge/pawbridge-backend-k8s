package com.pawbridge.backend.community.internal.homevideo.infrastructure

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Parse only. Never fetch a URL supplied by an administrator. */
object YouTubeVideoUrl {
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    @JvmStatic
    fun id(input: String?): String {
        try {
            if (input == null || input.length > 2048) {
                throw IllegalArgumentException()
            }
            // Java String.strip semantics (Character.isWhitespace); Kotlin's trim also treats NBSP as blank.
            val uri = URI.create(input.trim { Character.isWhitespace(it) })
            if (!"https".equals(uri.scheme, ignoreCase = true) ||
                uri.userInfo != null ||
                (uri.port != -1 && uri.port != 443)
            ) {
                throw IllegalArgumentException()
            }
            val host = if (uri.host == null) "" else uri.host.lowercase(Locale.ROOT)
            val path = uri.path
            var candidate: String? = null
            if ("youtu.be" == host) {
                candidate = path.substring(1)
            } else if (HOSTS.contains(host)) {
                if ("/watch" == path && uri.rawQuery != null) {
                    val ids = uri.rawQuery.split("&")
                        .map { part -> part.split("=", limit = 2) }
                        .filter { pair -> pair.size == 2 && pair[0] == "v" }
                        .map { pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8) }
                    if (ids.size == 1) {
                        candidate = ids[0]
                    }
                } else if (path.startsWith("/shorts/") || path.startsWith("/embed/")) {
                    candidate = path.substring(path.lastIndexOf('/') + 1)
                    // Java String.split drops trailing empty strings; Kotlin split keeps them.
                    if (path.split("/").dropLastWhile { it.isEmpty() }.size != 3) {
                        throw IllegalArgumentException()
                    }
                }
            }
            if (candidate == null || !VIDEO_ID.matches(candidate)) {
                throw IllegalArgumentException()
            }
            return candidate
        } catch (failure: IllegalArgumentException) {
            throw badRequest()
        } catch (failure: IndexOutOfBoundsException) {
            throw badRequest()
        }
    }

    private fun badRequest(): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 HTTPS YouTube 영상 주소를 입력해 주세요.")
}
