package com.pawbridge.backend.contract

import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager

/**
 * Base of the animal repository contracts: the application's own pools and the rows the animal queries read.
 *
 * Every table the test changes is restored by [ApiContractTestBase], so tests may seed any ids.
 */
abstract class AnimalRepositoryContractBase : ApiContractTestBase() {
    @Autowired
    @Qualifier("dataSource")
    protected lateinit var source: DataSource

    @Autowired
    @Qualifier("transactionManager")
    protected lateinit var manager: PlatformTransactionManager

    @Autowired
    @Qualifier("writeDataSource")
    protected lateinit var writeSource: DataSource

    @Autowired
    @Qualifier("writeTransactionManager")
    protected lateinit var writeManager: JdbcTransactionManager

    protected fun seedShelter(
        id: Long,
        name: String = "보호소 $id",
        address: String? = "서울특별시 강남구 $id",
        careRegNo: String = "CR-$id",
    ) {
        execute(
            "INSERT INTO pawbridge_animal.shelters(id, created_at, care_reg_no, name, address, phone) " +
                "VALUES (?, '2026-01-01 00:00:00', ?, ?, ?, '02-000-0000')",
            id, careRegNo, name, address,
        )
    }

    @Suppress("LongParameterList")
    protected fun seedAnimal(
        id: Long,
        shelter: Long,
        status: String = "PROTECT",
        species: String = "DOG",
        breed: String? = "믹스견",
        gender: String = "MALE",
        happenDate: String? = "2026-03-01",
        noticeEnd: String = "2026-03-20",
        birthYear: Int? = 2022,
        createdAt: String = "2026-03-01 00:00:00",
        apiSource: String = "APMS_ANIMAL",
        noticeNo: String = "N-$id",
        desertionNo: String? = null,
        apmsUpdatedAt: String? = null,
        favoriteCount: Int = 0,
        color: String? = "갈색",
        specialMark: String? = null,
        description: String? = null,
    ) {
        execute(
            "INSERT INTO pawbridge_animal.animals(id, created_at, updated_at, api_source, apms_desertion_no, apms_notice_no, " +
                "apms_updated_at, birth_year, breed, color, description, favorite_count, gender, happen_date, happen_place, " +
                "neuter_status, notice_end_date, notice_start_date, special_mark, species, status, shelter_id) " +
                "VALUES (?, ?::timestamp, ?::timestamp, ?, ?, ?, ?::timestamp, ?, ?, ?, ?, ?, ?, ?::date, '합성 장소', " +
                "'UNKNOWN', ?::date, '2026-02-01', ?, ?, ?, ?)",
            id, createdAt, createdAt, apiSource, desertionNo, noticeNo, apmsUpdatedAt, birthYear, breed, color, description,
            favoriteCount, gender, happenDate, noticeEnd, specialMark, species, status, shelter,
        )
    }
}
