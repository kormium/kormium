@file:OptIn(io.github.kormium.DelicateKormiumApi::class)

package io.github.kormium.r2dbc

import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.UniqueViolationException
import io.github.kormium.count
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.eq
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MySQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * End-to-end test of the async r2dbc-mysql backend against a real MySQL (Testcontainers). Mirrors
 * the Postgres [R2dbcIntegrationTest]: suspendTransaction/suspendAutocommit →
 * R2dbcDatabase.useConnection over the same Table DSL, with MySQL DDL and `?` bind markers. Skips
 * gracefully when Docker isn't available. Integrity violations are mapped to typed exceptions by
 * vendor code (MySQL reports them all under SQLSTATE 23000), like the JDBC and native drivers.
 */
class MySqlR2dbcIntegrationTest {

    private val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
    private var container: MySQLContainer<*>? = null
    private var db: SuspendDatabase<MyR2Catalog>? = null

    @BeforeTest
    fun setUp() {
        if (!dockerAvailable) return
        val mysql = MySQLContainer("mysql:8.0")
            .withCommand("--default-authentication-plugin=mysql_native_password")
        mysql.start()
        container = mysql
        db = createMySqlR2dbcDatabase(
            host = mysql.host,
            port = mysql.firstMappedPort,
            database = mysql.databaseName,
            user = mysql.username,
            password = mysql.password,
            poolSize = 4,
        )
    }

    @AfterTest
    fun tearDown() {
        db?.close()
        container?.stop()
    }

    @Test
    fun crudRoundTrip() {
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            val id = Uuid.random()
            database.suspendTransaction {
                MyWidgets.execSql(myWidgetsDdl)
                MyWidgets.insert(MyWidget().apply {
                    this.id = id
                    this.name = "async-widget"
                    this.qty = 7
                })
            }

            val found = database.suspendAutocommit { MyWidgets.findOne { where { MyWidgets.id eq id } } }
            assertEquals(id, found?.id)
            assertEquals("async-widget", found?.name)
            assertEquals(7, found?.qty)

            val byName = database.suspendAutocommit { MyWidgets.find(Query(MyWidgets.name eq "async-widget")) }
            assertEquals(1, byName.size)

            assertTrue(database.suspendAutocommit { MyWidgets.count() } >= 1)
        }
    }

    @Test
    fun transactionRollsBackOnThrow() {
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            database.suspendTransaction { MyWidgets.execSql(myWidgetsDdl) }
            val id = Uuid.random()

            assertFailsWith<IllegalStateException> {
                database.suspendTransaction {
                    MyWidgets.insert(MyWidget().apply {
                        this.id = id
                        this.name = "doomed"
                        this.qty = 1
                    })
                    error("boom")
                }
            }

            assertNull(database.suspendAutocommit { MyWidgets.findOne { where { MyWidgets.id eq id } } })
        }
    }

    @Test
    fun typedUniqueViolation() {
        if (!dockerAvailable) return
        val database = db!!
        runBlocking {
            database.suspendTransaction { MyWidgets.execSql(myWidgetsDdl) }
            val id = Uuid.random()
            database.suspendTransaction {
                MyWidgets.insert(MyWidget().apply { this.id = id; this.name = "dup"; this.qty = 1 })
            }
            assertFailsWith<UniqueViolationException> {
                database.suspendTransaction {
                    MyWidgets.insert(MyWidget().apply { this.id = id; this.name = "dup2"; this.qty = 2 })
                }
            }
        }
    }
}

object MyR2Catalog : Catalog

class MyWidget : Entity() {
    var id by MyWidgets.id
    var name by MyWidgets.name
    var qty by MyWidgets.qty
}

object MyWidgets : Table<MyR2Catalog, MyWidget>("widgets", ::MyWidget) {
    val id by Column.UUID().primaryKey()
    val name by Column.Text()
    val qty by Column.Int()

    init { id; name; qty }
}

private val myWidgetsDdl =
    "CREATE TABLE IF NOT EXISTS `widgets` (`id` CHAR(36) NOT NULL, `name` VARCHAR(255) NOT NULL, `qty` INT NOT NULL, PRIMARY KEY (`id`))"
