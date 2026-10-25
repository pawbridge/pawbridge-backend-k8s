package com.pawbridge.backend.community.internal.note.application

import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException.Reason
import com.pawbridge.backend.user.api.ContactMember
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.Objects
import java.util.UUID
import java.util.function.Supplier

class SendPrivateNote(
    private val members: ContactMemberQuery,
    private val store: NoteSendStore,
    private val clock: Clock,
    private val noteIds: Supplier<UUID?>,
) {

    fun send(
        currentUserId: Long,
        request: SendNote?,
    ): Receipt {
        requireId(currentUserId)
        val body = validate(request, currentUserId)
        // validate() rejects a null request, so every field read below belongs to a checked request.
        val command = request!!
        val recipientId = command.recipientId!!
        val requestId = command.requestId!!
        val requestHash = hash(command, body)
        return store.inTransaction(
            Supplier<Receipt> {
                store.lockMembers(currentUserId, recipientId)
                val sender = active(currentUserId)
                active(recipientId)
                val previousRequest = store.request(currentUserId, requestId)
                if (previousRequest.isPresent) {
                    val previous = previousRequest.get()
                    if (previous.noteId == null || previous.hash == null) {
                        throw rejected(Reason.GONE)
                    }
                    if (requestHash != previous.hash) {
                        throw rejected(Reason.CONFLICT)
                    }
                    return@Supplier Receipt(previous.noteId)
                }
                if (store.blocked(currentUserId, recipientId)) {
                    throw rejected(Reason.BLOCKED)
                }
                val now = clock.instant()
                val startOfKoreanDay = now.atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL).toInstant()
                if (store.rate(currentUserId, now.minusSeconds(60)) >= 10 ||
                    store.rate(currentUserId, startOfKoreanDay) >= 100
                ) {
                    throw rejected(Reason.RATE_LIMIT)
                }
                if (command.replyTo != null) {
                    val parent = store.reply(currentUserId, command.replyTo, clock.instant())
                        .orElseThrow { rejected(Reason.NOT_FOUND) }
                    if ("INBOX" != parent.direction || !Objects.equals(parent.senderId, recipientId)) {
                        throw rejected(Reason.REPLY_MISMATCH)
                    }
                }
                if (command.contextType != null &&
                    !store.contextAuthor(command.contextType, command.contextId!!, recipientId)
                ) {
                    throw rejected(Reason.CONTEXT_AUTHOR)
                }
                val noteId = noteIds.get()
                val expiresAt = now.atZone(ZoneOffset.UTC).plusYears(1).toInstant()
                val note = NoteSendStore.NewNote(
                    noteId,
                    currentUserId,
                    recipientId,
                    body,
                    command.replyTo,
                    command.contextType,
                    command.contextId,
                    requestId,
                    requestHash,
                    now,
                    expiresAt,
                )
                val notification = Notification(
                    noteId,
                    "PRIVATE_NOTE",
                    sender.userId,
                    sender.nickname,
                    now,
                    false,
                    "/notes/" + noteId,
                )
                store.insert(note, notification)
                Receipt(noteId)
            },
        )
    }

    private fun active(userId: Long): ContactMember {
        requireId(userId)
        val member = members.get(userId)
        if (!member.active) {
            throw rejected(Reason.INACTIVE)
        }
        return member
    }

    @JvmRecord
    data class SendNote(
        val recipientId: Long?,
        val body: String?,
        val requestId: UUID?,
        val replyTo: UUID?,
        val contextType: String?,
        val contextId: Long?,
    )

    @JvmRecord
    data class Receipt(
        val noteId: UUID?,
    )

    private companion object {
        private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        private fun validate(
            request: SendNote?,
            currentUserId: Long,
        ): String {
            if (request == null ||
                request.recipientId == null ||
                request.recipientId <= 0 ||
                request.recipientId == currentUserId ||
                request.requestId == null ||
                request.body == null
            ) {
                throw rejected(Reason.INVALID_REQUEST)
            }
            // Java String.strip / isBlank use Character.isWhitespace; Kotlin's trim / isBlank also treat NBSP as blank.
            val body = request.body.trim { character -> Character.isWhitespace(character) }
            if (body.all { character -> Character.isWhitespace(character) } ||
                body.codePointCount(0, body.length) > 5000 ||
                body.indexOf('\u0000') >= 0
            ) {
                throw rejected(Reason.INVALID_BODY)
            }
            val incompleteContext = (request.contextType == null) != (request.contextId == null)
            val invalidContext = request.contextType != null &&
                request.contextId != null &&
                (!listOf("POST", "REPORT").contains(request.contextType) || request.contextId <= 0)
            if (incompleteContext || invalidContext) {
                throw rejected(Reason.INVALID_CONTEXT)
            }
            return body
        }

        private fun hash(
            request: SendNote,
            body: String,
        ): String {
            val payload = request.recipientId.toString() + "\n" + request.replyTo + "\n" +
                request.contextType + "\n" + request.contextId + "\n" + body
            try {
                return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(StandardCharsets.UTF_8)),
                )
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException(impossible)
            }
        }

        private fun requireId(userId: Long) {
            if (userId <= 0) {
                throw rejected(Reason.LOGIN)
            }
        }

        private fun rejected(reason: Reason): NoteSendRejectedException = NoteSendRejectedException(reason)
    }
}
