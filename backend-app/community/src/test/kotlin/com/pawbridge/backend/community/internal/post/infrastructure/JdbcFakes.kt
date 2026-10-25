package com.pawbridge.backend.community.internal.post.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.Calendar

/*
 * Small in-memory fakes for the JDBC tests in this package.
 * ResultSet, PreparedStatement and Connection have far too many overloads to stub one by one with strict MockK,
 * so these Proxy-based fakes record what the production code binds and serve the values a test puts in.
 */

/** One prepared statement: its SQL and the values bound to it, keyed by parameter index. */
internal class StatementCall(val sql: String) {
    val values = LinkedHashMap<Int, Any?>()
    val calendars = LinkedHashMap<Int, Calendar>()
}

/** A TransactionOperations that runs the callback inline and exposes whether it is currently inside it. */
internal class FakeTransactions : TransactionOperations {
    var active = false
        private set
    var executions = 0
        private set
    var failureBeforeCallback: RuntimeException? = null
    var failureAfterCallback: RuntimeException? = null

    override fun <T : Any?> execute(action: TransactionCallback<T>): T? {
        executions++
        failureBeforeCallback?.let { throw it }
        active = true
        try {
            val result = action.doInTransaction(SimpleTransactionStatus())
            failureAfterCallback?.let { throw it }
            return result
        } finally {
            active = false
        }
    }
}

/** A Connection whose prepareStatement records the SQL, runs [beforePrepare], and hands back a recording statement. */
internal fun recordingConnection(calls: MutableList<StatementCall>, beforePrepare: () -> Unit): Connection =
    proxyOf(Connection::class.java) { method, args ->
        if (method.name == "prepareStatement") {
            beforePrepare()
            val call = StatementCall(args!![0] as String)
            calls.add(call)
            recordingStatement(call)
        } else {
            NOT_HANDLED
        }
    }

/** A PreparedStatement that stores setObject and setTimestamp bindings into [call] and ignores everything else. */
internal fun recordingStatement(call: StatementCall): PreparedStatement =
    proxyOf(PreparedStatement::class.java) { method, args ->
        when (method.name) {
            "setObject" -> {
                call.values[args!![0] as Int] = args[1]
                null
            }
            "setTimestamp" -> {
                val index = args!![0] as Int
                call.values[index] = args[1] as Timestamp?
                if (args.size == 3) {
                    val calendar = args[2] as Calendar
                    assertEquals("UTC", calendar.timeZone.id)
                    call.calendars[index] = calendar
                }
                null
            }
            else -> NOT_HANDLED
        }
    }

/**
 * A ResultSet that serves values from maps keyed by column label.
 * Anything not put into a map answers like an unstubbed mock would: null, 0 or false.
 */
internal class FakeRow {
    val strings = HashMap<String, String?>()
    val longs = HashMap<String, Long>()
    val booleans = HashMap<String, Boolean>()
    val objects = HashMap<String, Any?>()
    val timestamps = HashMap<String, Timestamp?>()
    var hasNext = true
    val reads = ArrayList<String>()

    val resultSet: ResultSet = proxyOf(ResultSet::class.java) { method, args ->
        when (method.name) {
            "next" -> hasNext
            "getString" -> read(args!![0] as String) { strings[it] }
            "getLong" -> read(args!![0] as String) { longs[it] ?: 0L }
            "getBoolean" -> read(args!![0] as String) { booleans[it] ?: false }
            "getObject" -> read(args!![0] as String) { objects[it] }
            "getTimestamp" -> {
                val label = args!![0] as String
                if (args.size == 2) {
                    assertEquals("UTC", (args[1] as Calendar).timeZone.id)
                }
                read(label) { timestamps[it] }
            }
            else -> NOT_HANDLED
        }
    }

    private fun read(label: String, value: (String) -> Any?): Any? {
        reads.add(label)
        return value(label)
    }
}

private val NOT_HANDLED = Any()

private fun <T> proxyOf(type: Class<T>, handler: (Method, Array<Any?>?) -> Any?): T {
    val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
        when (method.name) {
            "toString" -> "Fake${type.simpleName}"
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args!![0]
            else -> {
                val handled = handler(method, args)
                if (handled === NOT_HANDLED) defaultValue(method.returnType) else handled
            }
        }
    }
    return type.cast(proxy)
}

private fun defaultValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
