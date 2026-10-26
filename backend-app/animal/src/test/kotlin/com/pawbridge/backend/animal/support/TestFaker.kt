package com.pawbridge.backend.animal.support

import net.datafaker.Faker
import java.util.Random
import java.util.UUID

/**
 * One fixed-seed Datafaker shared by this module's tests, so every run produces the same values.
 * Use it only for values that carry no meaning (names, emails, ids); keep literals for contract values
 * such as boundaries, special characters, messages, SQL arguments, amounts and sort orders.
 * Copy this file into another module's test sources to follow the same pattern.
 */
object TestFaker {
    private const val SEED = 20_260_101L

    val faker: Faker = Faker(Random(SEED))

    fun uuid(): UUID = UUID.fromString(faker.internet().uuid())
}
