package dev.natario

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import java.nio.file.Path
import kotlin.io.path.*

enum class MergeType {
    Combine, Append
}

fun main(args: Array<String>) {
    val parser = ArgParser("whatsapp-database-merger")
    val schema = Schema.October2022
    val dir by parser.argument(
        type = ArgType.String,
        fullName = "root",
        description = "Root directory. Put input databases in <root>/input. Expect output in <root>/output."
    )
    val type by parser.option(
        type = ArgType.Choice<MergeType>(),
        shortName = "t",
        description = "Merge type. Use 'combine' for smart conflict detection, 'append' to append rows without modifications (risky)."
    ).default(MergeType.Combine)
    val batch by parser.option(
        type = ArgType.Int,
        shortName = "b",
        description = "How many rows to copy per database insert call."
    ).default(500)
    parser.parse(args)

    val root = Path.of(dir).absolute().normalize()
    val inputs = root
        .resolve("input")
        .createDirectories()
        .listDirectoryEntries("*.db")
        .sortedBy { it.name }
        .map { Database(it, root) }
    /*if (true) {
        println("[*] SPLITTING")
        inputs.single().split(schema, minTimestamp = 1646362037426)
        return
    }*/
    if (inputs.isEmpty()) error("No inputs found in ${root.resolve("input")}.")
    if (inputs.size == 1) error("Only 1 db found in ${root.resolve("input")}, nothing to merge.")
    println("[*] Input databases:\n${inputs.joinToString("\n") { "\t- $it" }}")
    inputs.forEach {
        println("[*] Checking consistency of $it")
        it.verifyConsistency(schema)
    }

    val output = root
        .resolve("output")
        .createDirectories()
        .resolve("msgstore.db")
        .let { Database(it, root) }
    println("[*] Output database: $output")
    if (output.exists()) println("\tWARNING! Output database exists, so it will be overwritten.")

    println("[*] Copying ${inputs.first()} to $output")
    inputs.first().copyTo(output)

    val remaining = inputs.drop(1)
    remaining.forEach {
        println("[*] Processing $it")
        merge(
            schema = schema,
            source = it,
            destination = output,
            type = type,
            batch = batch
        )
    }

    println("[*] Checking consistency of $output")
    output.verifyConsistency(schema)
    println("Success.")
}

