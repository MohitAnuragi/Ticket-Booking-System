package com.ticketbooking.plugins

import com.ticketbooking.util.ConfigurationException
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * Owns the JDBC connection pool and the Exposed [Database] handle.
 *
 * Startup contract: [init] either returns with a pool that has served a real
 * query, or it throws. A half-initialised database is never handed to the rest
 * of the application, so no request can fail later for a reason that was
 * already knowable at boot.
 */
object DatabaseFactory {

    private val log: Logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    @Volatile
    private var dataSource: HikariDataSource? = null

    @Volatile
    private var database: Database? = null

    /** True once [init] has completed successfully. */
    val isInitialized: Boolean
        get() = database != null

    /**
     * The Exposed database handle.
     * @throws IllegalStateException if accessed before a successful [init].
     */
    val db: Database
        get() = database ?: error("DatabaseFactory.init() has not completed successfully")

    /**
     * Reads `app.db.*`, opens the pool, and proves it works with `SELECT 1`.
     *
     * @throws ConfigurationException when credentials are missing or the database
     *         cannot be reached. The message tells the operator exactly what to fix.
     */
    fun init(config: ApplicationConfig) {
        if (isInitialized) {
            log.debug("DatabaseFactory already initialised; skipping")
            return
        }

        val url = config.stringOrNull("app.db.url")
        val user = config.stringOrNull("app.db.user")
        val password = config.stringOrNull("app.db.password")
        val poolSize = config.stringOrNull("app.db.poolSize")?.toIntOrNull() ?: DEFAULT_POOL_SIZE

        val missing = buildList {
            if (url.isNullOrBlank()) add("DB_URL")
            if (user.isNullOrBlank()) add("DB_USER")
            if (password.isNullOrBlank()) add("DB_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            throw ConfigurationException(
                """
                Cannot start: missing database configuration ${missing.joinToString(", ")}.

                Set them in the terminal you launch the server from, for example:
                  ${'$'}env:DB_URL      = "jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require"
                  ${'$'}env:DB_USER     = "postgres"
                  ${'$'}env:DB_PASSWORD = "<your supabase db password>"

                See Backend/.env.example for the full list and Supabase-specific notes.
                """.trimIndent(),
            )
        }

        warnAboutRiskyUrl(url!!)

        val hikari = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"

            // Small pool on purpose: Supabase caps concurrent connections, and a
            // non-blocking Ktor server does not need one connection per thread.
            maximumPoolSize = poolSize.coerceIn(1, MAX_POOL_SIZE)
            minimumIdle = 1

            // Exposed opens and commits transactions itself.
            isAutoCommit = false

            // READ_COMMITTED is what SELECT ... FOR UPDATE row locking needs; the
            // lock, not the isolation level, is what serialises seat claims.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            connectionTimeout = CONNECTION_TIMEOUT_MS
            validationTimeout = VALIDATION_TIMEOUT_MS
            idleTimeout = IDLE_TIMEOUT_MS
            maxLifetime = MAX_LIFETIME_MS
            poolName = "ticket-booking-pool"
            validate()
        }

        val createdPool = try {
            HikariDataSource(hikari)
        } catch (e: Exception) {
            throw ConfigurationException(connectionFailureMessage(url, e), e)
        }

        // Prove the credentials and network path actually work before booting.
        try {
            createdPool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rs ->
                        check(rs.next()) { "Connectivity probe returned no rows" }
                    }
                }
            }
        } catch (e: SQLException) {
            createdPool.close()
            throw ConfigurationException(connectionFailureMessage(url, e), e)
        } catch (e: Exception) {
            createdPool.close()
            throw ConfigurationException(connectionFailureMessage(url, e), e)
        }

        dataSource = createdPool
        database = Database.connect(createdPool)

        log.info("Connected to database: ${redactUrl(url)} (pool size ${hikari.maximumPoolSize})")

        // Fail now if the hand-applied schema does not match what the code expects,
        // rather than midway through a booking later.
        SchemaVerifier.verify(createdPool)
    }

    /** The pool, for components that need raw JDBC (the schema verifier, health probe). */
    val pool: HikariDataSource?
        get() = dataSource

    /**
     * Live connectivity check for the health endpoint. Never throws - the health
     * route should report a degraded database, not fail with a 500.
     */
    fun probe(): Boolean {
        val pool = dataSource ?: return false
        return try {
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { it.next() }
                }
            }
        } catch (e: Exception) {
            log.warn("Database health probe failed: ${e.message}")
            false
        }
    }

    /** Closes the pool. Registered against Ktor's shutdown so the JVM can exit cleanly. */
    fun close() {
        dataSource?.let {
            log.info("Closing database connection pool")
            it.close()
        }
        dataSource = null
        database = null
    }

    private fun warnAboutRiskyUrl(url: String) {
        if (":6543" in url && "prepareThreshold=0" !in url) {
            log.warn(
                "DB_URL uses port 6543, Supabase's TRANSACTION-mode pooler. That mode does " +
                    "not support JDBC server-side prepared statements and will cause " +
                    "intermittent 'prepared statement already exists' errors. Use the direct " +
                    "connection on port 5432, or append &prepareThreshold=0 to the URL.",
            )
        }
        if ("sslmode" !in url) {
            log.warn(
                "DB_URL does not specify sslmode. Supabase requires TLS - append " +
                    "?sslmode=require (or &sslmode=require) if the connection is refused.",
            )
        }
    }

    private fun connectionFailureMessage(url: String, cause: Exception): String = """
        Cannot start: failed to connect to the database at ${redactUrl(url)}.

        Underlying error: ${cause.message}

        Things worth checking:
          - DB_PASSWORD is the Supabase DATABASE password (Project Settings ->
            Database), not your Supabase account password or an API key.
          - The URL ends with ?sslmode=require - Supabase refuses plaintext connections.
          - You are on the direct connection (port 5432). Port 6543 is the
            transaction-mode pooler and needs &prepareThreshold=0.
          - The project is not paused in the Supabase dashboard.
          - Your network permits outbound connections on that port.
    """.trimIndent()

    /** Strips credentials so a connection string is safe to log. */
    private fun redactUrl(url: String): String =
        url.replace(Regex("://[^@/]+@"), "://***@")
            .replace(Regex("(?i)(password=)[^&]*"), "$1***")

    private const val DEFAULT_POOL_SIZE = 5
    private const val MAX_POOL_SIZE = 20
    private const val CONNECTION_TIMEOUT_MS = 15_000L
    private const val VALIDATION_TIMEOUT_MS = 5_000L
    private const val IDLE_TIMEOUT_MS = 300_000L
    private const val MAX_LIFETIME_MS = 900_000L
}

/** Reads a config value, tolerating an absent key (HOCON `${?ENV}` leaves keys unset). */
// `stringOrNull` lives in Components.kt - same package, shared by both files.

/**
 * Opens the connection pool and closes it again on shutdown.
 * Called from [com.ticketbooking.module] before any route is served.
 */
fun Application.configureDatabase() {
    DatabaseFactory.init(environment.config)

    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        DatabaseFactory.close()
    }
    log.debug("Database lifecycle bound to application shutdown")
}
