package com.pawbridge.backend.community.internal.report.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.report.application.AnimalReports
import com.pawbridge.backend.community.internal.report.application.AnimalReports.AnimalType
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Details
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Draft
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Filter
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Kind
import com.pawbridge.backend.community.internal.report.application.AnimalReports.Slice
import com.pawbridge.backend.user.api.UserNicknameQuery
import java.io.IOException
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.server.ResponseStatusException

/** Minimal JDBC translation of the existing JPA report workflow and visible-row predicates. */
class JdbcAnimalReports(
    private val reader: JdbcOperations,
    private val writer: JdbcOperations,
    private val readTransaction: TransactionOperations,
    private val writeTransaction: TransactionOperations,
    private val users: UserNicknameQuery,
    private val uploads: PostMediaUploader,
    private val media: PostMediaRemover,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : AnimalReports {

    override fun list(filter: Filter): Slice {
        // Controller pagination checks precede the original service classification/date checks.
        if (filter.page < 0 ||
            filter.size < 1 ||
            filter.size > 50 ||
            (filter.keyword != null && filter.keyword.length > 100)
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 범위를 확인해 주세요.")
        }
        validateClassification(filter.province, filter.district)
        val today = today()
        val from = filter.from
        val to = filter.to
        if ((from != null && from.isAfter(today)) ||
            (to != null && to.isAfter(today)) ||
            (from != null && to != null && from.isAfter(to))
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "실종·목격 날짜 범위를 확인해 주세요.")
        }
        val keyword = if (filter.keyword == null || isBlank(filter.keyword)) {
            ""
        } else {
            javaTrim(filter.keyword).lowercase(Locale.ROOT)
        }
        val where = StringBuilder(" WHERE deleted_at IS NULL")
        val args = ArrayList<Any>()
        equal(where, args, "report_kind", filter.kind?.name)
        equal(where, args, "province", optional(filter.province))
        equal(where, args, "district", optional(filter.district))
        equal(where, args, "animal_type", filter.animalType?.name)
        if (from != null) {
            where.append(" AND occurred_on >= ?")
            args.add(from)
        }
        if (to != null) {
            where.append(" AND occurred_on <= ?")
            args.add(to)
        }
        if (keyword.isNotEmpty()) {
            where.append(
                " AND (position(? in lower(description)) > 0 OR position(? in lower(region)) > 0" +
                    " OR position(? in lower(species)) > 0 OR position(? in lower(animal_name)) > 0" +
                    " OR position(? in lower(distinguishing_features)) > 0)",
            )
            for (index in 0 until 5) {
                args.add(keyword)
            }
        }
        val offset = filter.page.toLong() * filter.size
        val raw = readTransaction.execute {
            if (offset > Int.MAX_VALUE) {
                throw InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)")
            }
            val pageArgs = ArrayList<Any>(args)
            pageArgs.add(filter.size)
            pageArgs.add(offset)
            val content: List<Details> = reader.query(
                "SELECT " + COLUMNS + " FROM " + TABLE + where +
                    " ORDER BY created_at DESC, report_id DESC LIMIT ? OFFSET ?",
                RowMapper { rs, _ -> row(rs) },
                *pageArgs.toTypedArray(),
            )
            // Spring Data avoids a count when a partial nonempty page already proves the total.
            val total = if (content.size < filter.size && (filter.page == 0 || content.isNotEmpty())) {
                offset + content.size
            } else {
                reader.queryForObject(
                    "SELECT count(*) FROM " + TABLE + where,
                    Long::class.javaObjectType,
                    *args.toTypedArray(),
                )!!
            }
            Slice(content, total)
        }!!
        // Keep the User call independent of the Community reader transaction, as in the HTTP boundary.
        return Slice(raw.content.map { details -> nickname(details) }, raw.total)
    }

    override fun get(reportId: Long): Details =
        nickname(readTransaction.execute { find(reader, reportId) }!!)

    // The User nickname read runs after the writer transaction: on the shared pool a failing read inside
    // it would mark the report transaction rollback-only instead of falling back to the placeholder name.
    override fun create(request: Draft?, photos: List<PostUpload?>?, authorId: Long?): Details =
        nickname(
            writeTransaction.execute {
                validate(request, photos)
                if (authorId == null || authorId <= 0) {
                    throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
                }
                // validate() has already rejected null elements, so the unchecked cast is safe.
                @Suppress("UNCHECKED_CAST")
                val images = uploads.upload(photos as List<PostUpload>?)
                try {
                    val draft = request!!
                    val next = normalized(
                        draft,
                        optional(draft.province),
                        optional(draft.district),
                        draft.animalType,
                    )
                    val now = now()
                    val id = writer.queryForObject(
                        "INSERT INTO " + TABLE + " (author_id,report_kind,description,image_urls,occurred_on," +
                            "approximate_time,region,landmark,species,animal_name,coat_color,animal_size," +
                            "distinguishing_features,direction,province,district,animal_type,created_at,updated_at) " +
                            "VALUES (?,?,?,CAST(? AS json),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING report_id",
                        Long::class.javaObjectType,
                        authorId,
                        next.kind!!.name,
                        next.description,
                        json(images),
                        next.occurredOn,
                        next.approximateTime,
                        next.region,
                        next.landmark,
                        next.species,
                        next.animalName,
                        next.coatColor,
                        next.animalSize,
                        next.distinguishingFeatures,
                        next.direction,
                        next.province,
                        next.district,
                        next.animalType?.name,
                        now,
                        now,
                    )
                    if (id == null) {
                        throw IllegalStateException("Report insert returned no identity")
                    }
                    details(id, authorId, next, images, now, now)
                } catch (failure: RuntimeException) {
                    // Original saveAndFlush/body failures clean uploads; proxy commit failures do not.
                    media.remove(images)
                    throw failure
                }
            }!!,
        )

    override fun update(reportId: Long, request: Draft?, photos: List<PostUpload?>?, authorId: Long?): Details =
        nickname(
            writeTransaction.execute {
                validate(request, photos)
                if (photos != null && photos.isNotEmpty()) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "등록 후 사진 교체는 아직 지원하지 않습니다.")
                }
                val draft = request!!
                val current = find(writer, reportId)
                requireAuthor(current, authorId)
                if (current.kind != draft.kind) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제보 종류는 변경할 수 없습니다.")
                }
                val classify = draft.province != null || draft.district != null || draft.animalType != null
                val next = normalized(
                    draft,
                    if (classify) optional(draft.province) else current.province,
                    if (classify) optional(draft.district) else current.district,
                    if (classify) draft.animalType else current.animalType,
                )
                val result = details(
                    reportId,
                    current.authorId!!,
                    next,
                    current.imageUrls!!,
                    current.createdAt,
                    current.updatedAt,
                )
                if (result != current) {
                    writer.update(
                        "UPDATE " + TABLE + " SET description=?,occurred_on=?,approximate_time=?,region=?," +
                            "landmark=?,species=?,animal_name=?,coat_color=?,animal_size=?,distinguishing_features=?," +
                            "direction=?,province=?,district=?,animal_type=?,updated_at=? WHERE report_id=?",
                        next.description,
                        next.occurredOn,
                        next.approximateTime,
                        next.region,
                        next.landmark,
                        next.species,
                        next.animalName,
                        next.coatColor,
                        next.animalSize,
                        next.distinguishingFeatures,
                        next.direction,
                        next.province,
                        next.district,
                        next.animalType?.name,
                        now(),
                        reportId,
                    )
                }
                // JPA auditing flush occurs after the original response is built: retain its prior updatedAt.
                result
            }!!,
        )

    override fun delete(reportId: Long, authorId: Long?) {
        writeTransaction.executeWithoutResult {
            val current = find(writer, reportId)
            requireAuthor(current, authorId)
            writer.update(
                "UPDATE " + TABLE + " SET deleted_at=?,updated_at=? WHERE report_id=?",
                now(),
                now(),
                reportId,
            )
        }
    }

    private fun find(jdbc: JdbcOperations, id: Long): Details {
        val rows = jdbc.query(
            "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE report_id=? AND deleted_at IS NULL",
            RowMapper { rs, _ -> row(rs) },
            id,
        )
        if (rows.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return rows[0]
    }

    private fun row(rs: ResultSet): Details {
        try {
            val draft = Draft(
                Kind.valueOf(rs.getString("report_kind")),
                rs.getObject("occurred_on", LocalDate::class.java),
                rs.getString("approximate_time"),
                rs.getString("region"),
                rs.getString("landmark"),
                rs.getString("species"),
                rs.getString("animal_name"),
                rs.getString("coat_color"),
                rs.getString("animal_size"),
                rs.getString("distinguishing_features"),
                rs.getString("direction"),
                rs.getString("description"),
                rs.getString("province"),
                rs.getString("district"),
                if (rs.getString("animal_type") == null) null else AnimalType.valueOf(rs.getString("animal_type")),
            )
            return details(
                rs.getLong("report_id"),
                rs.getLong("author_id"),
                draft,
                mapper.readValue(rs.getString("image_urls"), IMAGES),
                rs.getObject("created_at", LocalDateTime::class.java),
                rs.getObject("updated_at", LocalDateTime::class.java),
            )
        } catch (failure: IOException) {
            throw IllegalStateException("Stored report media cannot be decoded")
        }
    }

    private fun nickname(details: Details): Details {
        val name = try {
            users.findNickname(details.authorId!!).orElse("사용자" + details.authorId)
        } catch (unavailable: RuntimeException) {
            "사용자" + details.authorId
        }
        return Details(
            details.reportId,
            details.authorId,
            name,
            details.title,
            details.description,
            details.imageUrls,
            details.kind,
            details.occurredOn,
            details.approximateTime,
            details.region,
            details.landmark,
            details.species,
            details.animalName,
            details.coatColor,
            details.animalSize,
            details.distinguishingFeatures,
            details.direction,
            details.createdAt,
            details.updatedAt,
            details.province,
            details.district,
            details.animalType,
        )
    }

    private fun json(values: List<String>): String {
        try {
            return mapper.writeValueAsString(values)
        } catch (failure: IOException) {
            throw IllegalStateException("Report media cannot be encoded")
        }
    }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))

    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())

    private fun validate(request: Draft?, photos: List<PostUpload?>?) {
        if (request == null ||
            request.kind == null ||
            request.occurredOn == null ||
            request.occurredOn.isAfter(today()) ||
            blankOrTooLong(request.region, 120) ||
            blankOrTooLong(request.species, 40) ||
            blankOrTooLong(request.description, 10000)
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "필수 제보 정보를 확인해 주세요.")
        }
        validateClassification(request.province, request.district)
        if (tooLong(request.approximateTime, 40) ||
            tooLong(request.landmark, 200) ||
            tooLong(request.animalName, 80) ||
            tooLong(request.coatColor, 100) ||
            tooLong(request.animalSize, 40) ||
            tooLong(request.distinguishingFeatures, 500) ||
            tooLong(request.direction, 200)
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "제보 내용의 글자 수를 확인해 주세요.")
        }
        if (photos == null) {
            return
        }
        if (photos.size > 5) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 최대 5장까지 첨부할 수 있습니다.")
        }
        for (photo in photos) {
            validatePhoto(photo)
        }
    }

    private companion object {
        private const val MAX_PHOTO_BYTES = 10L * 1024 * 1024
        private val PROVINCES = setOf(
            "서울특별시", "부산광역시", "대구광역시",
            "인천광역시", "광주광역시", "대전광역시", "울산광역시", "세종특별자치시", "경기도",
            "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도",
            "경상북도", "경상남도", "제주특별자치도",
        )
        private const val TABLE = "pawbridge_community.animal_reports"
        private const val COLUMNS = "report_id,author_id,report_kind,description,image_urls,occurred_on," +
            "approximate_time,region,landmark,species,animal_name,coat_color,animal_size," +
            "distinguishing_features,direction,created_at,updated_at,province,district,animal_type"
        private val IMAGES = object : TypeReference<List<String>>() {}

        private fun equal(sql: StringBuilder, args: MutableList<Any>, column: String, value: Any?) {
            if (value != null) {
                sql.append(" AND ").append(column).append(" = ?")
                args.add(value)
            }
        }

        private fun requireAuthor(current: Details, author: Long?) {
            if (author == null || current.authorId != author) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN)
            }
        }

        private fun details(
            id: Long,
            author: Long,
            draft: Draft,
            images: List<String>,
            created: LocalDateTime?,
            updated: LocalDateTime?,
        ): Details {
            val label = if (draft.kind == Kind.MISSING) "실종" else "목격 제보"
            return Details(
                id,
                author,
                null,
                label + " · " + draft.region + " · " + draft.species,
                draft.description,
                java.util.List.copyOf(images),
                draft.kind,
                draft.occurredOn,
                draft.approximateTime,
                draft.region,
                draft.landmark,
                draft.species,
                draft.animalName,
                draft.coatColor,
                draft.animalSize,
                draft.distinguishingFeatures,
                draft.direction,
                created,
                updated,
                draft.province,
                draft.district,
                draft.animalType,
            )
        }

        private fun normalized(draft: Draft, province: String?, district: String?, type: AnimalType?): Draft =
            Draft(
                draft.kind,
                draft.occurredOn,
                optional(draft.approximateTime),
                javaTrim(draft.region!!),
                optional(draft.landmark),
                javaTrim(draft.species!!),
                optional(draft.animalName),
                optional(draft.coatColor),
                optional(draft.animalSize),
                optional(draft.distinguishingFeatures),
                optional(draft.direction),
                javaTrim(draft.description!!),
                province,
                district,
                type,
            )

        private fun validateClassification(province: String?, district: String?) {
            val normalizedProvince = optional(province)
            val normalizedDistrict = optional(district)
            if ((normalizedProvince != null && !PROVINCES.contains(normalizedProvince)) ||
                tooLong(district, 40) ||
                (normalizedDistrict != null && normalizedProvince == null)
            ) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "시·도와 시·군·구 선택을 확인해 주세요.")
            }
        }

        private fun optional(value: String?): String? =
            if (value == null || isBlank(value)) null else javaTrim(value)

        private fun blankOrTooLong(value: String?, max: Int): Boolean =
            value == null || isBlank(value) || javaTrim(value).length > max

        private fun tooLong(value: String?, max: Int): Boolean =
            value != null && javaTrim(value).length > max

        // Java String.isBlank semantics (Character.isWhitespace); Kotlin's isBlank also treats NBSP as blank.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        // Java String.trim semantics (characters <= U+0020); Kotlin's trim also strips NBSP and similar.
        private fun javaTrim(value: String): String = value.trim { it <= ' ' }

        private fun validatePhoto(photo: PostUpload?) {
            if (photo == null || photo.size == 0L || photo.size > MAX_PHOTO_BYTES) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 각 10MB 이하로 첨부해 주세요.")
            }
            val type = photo.contentType
            val name = photo.originalFilename
            if (type == null || name == null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 형식을 확인해 주세요.")
            }
            val lower = name.lowercase(Locale.ROOT)
            val extensionMatches = when (type.lowercase(Locale.ROOT)) {
                "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                "image/png" -> lower.endsWith(".png")
                "image/webp" -> lower.endsWith(".webp")
                else -> false
            }
            if (!extensionMatches) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "JPG, PNG, WebP 사진만 첨부할 수 있습니다.")
            }
            try {
                photo.content.open().use { stream ->
                    val header = stream.readNBytes(12)
                    val jpeg = type.equals("image/jpeg", ignoreCase = true) &&
                        header.size >= 3 &&
                        unsigned(header[0]) == 0xff &&
                        unsigned(header[1]) == 0xd8 &&
                        unsigned(header[2]) == 0xff
                    val png = type.equals("image/png", ignoreCase = true) &&
                        header.size >= 8 &&
                        unsigned(header[0]) == 0x89 &&
                        header[1] == 'P'.code.toByte() &&
                        header[2] == 'N'.code.toByte() &&
                        header[3] == 'G'.code.toByte() &&
                        header[4].toInt() == 13 &&
                        header[5].toInt() == 10 &&
                        unsigned(header[6]) == 0x1a &&
                        header[7].toInt() == 10
                    val webp = type.equals("image/webp", ignoreCase = true) &&
                        header.size >= 12 &&
                        header[0] == 'R'.code.toByte() &&
                        header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() &&
                        header[3] == 'F'.code.toByte() &&
                        header[8] == 'W'.code.toByte() &&
                        header[9] == 'E'.code.toByte() &&
                        header[10] == 'B'.code.toByte() &&
                        header[11] == 'P'.code.toByte()
                    if (!jpeg && !png && !webp) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 파일의 실제 형식을 확인해 주세요.")
                    }
                }
            } catch (failure: IOException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 읽을 수 없습니다.", failure)
            }
        }

        private fun unsigned(value: Byte): Int = value.toInt() and 0xff
    }
}
