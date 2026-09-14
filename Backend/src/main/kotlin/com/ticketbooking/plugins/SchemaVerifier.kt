package com.ticketbooking.plugins

import com.ticketbooking.util.ConfigurationException
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Confirms at startup that the database actually has the schema this code expects.
 *
 * The schema is applied by hand in the Supabase SQL editor, so the most likely
 * setup mistake is running `01_schema.sql` but forgetting `02_deltas.sql`. Without
 * this check that mistake would surface much later as a confusing "column
 * lock_expires_at does not exist" error in the middle of a booking. Here it
 * surfaces at boot with instructions.
 */
object SchemaVerifier {

    private val log = LoggerFactory.getLogger(SchemaVerifier::class.java)

    private val REQUIRED_TABLES = listOf(
        "users", "venues", "seats", "events", "event_seats", "bookings", "booking_seats",
    )

    /** Columns introduced by db/02_deltas.sql, as table to column. */
    private val REQUIRED_DELTA_COLUMNS = listOf(
        "event_seats" to "locked_by",
        "event_seats" to "lock_expires_at",
        "bookings" to "hold_expires_at",
        "booking_seats" to "is_active",
    )

    private const val ACTIVE_CLAIM_INDEX = "uq_booking_seats_active"

    /**
     * @return row counts per table, for the startup log.
     * @throws ConfigurationException if anything required is missing.
     */
    fun verify(dataSource: DataSource): Map<String, Long> {
        dataSource.connection.use { connection ->
            val existingTables = mutableSetOf<String>()
            connection.prepareStatement(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    while (rs.next()) existingTables += rs.getString("table_name").lowercase()
                }
            }

            val missingTables = REQUIRED_TABLES.filterNot { it in existingTables }
            if (missingTables.isNotEmpty()) {
                throw ConfigurationException(
                    """
                    Cannot start: the database is missing ${missingTables.size} required table(s): ${missingTables.joinToString(", ")}.

                    Apply the schema first. In the Supabase dashboard open the SQL Editor and run,
                    in this order:
                      1. Backend/db/01_schema.sql
                      2. Backend/db/02_deltas.sql
                    """.trimIndent(),
                )
            }

            val existingColumns = mutableSetOf<Pair<String, String>>()
            connection.prepareStatement(
                """
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        existingColumns += rs.getString("table_name").lowercase() to
                            rs.getString("column_name").lowercase()
                    }
                }
            }

            val missingColumns = REQUIRED_DELTA_COLUMNS.filterNot { it in existingColumns }
            if (missingColumns.isNotEmpty()) {
                throw ConfigurationException(
                    """
                    Cannot start: the base tables exist but the required delta columns are missing:
                      ${missingColumns.joinToString(", ") { "${it.first}.${it.second}" }}

                    It looks like db/01_schema.sql was applied but db/02_deltas.sql was not.
                    Run Backend/db/02_deltas.sql in the Supabase SQL Editor. It is idempotent,
                    so it is safe to run even if part of it already applied.

                    Those columns are what make 24-hour seat holds and cancel-then-rebook work.
                    """.trimIndent(),
                )
            }

            // The partial unique index IS the double-booking guarantee; a silently
            // missing one would leave the system able to sell a seat twice.
            val hasActiveClaimIndex = connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { statement ->
                statement.setString(1, ACTIVE_CLAIM_INDEX)
                statement.executeQuery().use { it.next() }
            }
            if (!hasActiveClaimIndex) {
                throw ConfigurationException(
                    """
                    Cannot start: the unique index '$ACTIVE_CLAIM_INDEX' is missing.

                    This index is the database-level guarantee against double booking - at most
                    one ACTIVE booking_seats row per event seat. Without it two users could be
                    sold the same seat.

                    Run Backend/db/02_deltas.sql in the Supabase SQL Editor.
                    """.trimIndent(),
                )
            }

            // Row counts: proves each table is readable with the expected name.
            val counts = LinkedHashMap<String, Long>()
            REQUIRED_TABLES.forEach { table ->
                connection.prepareStatement("SELECT count(*) FROM $table").use { statement ->
                    statement.executeQuery().use { rs ->
                        rs.next()
                        counts[table] = rs.getLong(1)
                    }
                }
            }

            log.info(
                "Schema verified. Row counts: ${counts.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
            )
            return counts
        }
    }
}
