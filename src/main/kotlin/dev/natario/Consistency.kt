package dev.natario

fun Database.ensureConsistent(throwOnFailure: Boolean) {
    Table.values().forEach { table ->
        if (table.refs.isEmpty()) return@forEach
        val columns = columns(table.name)
        val entries = query(table.name)
        table.refs.forEach { ref ->
            val index = columns.indexOf(ref.name)
            // Check that all referenced values exist in the referenced table.
            val values: List<String> = entries.mapNotNull { it[index] }
                .distinct()
                .filterNot { it == "0" }
            val results = count(ref.table.name, "_id in (${values.joinToString()})")
            if (results != values.size.toLong()) {
                val message = "Database $this is not consistent. Column ${table}.${ref.name} references table ${ref.table}, " +
                        "where we expected to find a list of ${values.size} entries, but only $results were found.\n" +
                        "Total count (source): ${count(ref.table.name)}, total count (target): ${count(table.name)}\n" +
                        "Missing=${values.toSet() - query(ref.table.name, "_id in (${values.joinToString()})").mapNotNull { it[0] }.toSet()}"
                if (throwOnFailure && !ref.ignoreConsistencyChecks) {
                    error(message)
                } else {
                    println("\tWarning: $message")
                }
            }
        }
    }
}