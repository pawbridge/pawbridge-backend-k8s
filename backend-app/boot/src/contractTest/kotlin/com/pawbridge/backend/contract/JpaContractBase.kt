package com.pawbridge.backend.contract

import com.pawbridge.backend.PawBridgeApplication
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Base of the contracts for the JPA adapters (store catalog writes, carts, orders, payment events).
 *
 * The JPA adapters exist only when their feature flags are on, so the shared context of [ContractTestBase] does not
 * contain them. This is the second Spring context of the contract suite, on purpose: the same application, database
 * and application role, with the store writer flags switched on. Every JPA contract extends this class so they all
 * share that one context; none may add properties or mocks of its own.
 *
 * The adapters are taken from the context as beans, so the entity manager, the writer transaction and the actor
 * lookup are the ones production wires.
 */
@Tag("contract")
@SpringBootTest(
    classes = [PawBridgeApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "pawbridge.auth.sessions-enabled=true",
        "jwt.access-token-expiration=120999",
        "jwt.refresh-token-expiration=600999",
        "pawbridge.cors.allowed-origin-patterns=https://contract.example.test",
        "pawbridge.visits.enabled=true",
        "pawbridge.store-direct-orders.enabled=true",
        "pawbridge.store-carts.enabled=true",
        "pawbridge.store-order-administration.enabled=true",
        "pawbridge.store-catalog-management.enabled=true",
        "pawbridge.store-payment-events.enabled=true",
        // The ranking Redis client is created lazily and never connects in these contracts.
        "spring.data.redis.host=127.0.0.1",
    ],
)
@ContextConfiguration(classes = [PawBridgeApplication::class], initializers = [ContractApplicationInitializer::class])
abstract class JpaContractBase : StoreRepositoryContractBase() {
    /** A member the order and cart adapters accept as the current actor. */
    protected fun seedMember(userId: Long, role: String = "ROLE_USER") {
        seedUser(userId, "member-$userId", role)
    }

    /** A member whose account deletion has started: the adapters must treat the actor as gone. */
    protected fun seedDeletingMember(userId: Long) {
        seedMember(userId)
        execute("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?)", userId)
    }

    protected fun stockOf(sku: Long): Int =
        count("SELECT stock_quantity FROM pawbridge_store.product_skus WHERE id = ?", sku).toInt()

    protected fun orderCountOf(user: Long): Long =
        count("SELECT count(*) FROM pawbridge_store.orders WHERE user_id = ?", user)

    protected fun orderField(orderId: Long, column: String): String? =
        scalar("SELECT $column::text FROM pawbridge_store.orders WHERE id = ?", orderId)

    protected fun searchDocumentCount(product: Long): Long =
        count("SELECT count(*) FROM pawbridge_store.product_search_documents WHERE product_id = ?", product)

    /** Seeds a product with one SKU in the id range the identity columns never reach in these contracts. */
    protected fun seedSellable(product: Long, sku: Long, stock: Int, price: Long = 1_000, status: String = "ACTIVE") {
        seedProduct(product, "상품 $product", status = status)
        seedSku(sku, product, price = price, stock = stock)
    }

    /**
     * Makes every [event] on a store table take [millis] more inside its transaction, for the duration of [action].
     * A race then no longer depends on thread timing: without the row lock under test the other workers read
     * their state while the first one is still writing, so the lost update or the oversell always happens.
     */
    protected fun <T> withSlowWrites(table: String, event: String, millis: Int = 200, action: () -> T): T {
        execute(
            "CREATE FUNCTION pawbridge_store.contract_slow() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$contract\$ BEGIN PERFORM pg_sleep($millis / 1000.0); IF TG_OP = 'DELETE' THEN RETURN OLD; END IF; RETURN NEW; END \$contract\$",
        )
        execute(
            "CREATE TRIGGER contract_slow BEFORE $event ON pawbridge_store.$table FOR EACH ROW " +
                "EXECUTE FUNCTION pawbridge_store.contract_slow()",
        )
        try {
            return action()
        } finally {
            execute("DROP TRIGGER contract_slow ON pawbridge_store.$table")
            execute("DROP FUNCTION pawbridge_store.contract_slow()")
        }
    }

    /**
     * Starts [count] workers at the same moment and returns what each one did, in worker order.
     * A worker's failure is a result, not an error of the test.
     */
    protected fun <T> runTogether(count: Int, work: (Int) -> T): List<Result<T>> {
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val futures = (0 until count).map { index ->
                pool.submit(
                    Callable {
                        ready.countDown()
                        go.await()
                        runCatching { work(index) }
                    },
                )
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).`as`("Every worker is waiting at the start").isTrue()
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
