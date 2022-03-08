package dev.natario

import com.squareup.sqldelight.Transacter
import com.squareup.sqldelight.TransacterImpl
import com.squareup.sqldelight.db.SqlCursor
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.db.SqlPreparedStatement
import com.squareup.sqldelight.sqlite.driver.ConnectionManager
import com.squareup.sqldelight.sqlite.driver.JdbcPreparedStatement
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.pathString

class Database(private val path: Path, private val root: Path) {

    fun exists() = path.exists()

    fun copyTo(other: Database) {
        path.copyTo(other.path, overwrite = true)
    }

    override fun toString(): String {
        return root.relativize(path).pathString
    }

    private val driver by lazy {
        JdbcSqliteDriver("jdbc:sqlite:${path.pathString}")
    }

    private val columns = mutableMapOf<String, List<String>>()

    fun columns(table: String) = columns.getOrPut(table) {
        driver.executeQuery(
            identifier = null,
            sql = "pragma table_info($table)",
            parameters = 0
        ).asSequence()
            .map { it.getString(1)!! }
            .toList()
    }

    fun count(table: String, constraint: String? = null): Long {
        return driver.executeQuery(
            identifier = null,
            sql = listOfNotNull("select count(*) from $table", constraint).joinToString(separator = " where "),
            parameters = 0
        ).let {
            require(it.next())
            it.getLong(0)!!
        }
    }

    fun query(table: String, constraint: String? = null): List<Entry> {
        val columns = columns(table)
        return driver.executeQuery(
            identifier = null,
            sql = listOfNotNull("select * from $table", constraint).joinToString(separator = " where "),
            parameters = 0
        ).asSequence()
            .map { Entry(it, columns.size) }
            .toList()
    }

    enum class OnConflict { Abort, Replace, Skip }

    fun insert(
        table: String,
        onConflict: OnConflict,
        columns: List<String>,
        entries: List<Entry>
    ): Long {
        val prefix = if (onConflict == OnConflict.Replace) "insert or replace" else "insert"
        val suffix = if (onConflict == OnConflict.Skip) "on conflict do nothing" else null
        return driver.executeWithRows(
            sql = listOfNotNull(
                prefix,
                "into $table (${columns.joinToString()}) values ${entries.joinToString()}",
                suffix
            ).joinToString(separator = " "),
        )
    }

    fun tables(): List<String> = driver.executeQuery(
        identifier = null,
        sql = "select name from sqlite_schema where type = 'table'",
        parameters = 0
    ).asSequence().mapNotNull {
        it.getString(0)
    }.toList()

    private fun SqlCursor.asSequence(): Sequence<SqlCursor> = sequence {
        while (next()) yield(this@asSequence)
    }

    // Can't use super.execute and can't call "select changes()" because after connection is closed
    // the value is reset. Maybe a SqlDelight transaction might work but it's equally verbose.
    private fun JdbcSqliteDriver.executeWithRows(
        sql: String,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): Long {
        val (connection, onClose) = connectionAndClose()
        return try {
            connection.prepareStatement(sql).use { jdbcStatement ->
                JdbcPreparedStatement(jdbcStatement)
                    .apply { if (binders != null) this.binders() }
                    .execute()
                // select changes()
                jdbcStatement.updateCount.toLong()
            }
        } finally {
            onClose()
        }
    }
}