private fun merge(schema: Schema, source: Database, destination: Database, type: MergeType, batch: Int) {
    val mappings = mutableMapOf<Table, IdMapping>()
    schema.forEach { table ->
        println("[*] processing table $table")
        require(source.tables().contains(table.name)) {
            "$source does not contain expected table $table. Unexpected WhatsApp version?"
        }
        require(destination.tables().contains(table.name)) {
            "$destination does not contain expected table $table. Unexpected WhatsApp version?"
        }

        val sourceData = source.query(table.name).takeIf { it.isNotEmpty() } ?: return@forEach
        val destData = destination.query(table.name)
        var sourceColumns = source.columns(table.name)
        val destColumns = destination.columns(table.name)
        println("\t- $source: ${sourceData.size} rows, columns: $sourceColumns")
        println("\t- $destination: ${destData.size} rows, columns: $destColumns")

        if (sourceColumns.toSet() != destColumns.toSet()) {
            if (destColumns.all { it in sourceColumns }) {
                println("\tWarning: schema mismatch for table $table. Different WhatsApp version? Trying to go on anyway, ignoring missing columns.")
                // some source columns are missing in dest. Try processing anyway.
                val missing = sourceColumns.mapIndexedNotNull { index, s -> index.takeIf { s !in destColumns } }
                sourceColumns = sourceColumns.filterIndexed { index, s -> index in missing }
                sourceData.forEach { it.remove(missing) }
            } else {
                error("""
                    Schema mismatch for table $table between $source and $destination. Different WhatsApp versions?
                    - $source: $sourceColumns
                    - $destination: $destColumns
                """.trimIndent()
                )
            }
        }

        if (type == MergeType.Combine) {
            processReferences(
                columns = sourceColumns,
                entries = sourceData,
                table = table,
                selfReferences = false, // can't process self references before IdMapping has been computed. Will do later
                getMapping = { requireNotNull(mappings[it]) { "Table $table depends on $it but mapping was not computed." } },
            )
            if (table.hasId) {
                computeIdMapping(
                    sourceColumns = sourceColumns,
                    sourceData = sourceData,
                    destColumns = destColumns,
                    destData = destData,
                    table = table
                ).also {
                    mappings[table] = it
                }
                applyIdMapping(
                    // TODO: could apply mapping to self references here, instead of having a separate step below.
                    entries = sourceData,
                    table = table,
                    mapping = mappings[table]!!
                )
            }
            processReferences(
                columns = sourceColumns,
                entries = sourceData,
                table = table,
                selfReferences = true,
                getMapping = { requireNotNull(mappings[it]) { "Table $table depends on $it but mapping was not computed." } },
            )
        }
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
            batch = batch
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
    var processed = 0L
    var inserted = 0L
    var failed = 0L
    var skipped = 0L
    data.chunked(minOf(batch, table.maxBatch)).forEach { entries ->
        try {
            val new = destination.insert(
                table = table.name,
                columns = columns,
                entries = entries,
                // Never use replace, we must keep the old data otherwise we have no way to correct already inserted
                // references of the new entry in the existing database.
                onConflict = if (table.abortOnConflict) Database.OnConflict.Abort else Database.OnConflict.Skip
            )
            processed += entries.size
            inserted += new
            skipped += entries.size - new
            println("\tprocessed $processed/${data.size}, inserted=$inserted/$processed, failed=$failed/$processed, skipped=$skipped/$processed...")
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
    println("[*] $table: processed ${data.size} entries, failed=$failed/${data.size}, skipped=$skipped/${data.size}, new total is ${destination.count(table.name)}.")
}

private fun computeIdMapping(
    sourceColumns: List<String>,
    sourceData: List<Entry>,
    destColumns: List<String>,
    destData: List<Entry>,
    table: Table
): IdMapping {
    require(table.hasId) { "Can't compute mapping if table does not have an ID." }
    val offset = destData.lastOrNull()?.let { it.getLong(0)!! } ?: 0L
    val mapping = IdMapping(offset = offset)
    if (table.abortOnConflict) {
        // Let other conflicts fail
    } else {
        // Handle other non-id conflicts by looking at the uniques. This is important because some entries might be
        // skipped because of this. So if entry X is skipped because of entry Y, we must remove all references to X
        // in next tables.
        table.uniques.forEach { unique ->
            val sourceIndices = unique.names.map { sourceColumns.indexOf(it) }
            val destIndices = unique.names.map { destColumns.indexOf(it) }
            var dupes = 0
            sourceData.forEach { sourceEntry ->
                val otherEntry = destData.firstOrNull { destEntry ->
                    unique.names.indices.all {
                        sourceEntry[sourceIndices[it]] == destEntry[destIndices[it]]
                    }
                }
                if (otherEntry != null) {
                    mapping.map(sourceEntry.getLong(0)!!, otherEntry.getLong(0)!!)
                    dupes++
                }
            }
            if (dupes > 0) {
                println("\tWarning: $dupes rows will be skipped because of $table unique constraint on ${unique.names}.")
            }
        }
    }
    return mapping
}

private fun applyIdMapping(
    table: Table,
    entries: List<Entry>,
    mapping: IdMapping
) {
    require(table.hasId) { "Can't apply mapping if table $table has no id." }
    println("[*] $table: mapping IDs of ${entries.size} entries (offset=${mapping.offset}, dupes=${mapping.extras})")
    entries.forEach { entry ->
        val old = entry.getLong(0)!!
        entry.setLong(0, mapping[old])
        // println("id $old is now ${mapping[old]}")
    }
}

private fun processReferences(
    columns: List<String>,
    entries: List<Entry>,
    table: Table,
    selfReferences: Boolean,
    getMapping: (Table) -> IdMapping,
) {
    val refs = table.refs.filter {
        if (selfReferences) it.table == table
        else it.table != table
    }
    refs.forEach {
        val mapping = getMapping(it.table)
        val index = columns.indexOf(it.name)
        println("[*] $table: editing column ${it.name} (${entries.size} entries), because it references table ${it.table} which was previously edited (offset=${mapping.offset}, dupes=${mapping.extras})")
        entries.forEach loop@ { entry ->
            val old = entry.getLong(index) ?: return@loop
            entry.setLong(index, mapping[old])
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
