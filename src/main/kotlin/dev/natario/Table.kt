package dev.natario

interface Table {
    val name: String
    val hasId: Boolean
    val refs: List<Ref>
    val uniques: List<Unique>
    val excludes: List<Exclude>
    val maxBatch: Int
    val dropFailingBatches: Boolean
    val abortOnConflict: Boolean
    val timestamp: String?

    data class Ref(val name: String, val table: Table, val ignoreConsistencyChecks: Boolean = false) {
        init {
            require(table.hasId) { "Column $name can't reference table $table without _id." }
        }
    }

    data class Unique(val names: List<String>) {
        constructor(vararg names: String) : this(names.toList())
    }

    data class Exclude(val name: String)

    companion object {
        fun newTable(
            name: String,
            hasId: Boolean,
            refs: List<Ref>,
            selfRefs: List<String>,
            uniques: List<Unique>,
            excludes: List<Exclude>,
            maxBatch: Int,
            dropFailingBatches: Boolean,
            abortOnConflict: Boolean,
            timestamp: String?,
        ): Table {
            val refsAndSelfRefs = refs.toMutableList()
            val table = object : Table {
                override val name = name
                override val hasId = hasId
                override val refs = refsAndSelfRefs
                override val uniques = uniques
                override val excludes = excludes
                override val maxBatch = maxBatch
                override val dropFailingBatches = dropFailingBatches
                override val abortOnConflict = abortOnConflict
                override val timestamp = timestamp
                override fun toString() = name
            }
            refsAndSelfRefs.addAll(selfRefs.map { Ref(it, table) })
            return table
        }
    }
}