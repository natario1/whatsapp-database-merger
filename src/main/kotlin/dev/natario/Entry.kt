package dev.natario

import com.squareup.sqldelight.db.SqlCursor

class Entry(data: List<String?>) {

    constructor(cursor: SqlCursor, count: Int) : this(
        data = (0 until count).map { cursor.getString(it) }
    )

    private val data = data.toMutableList()

    fun remove(indices: List<Int>) {
        indices.sortedDescending().forEach {
            data.removeAt(it)
        }
    }

    override fun toString(): String {
        return toString(false)
    }

    // https://sqlite.org/nulinstr.html
    // if useSplit, logic is simpler but it can have ||char(0)||''||char(0)... which in turn
    // can give "Expression tree is too large (maximum depth 1000)".
    // Even without split, this can still happen for strings with lots of \u0000 and AFAIU
    // there's no way around it other than drop the entry. Maybe use binary literals X'...'
    private fun toString(useSplit: Boolean) = data.map {
        when {
            it == null -> "NULL"
            useSplit -> it.split('\u0000').joinToString(
                separator = "||char(0)||",
                transform = { "'${it.replace("'", "''")}'" }
            )
            else -> buildString {
                // Probably can be rewritten more concisely
                fun StringBuilder.appendNulls(count: Int) {
                    if (count <= 0) return
                    if (length > 0) repeat(count) { append("||char(0)") }
                    else {
                        append("char(0)")
                        repeat(count - 1) { append("||char(0)") }
                    }
                }
                val parts = it.split('\u0000')
                var nulls = 0
                parts.forEachIndexed { index, part ->
                    if (index > 0) nulls += 1
                    if (part.isNotEmpty()) {
                        appendNulls(nulls)
                        nulls = 0
                        if (length > 0) append("||")
                        append("'${part.replace("'", "''")}'")
                    }
                }
                appendNulls(nulls)
                if (length == 0) append("''")
            }
        }
    }.joinToString(
        prefix = "(",
        postfix = ")"
    )

    operator fun get(index: Int) = data[index]

    operator fun set(index: Int, value: String?) {
        data[index] = value
    }
}

fun Entry.getLong(index: Int) = this[index]?.toLong()
fun Entry.setLong(index: Int, value: Long) {
    this[index] = value.toString()
}