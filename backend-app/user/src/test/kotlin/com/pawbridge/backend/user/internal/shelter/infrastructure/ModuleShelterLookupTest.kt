package com.pawbridge.backend.user.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModuleShelterLookupTest {
    private val source: AnimalShelterReads = mockk<AnimalShelterReads>()
    private val lookup = ModuleShelterLookup(source)

    @Test
    fun `애니멀 API 투영이 있을 때__조회하면__사용자 계약만 매핑하고 원본 등록번호 인자를 보존한다`() {
        // given
        every { source.findByCareRegNo(any()) } returns AnimalShelterReads.Shelter(9L, "등록 번호", "보호소")

        // when
        val result = lookup.find("등록 번호")

        // then
        assertEquals(ShelterMembership.Shelter(9L, "등록 번호"), result)
        verify(exactly = 1) { source.findByCareRegNo("등록 번호") }
        confirmVerified(source)
    }

    @Test
    fun `보호소를 찾지 못하거나 다른 등록번호가 돌아올 때__조회하면__등록번호 검증은 기존 멤버십 서비스에 맡기고 결과를 그대로 매핑한다`() {
        // given
        every { source.findByCareRegNo(any()) } returnsMany listOf(
            null,
            AnimalShelterReads.Shelter(null, "different", null),
        )

        // when
        val missing = lookup.find("r")
        val different = lookup.find("r")

        // then
        assertNull(missing)
        assertEquals(ShelterMembership.Shelter(null, "different"), different)
    }

    @Test
    fun `애니멀 모듈을 쓸 수 없을 때__조회하면__없음 표식을 만들지 않고 기존 503 매핑을 위해 예외를 전파한다`() {
        // given
        val failure = AnimalShelterReadUnavailableException(IllegalStateException("synthetic source missing"))
        every { source.findByCareRegNo(any()) } throws failure

        // when
        val actual = assertThrows(AnimalShelterReadUnavailableException::class.java) { lookup.find("r") }

        // then
        assertSame(failure, actual)
        assertFalse(ShelterMembership.MissingShelter::class.java.isInstance(actual))
    }
}
