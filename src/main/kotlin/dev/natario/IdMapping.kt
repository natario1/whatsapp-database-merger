package dev.natario

class IdMapping(val offset: Long) {

    private val extraMappings = mutableMapOf<Long, Long>()

    val extras get() = extraMappings.size

    fun map(id: Long, toId: Long) {
        extraMappings[id] = toId
    }

    operator fun get(id: Long): Long {
        return extraMappings[id] ?: (id + offset + 1)
    }
}
