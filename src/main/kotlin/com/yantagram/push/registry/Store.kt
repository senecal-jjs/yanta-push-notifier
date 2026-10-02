package com.yantagram.push.registry

import com.yantagram.push.Platform
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File

/** A stored registration: exactly what onboarding discloses (privacy rule 5). */
data class StoredRegistration(
    val token: String,
    val platform: Platform,
    val tags: Set<String>,
    val updatedAt: Long,
    /** Whether the user has notifications on: chat messages may be sent as alerts. */
    val alerts: Boolean = false,
    /** The device's sender marker key K, hex; null if it sent none. */
    val selfKey: String? = null,
)

private object Registrations : Table("registrations") {
    val token = varchar("token", 300)
    val platform = varchar("platform", 10)
    /** Space-separated tags. */
    val tags = text("tags")
    /** Epoch milliseconds. */
    val updatedAt = long("updated_at")
    val alerts = bool("alerts").default(false)
    val selfKey = varchar("self_key", 64).nullable()

    override val primaryKey = PrimaryKey(token)
}

private object Meta : Table("meta") {
    val key = varchar("key", 64)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}

/**
 * SQLite persistence. Blocking: call from Dispatchers.IO. The Registry is the only
 * writer of registrations, and serializes its writes.
 */
class Store(path: String) {
    private val db: Database

    init {
        File(path).absoluteFile.parentFile?.mkdirs()
        val config = SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            setBusyTimeout(5_000)
        }
        val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:$path" }
        db = Database.connect(dataSource)
        transaction(db) {
            SchemaUtils.create(Registrations, Meta)
            // Databases from before alert pushes lack the newer columns.
            val columns = mutableSetOf<String>()
            exec("PRAGMA table_info(registrations)") { rs -> while (rs.next()) columns += rs.getString("name") }
            if ("alerts" !in columns) exec("ALTER TABLE registrations ADD COLUMN alerts BOOLEAN NOT NULL DEFAULT 0")
            if ("self_key" !in columns) exec("ALTER TABLE registrations ADD COLUMN self_key VARCHAR(64) NULL")
        }
    }

    fun loadAll(): List<StoredRegistration> = transaction(db) {
        Registrations.selectAll().mapNotNull { row ->
            val platform = Platform.fromWire(row[Registrations.platform]) ?: return@mapNotNull null
            StoredRegistration(
                token = row[Registrations.token],
                platform = platform,
                tags = row[Registrations.tags].split(' ').filterTo(mutableSetOf()) { it.isNotEmpty() },
                updatedAt = row[Registrations.updatedAt],
                alerts = row[Registrations.alerts],
                selfKey = row[Registrations.selfKey],
            )
        }
    }

    fun upsert(registration: StoredRegistration) {
        transaction(db) {
            Registrations.upsert {
                it[token] = registration.token
                it[platform] = registration.platform.wire
                it[tags] = registration.tags.joinToString(" ")
                it[updatedAt] = registration.updatedAt
                it[alerts] = registration.alerts
                it[selfKey] = registration.selfKey
            }
        }
    }

    fun delete(token: String) {
        transaction(db) { Registrations.deleteWhere { Registrations.token eq token } }
    }

    fun deleteAll(tokens: Collection<String>) {
        if (tokens.isEmpty()) return
        transaction(db) {
            tokens.chunked(500).forEach { chunk ->
                Registrations.deleteWhere { Registrations.token inList chunk }
            }
        }
    }

    fun getMeta(key: String): String? = transaction(db) {
        Meta.selectAll().where { Meta.key eq key }.singleOrNull()?.get(Meta.value)
    }

    fun setMeta(key: String, value: String) {
        transaction(db) {
            Meta.upsert {
                it[Meta.key] = key
                it[Meta.value] = value
            }
        }
    }
}
