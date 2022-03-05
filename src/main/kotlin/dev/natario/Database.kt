package dev.natario

import com.squareup.sqldelight.db.SqlCursor
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

    fun count(table: String): Long {
        return driver.executeQuery(
            identifier = null,
            sql = "select count(*) from $table",
            parameters = 0
        ).let {
            require(it.next())
            it.getLong(0)!!
        }
    }

    fun query(table: String): List<Entry> {
        val columns = columns(table)
        return driver.executeQuery(
            identifier = null,
            sql = "select * from $table",
            parameters = 0
        ).asSequence()
            .map { Entry(it, columns.size) }
            .toList()
    }

    fun insert(
        table: String,
        overwrite: Boolean,
        columns: List<String>,
        entries: List<Entry>
    ) {
        val verb = if (overwrite) "insert or replace" else "insert"
        driver.execute(
            identifier = null,
            sql = "$verb into $table (${columns.joinToString()}) values ${entries.joinToString()}",
            parameters = 0
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
}