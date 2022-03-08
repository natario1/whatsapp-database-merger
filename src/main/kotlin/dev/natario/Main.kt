package dev.natario

import java.nio.file.Path
import kotlin.io.path.*

fun main(args: Array<String>) {
    val root: Path = Path.of(args.first()).absolute().normalize()
    val inputs = root
        .resolve("input")
        .createDirectories()
        .listDirectoryEntries("*.db")
        .map { Database(it, root) }
    if (inputs.isEmpty()) error("No inputs found.")
    if (inputs.size == 1) error("Only 1 db found, can't merge.")
    println("[*] Input databases:\n${inputs.joinToString("\n") { "\t- $it" }}")
    inputs.forEach {
        println("[*] Checking consistency of $it")
        it.ensureConsistent(throwOnFailure = true)
    }

    val output = root
        .resolve("output")
        .createDirectories()
        .resolve("msgstore.db")
        .let { Database(it, root) }
    println("[*] Output database: $output")
    if (output.exists()) println("\tWARNING! Output database exists, so it will be overwritten.")

    println("[*] Copying ${inputs.first()} to $output}")
    inputs.first().copyTo(output)

    val remaining = inputs.drop(1)
    remaining.forEach {
        println("[*] Processing $it")
        merge(source = it, destination = output)
    }

    output.ensureConsistent(throwOnFailure = true)
    println("Success.")
}

private fun merge(source: Database, destination: Database) {
    val offsets = mutableMapOf<Table, Long>()
    Table.values().forEach { table ->
        println("[*] processing table $table")
        require(source.tables().contains(table.name.lowercase())) {
            "$source does not contain expected table $table. Unexpected WhatsApp version?"
        }
        require(destination.tables().contains(table.name.lowercase())) {
            "$destination does not contain expected table $table. Unexpected WhatsApp version?"
        }

        val sourceColumns = source.columns(table.name)
        val destColumns = destination.columns(table.name)
        println("\t- $source columns: $sourceColumns")
        println("\t- $destination columns: $destColumns")
        require(sourceColumns.toSet() == destColumns.toSet()) {
            """
                Table $table mismatch between $source and $destination. Different WhatsApp versions?
                - $source schema: $sourceColumns
                - $destination schema: $destColumns
            """.trimIndent()
        }

        val sourceData = source.query(table.name)
        val destData = destination.query(table.name)
        println("[*] $table: source elements ${sourceData.size}, destination elements ${destData.size}")

        warnDuplicates(
            sourceColumns = sourceColumns,
            sourceData = sourceData,
            destColumns = destColumns,
            destData = destData,
            table = table
        )
        offsetPrimaryKey(
            columns = sourceColumns,
            data = sourceData,
            table = table,
            computeOffset = {
                (destData.lastOrNull()?.let { it.getLong(0)!! } ?: 0L).also {
                    offsets[table] = it
                }
            }
        )
        offsetReferencedTables(
            columns = sourceColumns,
            data = sourceData,
            table = table,
            getOffset = {
                requireNotNull(offsets[it]) {
                    "Table $table depends on $it but offset was not computed."
                }
            },
        )
        excludeProblematicColumns(
            data = sourceData,
            table = table,
            columns = sourceColumns
        )
        insertData(
            table = table,
            columns = sourceColumns,
            data = sourceData,
            destination = destination,
            batch = 500
        )
    }
}

private fun insertData(
    table: Table,
    columns: List<String>,
    data: List<Entry>,
    destination: Database,
    batch: Int
) {
    println("[*] $table: about to append ${data.size} rows")
    var inserted = 0
    var failed = 0
    data.chunked(minOf(batch, table.maxBatch)).forEach { entries ->
        try {
            destination.insert(
                table = table.name,
                columns = columns,
                entries = entries,
                overwrite = table.overwrite
            )
            inserted += entries.size
            println("\tappended $inserted/${data.size}...")
        } catch (e: Throwable) {
            if (table.dropFailingBatches) {
                println("\tWarning: batch of ${entries.size} entries failed, going on because dropFailingBatches is true for this table. Failing entries:")
                println("\t${entries.joinToString(separator = "\n\t")}")
                failed += entries.size
            } else {
                throw RuntimeException(
                    "INSERT failed for last batch of ${entries.size} entries: \n${entries.joinToString("\n") { "\t- $it" }}",
                    e
                )
            }
        }
    }
    println("[*] $table: appended ${data.size} entries, ${destination.count(table.name)} total, $failed failed.")
}

private fun offsetReferencedTables(
    columns: List<String>,
    data: List<Entry>,
    table: Table,
    getOffset: (Table) -> Long,
) {
    table.refs.forEach {
        val offset = getOffset(it.table)
        val index = columns.indexOf(it.name)
        println("[*] $table: editing column ${it.name}, because it references table ${it.table} which was previously edited. offset=$offset index=$index")
        data.forEach { entry ->
            entry.getLong(index)?.let { old ->
                entry.setLong(index, old + offset)
            }
        }
    }
}

private fun warnDuplicates(
    sourceColumns: List<String>,
    sourceData: List<Entry>,
    destColumns: List<String>,
    destData: List<Entry>,
    table: Table
) {
    table.uniques.forEach {
        /* val sourceIndex = sourceColumns.indexOf(it.name)
        val sourceIds = sourceData.mapNotNull { it[sourceIndex] }
        val destIndex = destColumns.indexOf(it.name)
        val destIds = destData.mapNotNull { it[destIndex] }
        val dupes = sourceIds - destIds
        if (dupes.isNotEmpty()) {
            println("\tWarning: ${dupes.size} rows will be overwritten because of $table.${it.name} unique constraint.")
        } */
    }
}

private fun offsetPrimaryKey(
    table: Table,
    data: List<Entry>,
    columns: List<String>,
    computeOffset: (Table) -> Long
) {
    val index = columns.indexOf("_id")
    if (index < 0) {
        println("\tWarning: table $table does not contain _id column.")
    } else {
        val offset = computeOffset(table)
        println("[*] $table: applying offset $offset to primary key.")
        data.forEach { entry ->
            entry.setLong(index, entry.getLong(index)!! + offset)
        }
    }
}

private fun excludeProblematicColumns(
    table: Table,
    data: List<Entry>,
    columns: List<String>,
) {
    table.excludes.forEach {
        println("[*] $table: removing ${it.name} data because it's marked as a problematic column")
        val index = columns.indexOf(it.name)
        data.forEach { entry -> entry[index] = null }
    }
}