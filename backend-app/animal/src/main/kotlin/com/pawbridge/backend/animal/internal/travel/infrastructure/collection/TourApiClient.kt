package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import org.springframework.web.util.HtmlUtils
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TourApiClient internal constructor(
    private val properties: TourApiProperties,
    private val mapper: ObjectMapper,
    private val http: HttpClient,
) {

    constructor(properties: TourApiProperties, mapper: ObjectMapper) : this(
        properties,
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    )

    enum class Operation(
        // Package-private fields in Java; the same-package tests read them directly.
        @JvmField internal val path: String,
        @JvmField internal val rows: Int,
        @JvmField internal val service: Service,
    ) {
        REGIONS("ldongCode2", 50, Service.PET),
        PLACES("areaBasedList2", 10, Service.PET),
        COMMON("detailCommon2", 1, Service.KOREAN),
        PET("detailPetTour2", 1, Service.KOREAN),
        INTRO("detailIntro2", 1, Service.KOREAN),
        INFO("detailInfo2", 100, Service.KOREAN),
        IMAGES("detailImage2", 100, Service.KOREAN),
        SYNC("petTourSyncList2", 100, Service.PET),
        PET_BULK("detailPetTour2", 100, Service.KOREAN),
    }

    internal enum class Service(@JvmField internal val base: String) {
        PET(PET_BASE),
        KOREAN(KOREAN_BASE),
    }

    @JvmRecord
    @JvmSuppressWildcards
    data class Page(val items: List<Map<String, String>>, val totalCount: Int)

    fun fetch(operation: Operation, argument: String?): List<Map<String, String>> {
        return fetchPage(operation, argument, null, 1).items
    }

    fun fetchDetail(operation: Operation, contentId: String?, contentTypeId: String?): List<Map<String, String>> {
        if (operation != Operation.INTRO && operation != Operation.INFO && operation != Operation.IMAGES) {
            throw PetTravelException.unavailable()
        }
        val page = fetchPage(operation, contentId, contentTypeId, 1)
        if (page.totalCount != page.items.size) {
            throw PetTravelException.unavailable()
        }
        return page.items
    }

    fun fetchPage(operation: Operation, argument: String?, pageNo: Int): Page {
        return fetchPage(operation, argument, null, pageNo)
    }

    private fun fetchPage(operation: Operation, argument: String?, contentTypeId: String?, pageNo: Int): Page {
        var pending: CompletableFuture<HttpResponse<ByteArray>>? = null
        try {
            if (!properties.isEnabled) {
                throw PetTravelException.unavailable()
            }
            if (operation == Operation.PET_BULK && !properties.isBulkPetEnabled) {
                throw PetTravelException.unavailable()
            }
            val key = decodedKey(
                if (operation.service == Service.KOREAN) properties.koreanServiceKey else properties.serviceKey,
            )
            val query = LinkedHashMap<String, String>()
            query["serviceKey"] = key
            query["MobileOS"] = "ETC"
            query["MobileApp"] = "PawBridge"
            query["_type"] = "json"
            if (pageNo < 1 || pageNo > 100000) {
                throw PetTravelException.unavailable()
            }
            query["pageNo"] = pageNo.toString()
            query["numOfRows"] = operation.rows.toString()
            if (operation == Operation.SYNC) {
                if (argument == null || ("0" != argument && "1" != argument)) {
                    throw PetTravelException.unavailable()
                }
                query["showflag"] = argument
                query["arrange"] = "A"
            } else if (operation == Operation.PLACES) {
                if (argument == null || !PLACE_AREA_CODE.matches(argument)) {
                    throw PetTravelException.unavailable()
                }
                query["areaCode"] = argument
                query["arrange"] = "A"
            } else if (operation != Operation.REGIONS && operation != Operation.PET_BULK) {
                if (argument == null || !CONTENT_ID.matches(argument)) {
                    throw PetTravelException.unavailable()
                }
                query["contentId"] = argument
                if (operation == Operation.INTRO || operation == Operation.INFO) {
                    if (contentTypeId == null || !CONTENT_TYPE_ID.matches(contentTypeId)) {
                        throw PetTravelException.unavailable()
                    }
                    query["contentTypeId"] = contentTypeId
                }
                if (operation == Operation.IMAGES) {
                    query["imageYN"] = "Y"
                }
            }
            val encoded = query.entries.joinToString("&") { entry -> encode(entry.key) + "=" + encode(entry.value) }
            val request = HttpRequest.newBuilder(URI.create(operation.service.base + operation.path + "?" + encoded))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build()
            val started = http.sendAsync(request, HttpResponse.BodyHandler { LimitedBody() })
            pending = started
            // Includes body consumption; a slow response body cannot hold a caller indefinitely.
            val response = started.get(6, TimeUnit.SECONDS)
            if (response.statusCode() != 200) {
                throw PetTravelException.unavailable()
            }
            val items = parse(response.body(), operation, key)
            var total = items.size
            if (operation == Operation.SYNC || operation == Operation.PET_BULK ||
                operation == Operation.INTRO || operation == Operation.INFO || operation == Operation.IMAGES
            ) {
                val count = mapper.readTree(response.body()).path("response").path("body").path("totalCount")
                if (!TOTAL_COUNT.matches(count.asText())) {
                    throw PetTravelException.unavailable()
                }
                total = Integer.parseInt(count.asText())
                if (total < items.size) {
                    throw PetTravelException.unavailable()
                }
            }
            return Page(items, total)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PetTravelException.unavailable()
        } catch (exception: Exception) {
            // Never retain a cause: HTTP exceptions may include the credential-bearing URI.
            throw PetTravelException.unavailable()
        } finally {
            if (pending != null && !pending.isDone) {
                pending.cancel(true)
            }
        }
    }

    private fun decodedKey(raw: String): String {
        try {
            val key = URLDecoder.decode(javaTrim(raw).replace("+", "%2B"), StandardCharsets.UTF_8)
            if (!KEY_FORMAT.matches(key)) {
                throw PetTravelException.unavailable()
            }
            return key
        } catch (exception: RuntimeException) {
            throw PetTravelException.unavailable()
        }
    }

    // Public (package-private in Java) because the Java tests call it directly.
    fun parse(bytes: ByteArray, operation: Operation, key: String): List<Map<String, String>> {
        try {
            if (bytes.size > MAX_BYTES) {
                throw PetTravelException.unavailable()
            }
            val response = mapper.readTree(bytes).path("response")
            if ("0000" != response.path("header").path("resultCode").asText()) {
                throw PetTravelException.unavailable()
            }
            val body = response.path("body")
            if (!body.isObject) {
                throw PetTravelException.unavailable()
            }
            val items = body.path("items")
            if (items.isMissingNode || items.isNull || (items.isTextual && items.asText().isEmpty()) ||
                (items.isObject && items.isEmpty)
            ) {
                return emptyList()
            }
            if (!items.isObject) {
                throw PetTravelException.unavailable()
            }
            val records = items.path("item")
            val rows = ArrayList<JsonNode>()
            if (records.isObject) {
                rows.add(records)
            } else if (records.isArray) {
                records.forEach { record -> rows.add(record) }
            } else {
                throw PetTravelException.unavailable()
            }
            if (rows.size > operation.rows) {
                throw PetTravelException.unavailable()
            }
            val result = ArrayList<Map<String, String>>()
            for (row in rows) {
                if (!row.isObject) {
                    throw PetTravelException.unavailable()
                }
                val values = LinkedHashMap<String, String>()
                for (field in FIELDS) {
                    val value = row.path(field)
                    if (value.isTextual || value.isNumber) {
                        values[field] = clean(value.asText(), key)
                    }
                }
                if (operation == Operation.REGIONS) {
                    if (!REGION_CODE.matches(values.getOrDefault("code", "")) || isBlank(values.getOrDefault("name", ""))) {
                        throw PetTravelException.unavailable()
                    }
                } else if (!CONTENT_ID.matches(values.getOrDefault("contentid", "")) ||
                    (
                        (operation == Operation.PLACES || operation == Operation.COMMON) &&
                            isBlank(values.getOrDefault("title", ""))
                        )
                ) {
                    throw PetTravelException.unavailable()
                }
                result.add(java.util.Map.copyOf(values))
            }
            if (operation == Operation.PET_BULK &&
                result.map { row -> row["contentid"] }.distinct().size.toLong() != result.size.toLong()
            ) {
                throw PetTravelException.unavailable()
            }
            if ((operation == Operation.INTRO || operation == Operation.INFO || operation == Operation.IMAGES) &&
                result.any { row -> row["contentid"]!! != result[0]["contentid"] }
            ) {
                throw PetTravelException.unavailable()
            }
            if (operation == Operation.IMAGES &&
                result.map { row -> row["serialnum"] }.any { value -> value == null || !SERIAL_NUMBER.matches(value) }
            ) {
                throw PetTravelException.unavailable()
            }
            if (operation == Operation.IMAGES &&
                result.map { row -> row["serialnum"] }.distinct().size.toLong() != result.size.toLong()
            ) {
                throw PetTravelException.unavailable()
            }
            return java.util.List.copyOf(result)
        } catch (exception: Exception) {
            throw PetTravelException.unavailable()
        }
    }

    private fun clean(value: String, key: String): String {
        var text = HtmlUtils.htmlUnescape(value)
        for (secret in listOf(key, encode(key))) {
            text = Pattern.compile(Pattern.quote(secret), Pattern.CASE_INSENSITIVE).matcher(text).replaceAll("[REDACTED]")
        }
        // This is plain text, never trusted HTML. Callers must render as text too.
        val withoutMarkup = MARKUP.replace(text, " ")
        val withoutControls = CONTROL_CHARACTERS.replace(withoutMarkup, " ")
        text = javaStrip(withoutControls)
        // Reject unusually large fields instead of silently dropping a restriction at the end.
        if (text.length > 10000) {
            throw PetTravelException.unavailable()
        }
        return text
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

    // Java String.trim() removes every character up to U+0020; Kotlin trim() uses Character.isWhitespace.
    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    // Java String.strip() removes Character.isWhitespace characters from both ends.
    private fun javaStrip(value: String): String = value.trim { Character.isWhitespace(it) }

    internal class LimitedBody : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate: HttpResponse.BodySubscriber<ByteArray> = HttpResponse.BodySubscribers.ofByteArray()
        private var subscription: Flow.Subscription? = null
        private var received = 0
        private var failed = false

        override fun getBody(): CompletionStage<ByteArray> = delegate.body

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            delegate.onSubscribe(subscription)
        }

        override fun onNext(buffers: List<ByteBuffer>) {
            if (failed) {
                return
            }
            val incoming = buffers.sumOf { buffer -> buffer.remaining().toLong() }
            if (received + incoming > MAX_BYTES) {
                failed = true
                subscription!!.cancel()
                delegate.onError(PetTravelException.unavailable())
                return
            }
            received += incoming.toInt()
            delegate.onNext(buffers)
        }

        override fun onError(throwable: Throwable) {
            delegate.onError(PetTravelException.unavailable())
        }

        override fun onComplete() {
            if (!failed) {
                delegate.onComplete()
            }
        }
    }

    companion object {
        const val MAX_BYTES: Int = 1024 * 1024
        const val PET_BASE: String = "https://apis.data.go.kr/B551011/KorPetTourService2/"
        const val KOREAN_BASE: String = "https://apis.data.go.kr/B551011/KorService2/"

        private val PLACE_AREA_CODE = Regex("[0-9]{1,2}")
        private val REGION_CODE = Regex("[0-9]{2,5}")
        private val CONTENT_ID = Regex("[0-9]{1,20}")
        private val CONTENT_TYPE_ID = Regex("12|14|28")
        private val TOTAL_COUNT = Regex("[0-9]{1,8}")
        private val KEY_FORMAT = Regex("[A-Za-z0-9+/=_-]{16,2048}")
        private val SERIAL_NUMBER = Regex("[A-Za-z0-9_-]{1,32}")
        private val MARKUP = Regex("(?is)</?[a-z][a-z0-9]*(?:\\s[^<>]*)?/?>|<!--.*?-->")
        private val CONTROL_CHARACTERS = Regex("[\\p{Cntrl}]")

        private val FIELDS = setOf(
            "code", "name", "contentid", "title", "addr1", "overview", "firstimage", "cpyrhtDivCd",
            "acmpyTypeCd", "acmpyPsblCpam", "acmpyNeedMtr", "etcAcmpyInfo",
            "relaAcdntRiskMtr", "relaPosesFclty", "relaFrnshPrdlst", "areacode", "sigungucode",
            "modifiedtime", "showflag", "addr2", "contenttypeid", "mapx", "mapy", "lDongRegnCd", "lDongSignguCd",
            "chkbabycarriage", "chkcreditcard", "heritage1", "heritage2", "heritage3", "infocenter", "opendate",
            "parking", "restdate", "usetime", "expagerange", "expguide", "useseason",
            "chkbabycarriageculture", "chkcreditcardculture", "discountinfo", "infocenterculture", "parkingculture",
            "parkingfee", "restdateculture", "usefee", "usetimeculture", "scale", "spendtime",
            "chkcreditcardleports", "expagerangeleports", "infocenterleports", "openperiod", "parkingleports",
            "parkingfeeleports", "reservation", "restdateleports", "scaleleports", "usefeeleports",
            "usetimeleports", "infoname", "infotext", "fldgubun",
            "serialnum", "imgname", "originimgurl", "smallimageurl",
        )
    }
}
