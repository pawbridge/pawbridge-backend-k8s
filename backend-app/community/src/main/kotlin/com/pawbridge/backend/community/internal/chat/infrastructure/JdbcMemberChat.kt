package com.pawbridge.backend.community.internal.chat.infrastructure

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.ExistingRoom
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Message
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.MessagePage
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Notifications
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Receipt
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Room
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.RoomPage
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Send
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMemberUnavailableException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcMemberChat(
    private val repository: JdbcMemberChatRepository,
    private val contact: NoteSendStore,
    private val users: ContactMemberQuery,
    private val signals: MemberChatHints,
    private val clock: Clock,
    private val transactions: TransactionOperations,
) : MemberChat {

    override fun send(sender: Long, input: Send?): Receipt {
        val request = validate(sender, input)
        val hash = hash(request)
        val recipientId = request.recipientId!!
        return transactions.execute {
            // Shared with private-note block and withdrawal; always ascending member IDs.
            contact.lockMembers(sender, recipientId)
            active(sender)
            active(recipientId)
            val now = clock.instant()
            val previous = repository.request(sender, request.requestId!!, now)
            if (previous.isPresent) {
                if (hash != previous.get().hash) {
                    throw error(HttpStatus.CONFLICT, "전송 키의 내용이 다릅니다.")
                }
                if (!previous.get().retained) {
                    throw error(HttpStatus.GONE, "보관 기간이 지난 전송입니다.")
                }
                return@execute previous.get().receipt
            }
            if (contact.blocked(sender, recipientId)) {
                throw error(HttpStatus.FORBIDDEN, "차단된 상대에게 메시지를 보낼 수 없습니다.")
            }
            if (repository.rate(sender, now.minusSeconds(60)) >= 60 ||
                repository.rate(sender, now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant()) >= 1000
            ) {
                throw error(HttpStatus.TOO_MANY_REQUESTS, "메시지 전송 한도에 도달했습니다. 잠시 후 다시 이용하세요.")
            }
            if (request.contextType != null &&
                !contact.contextAuthor(request.contextType, request.contextId!!, recipientId)
            ) {
                throw error(HttpStatus.BAD_REQUEST, "관련 글의 작성자를 확인해 주세요.")
            }
            val room = repository.roomForSend(sender, recipientId)
            val receipt = repository.insert(
                sender,
                request,
                room,
                hash,
                now,
                now.atZone(ZoneOffset.UTC).plusYears(1).toInstant(),
            )
            afterCommit(
                Runnable {
                    signals.publish(Signal(sender, "SENT", room, receipt.sequence))
                    signals.publish(Signal(recipientId, "MESSAGE", room, receipt.sequence))
                },
            )
            receipt
        }!!
    }

    override fun existingRoom(owner: Long, counterpartId: Long): ExistingRoom {
        active(owner)
        if (counterpartId <= 0 || counterpartId == owner) {
            throw error(HttpStatus.BAD_REQUEST, "대화 상대를 확인해 주세요.")
        }
        // Looking up a peer never creates a room or changes its hidden/read state.
        return ExistingRoom(repository.existingRoom(owner, counterpartId).orElse(null))
    }

    override fun rooms(owner: Long, cursor: UUID?): RoomPage {
        active(owner)
        val found = repository.rooms(owner, cursor, false, clock.instant())
        val page = summaries(owner, found, true)
        return RoomPage(page, if (found.size > 10) page[9].roomId else null)
    }

    override fun room(owner: Long, room: UUID?): Room {
        active(owner)
        val member = membership(owner, room)
        val counterpart = counterpart(member.counterpartId)
        val last = repository.latest(room, clock.instant()).orElse(null)
        return Room(
            room,
            if (counterpart.active) counterpart.userId else null,
            counterpart.nickname,
            member.latestSequence,
            member.readThrough,
            member.counterpartReadThrough,
            repository.unreadRoom(owner, room, clock.instant()),
            counterpart.active && !contact.blocked(owner, counterpart.userId!!),
            last?.createdAt,
            last?.body,
        )
    }

    override fun notifications(owner: Long, cursor: UUID?): Notifications {
        active(owner)
        val found = repository.rooms(owner, cursor, true, clock.instant())
        // Notifications deliberately omit message previews.
        val page = summaries(owner, found, false)
        return Notifications(
            page,
            if (found.size > 10) page[9].roomId else null,
            repository.unreadRooms(owner, clock.instant()),
        )
    }

    private fun summaries(
        owner: Long,
        found: List<JdbcMemberChatRepository.Summary>,
        preview: Boolean,
    ): List<Room> {
        val result = ArrayList<Room>()
        for (summary in found.subList(0, Math.min(found.size, 10))) {
            val member = summary.membership
            val counterpart = counterpart(member.counterpartId)
            result.add(
                Room(
                    member.roomId,
                    if (counterpart.active) counterpart.userId else null,
                    counterpart.nickname,
                    member.latestSequence,
                    member.readThrough,
                    member.counterpartReadThrough,
                    summary.unread,
                    counterpart.active && !contact.blocked(owner, counterpart.userId!!),
                    summary.updatedAt,
                    if (preview) summary.preview else null,
                ),
            )
        }
        return java.util.List.copyOf(result)
    }

    override fun messages(owner: Long, room: UUID?, before: Long?, after: Long?): MessagePage {
        active(owner)
        if ((before != null && before <= 0) ||
            (after != null && after < 0) ||
            (before != null && after != null)
        ) {
            throw error(HttpStatus.BAD_REQUEST, "메시지 조회 범위를 확인해 주세요.")
        }
        val membership = membership(owner, room)
        val found = repository.messages(room, before, after, clock.instant())
        val page = ArrayList(found.subList(0, Math.min(found.size, 50)))
        if (after == null) {
            Collections.reverse(page)
        }
        val cursor: Long? = if (found.size > 50) {
            if (after == null) page[0].sequence else page[49].sequence
        } else {
            null
        }
        return MessagePage(java.util.List.copyOf(page), cursor, membership.counterpartReadThrough)
    }

    override fun read(owner: Long, room: UUID?, through: Long) {
        transactions.executeWithoutResult {
            contact.lockMembers(owner, owner)
            active(owner)
            val member = membership(owner, room)
            if (through < 0 || through > member.latestSequence) {
                throw error(HttpStatus.BAD_REQUEST, "확인한 메시지 범위를 확인해 주세요.")
            }
            repository.read(owner, room, through)
            afterCommit(
                Runnable {
                    signals.publish(Signal(owner, "READ", room, through))
                    if (member.counterpartId != null) {
                        signals.publish(Signal(member.counterpartId, "READ", room, through))
                    }
                },
            )
        }
    }

    override fun hide(owner: Long, room: UUID?) {
        transactions.executeWithoutResult {
            contact.lockMembers(owner, owner)
            active(owner)
            membership(owner, room)
            repository.hide(owner, room)
            afterCommit(Runnable { signals.publish(Signal(owner, "HIDDEN", room, 0)) })
        }
    }

    override fun active(memberId: Long): ContactMember {
        if (memberId <= 0) {
            throw error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")
        }
        val member = lookup(memberId)
        if (!member.active) {
            throw error(HttpStatus.FORBIDDEN, "이용할 수 없는 회원입니다.")
        }
        return member
    }

    private fun counterpart(id: Long?): ContactMember {
        val member = if (id == null) null else lookup(id)
        return if (member == null || !member.active) {
            ContactMember(null, "탈퇴한 회원", false, false)
        } else {
            member
        }
    }

    private fun lookup(id: Long): ContactMember {
        try {
            return users.get(id)
        } catch (unavailable: ContactMemberUnavailableException) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "회원 정보를 확인할 수 없습니다.")
        }
    }

    private fun membership(owner: Long, room: UUID?): JdbcMemberChatRepository.Membership =
        repository.membership(owner, room).orElseThrow { error(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다.") }

    override fun expire() {
        if (repository.installed()) {
            transactions.executeWithoutResult { repository.expire(clock.instant()) }
        }
    }

    private fun afterCommit(action: Runnable) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    action.run()
                }
            },
        )
    }

    // The package-private statics of the Java class are public here so tests in the package can still call them.
    companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        @JvmStatic
        fun validate(sender: Long, input: Send?): Send {
            if (input == null ||
                input.recipientId == null ||
                input.recipientId <= 0 ||
                input.recipientId == sender ||
                input.requestId == null ||
                input.body == null
            ) {
                throw error(HttpStatus.BAD_REQUEST, "받는 사람과 메시지를 확인해 주세요.")
            }
            // Java String.strip uses Character.isWhitespace; Kotlin's trim() would also strip NBSP.
            val body = input.body.trim { character -> Character.isWhitespace(character) }
            if (body.isEmpty() ||
                body.codePointCount(0, body.length) > 2000 ||
                body.indexOf('\u0000') >= 0 ||
                containsSurrogateCodePoint(body)
            ) {
                throw error(HttpStatus.BAD_REQUEST, "메시지는 1~2,000자로 입력해 주세요.")
            }
            if ((input.contextType == null) != (input.contextId == null) ||
                (
                    input.contextType != null &&
                        (!listOf("POST", "REPORT").contains(input.contextType) || input.contextId!! <= 0)
                    )
            ) {
                throw error(HttpStatus.BAD_REQUEST, "관련 글 정보를 확인해 주세요.")
            }
            return Send(input.recipientId, input.requestId, body, input.contextType, input.contextId)
        }

        // Equivalent of body.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF): only unpaired surrogates match.
        private fun containsSurrogateCodePoint(body: String): Boolean {
            var index = 0
            while (index < body.length) {
                val codePoint = body.codePointAt(index)
                if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
                    return true
                }
                index += Character.charCount(codePoint)
            }
            return false
        }

        @JvmStatic
        fun hash(input: Send): String {
            try {
                val canonical = "${input.recipientId}\n${input.contextType}\n${input.contextId}\n${input.body}"
                return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException(impossible)
            }
        }

        @JvmStatic
        fun error(status: HttpStatus, reason: String): MemberChatRejectedException =
            MemberChatRejectedException(status.value(), reason)
    }
}
