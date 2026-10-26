package com.pawbridge.backend.animal.internal.command.infrastructure.repository

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ShelterRepository : JpaRepository<Shelter, Long> {
    fun findByCareRegNo(careRegNo: String?): Optional<Shelter>

    fun findByCareRegNoIn(careRegNos: List<String>): List<Shelter>
}
