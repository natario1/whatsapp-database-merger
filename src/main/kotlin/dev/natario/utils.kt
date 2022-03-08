package dev.natario

fun Database.split(schema: Schema, minTimestamp: Long) {
    schema.forEach {
        val deleted = if (it.timestamp == null) {
            delete(it.name)
        } else {
            delete(it.name, "${it.timestamp} < $minTimestamp")
        }
        println("[*] deleted $deleted entries from table $it")
    }
}