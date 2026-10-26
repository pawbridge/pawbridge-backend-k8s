package com.pawbridge.backend.community.internal.post.application

import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import com.pawbridge.backend.user.api.UserNicknameQuery
import com.pawbridge.backend.user.api.UserNicknameUnavailableException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.util.Optional

class UpdatePostTest {
    private val reader = mockk<PostReader>()
    private val remover = mockk<PostMediaRemover>()
    private val uploader = mockk<PostMediaUploader>()
    private val users = mockk<UserNicknameQuery>()
    private val writer = mockk<PostModification>()
    private val update = UpdatePost(reader, remover, uploader, users, writer)
    private val time = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val previous = PostSnapshot(42, 7, "제목", "본문", "MISSING", listOf("https://fixture.invalid/old"), time, time)

    @Test
    fun `다른 작성자의 게시글일 때__관리자로 수정하면__원작성자를 유지하고 같은 저장 경로를 미디어 없이 재사용한다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(previous)
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } returns previous

        // when
        val result = update.updateAsAdmin(42, "admin edit", null, listOf())

        // then
        assertThat(result.authorId).isEqualTo(7)
        assertThat(result.authorNickname).isEqualTo("작성자")
        verifyOrder {
            reader.findById(42)
            users.findNickname(7)
            writer.update(42, 7, "admin edit", null, null)
        }
        confirmVerified(reader, users, writer, remover, uploader)
    }

    @Test
    fun `파일이 생략되고 필드가 null일 때__수정하면__미디어 없이 null 패치를 넘기고 실종 게시판을 유지한다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(previous)
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } returns previous

        // when
        val response = update.update(42, 7, null, null, listOf())

        // then
        assertThat(response.authorNickname).isEqualTo("작성자")
        assertThat(response.boardType).isEqualTo("MISSING")
        verify(exactly = 1) { writer.update(42, 7, null, null, null) }
        confirmVerified(remover, uploader)
    }

    @Test
    fun `빈 파일 하나와 명시적 빈 문자열이 있을 때__수정하면__삭제와 업로드와 조회와 저장을 순서대로 한다`() {
        // given
        val file = PostUpload("empty.png", "image/png", 0, PostUpload.Content { ByteArrayInputStream(ByteArray(0)) })

        every { reader.findById(any()) } returns Optional.of(previous)
        justRun { remover.remove(any()) }
        every { uploader.upload(any()) } returns listOf()
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } returns
            PostSnapshot(42, 7, "", "", "MISSING", listOf(), time, time)

        // when
        val result = update.update(42, 7, "", "", listOf(file))

        // then
        assertThat(result.imageUrls).isEmpty()
        verifyOrder {
            reader.findById(42)
            remover.remove(previous.imageUrls())
            uploader.upload(listOf(file))
            users.findNickname(7)
            writer.update(42, 7, "", "", listOf())
        }
    }

    @Test
    fun `작성자가 다를 때__수정하면__외부 작업과 저장 전에 거부한다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(previous)

        // when
        val thrown = assertThatThrownBy { update.update(42, 8, "수정", null, listOf()) }

        // then
        thrown.isInstanceOf(PostNotOwnedException::class.java)
        confirmVerified(remover, uploader, users, writer)
    }

    @Test
    fun `게시글이 없을 때__수정하면__외부 작업과 저장 전에 404를 던진다`() {
        // given
        every { reader.findById(any()) } returns Optional.empty()

        // when
        val thrown = assertThatThrownBy { update.update(42, 7, "수정", null, listOf()) }

        // then
        thrown.isInstanceOf(PostNotFoundException::class.java)
        confirmVerified(remover, uploader, users, writer)
    }

    @Test
    fun `기존 미디어 삭제를 시도한 뒤 업로드가 실패할 때__수정하면__저장이나 보상 없이 예외를 전파한다`() {
        // given
        val file = PostUpload("a.png", "image/png", 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })
        val failure = IllegalStateException("synthetic upload failure")

        every { reader.findById(any()) } returns Optional.of(previous)
        justRun { remover.remove(any()) }
        every { uploader.upload(any()) } throws failure

        // when
        val thrown = assertThatThrownBy { update.update(42, 7, null, null, listOf(file)) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { remover.remove(previous.imageUrls()) }
        confirmVerified(users, writer, remover)
    }

    @Test
    fun `업로드 뒤 저장이 늦게 거부될 때__수정하면__재시도나 미디어 보상 없이 예외를 전파한다`() {
        // given
        val file = PostUpload("a.png", "image/png", 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })
        val failure = PostNotFoundException()

        every { reader.findById(any()) } returns Optional.of(previous)
        justRun { remover.remove(any()) }
        every { uploader.upload(any()) } returns listOf("https://fixture.invalid/new")
        every { users.findNickname(any()) } returns Optional.of("작성자")
        every { writer.update(any(), any(), any(), any(), any()) } throws failure

        // when
        val thrown = assertThatThrownBy { update.update(42, 7, null, null, listOf(file)) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { writer.update(42, 7, null, null, listOf("https://fixture.invalid/new")) }
        verify(exactly = 1) { remover.remove(previous.imageUrls()) }
        confirmVerified(remover, writer)
    }

    @Test
    fun `닉네임이 없을 때__수정하면__미디어 처리 뒤 저장 전에 기존 대체 이름을 쓴다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(previous)
        every { users.findNickname(any()) } returns Optional.empty()
        every { writer.update(any(), any(), any(), any(), any()) } returns previous

        // when
        val result = update.update(42, 7, null, null, listOf())

        // then
        assertThat(result.authorNickname).isEqualTo("사용자7")
    }

    @Test
    fun `닉네임이 빈 문자열일 때__수정하면__기존 동작대로 null 닉네임을 유지한다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(previous)
        every { users.findNickname(any()) } returns Optional.of("")
        every { writer.update(any(), any(), any(), any(), any()) } returns previous

        // when
        val result = update.update(42, 7, null, null, listOf())

        // then
        assertThat(result.authorNickname).isNull()
    }

    @Test
    fun `닉네임 조회 통신이 불가능할 때__수정하면__기존 대체 이름을 쓴다`() {
        // given
        every { reader.findById(any()) } returns Optional.of(previous)
        every { users.findNickname(any()) } throws UserNicknameUnavailableException(IllegalStateException("synthetic"))
        every { writer.update(any(), any(), any(), any(), any()) } returns previous

        // when
        val result = update.update(42, 7, null, null, listOf())

        // then
        assertThat(result.authorNickname).isEqualTo("사용자7")
    }
}
