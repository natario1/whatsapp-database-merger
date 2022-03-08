package dev.natario

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

sealed class Schema : Iterable<Table> {

    private val tables = mutableListOf<Table>()

    operator fun get(name: String) = tables.first { it.name == name }

    override fun iterator() = tables.iterator()

    protected fun table(
        hasId: Boolean,
        refs: List<Table.Ref> = emptyList(),
        selfRefs: List<String> = emptyList(),
        uniques: List<Table.Unique> = emptyList(),
        excludes: List<Table.Exclude> = emptyList(),
        maxBatch: Int = Int.MAX_VALUE,
        dropFailingBatches: Boolean = false,
        abortOnConflict: Boolean = false,
        timestamp: String? = null
    ) = PropertyDelegateProvider<Schema, ReadOnlyProperty<Schema, Table>> { _, property ->
        val table = Table.newTable(
            name = property.name,
            hasId = hasId,
            refs = refs,
            selfRefs = selfRefs,
            uniques = uniques,
            excludes = excludes,
            maxBatch = maxBatch,
            dropFailingBatches = dropFailingBatches,
            abortOnConflict = abortOnConflict,
            timestamp = timestamp
        ).also {
            tables.add(it)
        }
        ReadOnlyProperty { _, _ -> table }
    }

    // Current schema
    object March2022 : Schema() {
        val jid by table(
            hasId = true,
            uniques = listOf(Table.Unique("raw_string"))
        )

        val messages_quotes by table(
            hasId = true,
            selfRefs = listOf("quoted_row_id"),
            timestamp = "timestamp"
        )

        val messages by table(
            hasId = true,
            refs = listOf(Table.Ref("quoted_row_id", messages_quotes)),
            timestamp = "timestamp"
        )

        val messages_vcards by table(
            hasId = true,
            refs = listOf(Table.Ref("message_row_id", messages)),
        )

        val messages_vcards_jids by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", messages),
                Table.Ref("vcard_row_id", messages_vcards),
            ),
        )

        val message_vcard_jid by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", messages),
                Table.Ref("vcard_jid_row_id", jid),
            ),
        )

        val group_participant_user by table(
            hasId = true,
            refs = listOf(
                Table.Ref("group_jid_row_id", jid),
                Table.Ref("user_jid_row_id", jid),
            ),
            uniques = listOf(Table.Unique("group_jid_row_id", "user_jid_row_id")),
        )

        val group_participant_device by table(
            hasId = true,
            refs = listOf(
                Table.Ref("group_participant_row_id", group_participant_user),
                Table.Ref("device_jid_row_id", jid),
            ),
            uniques = listOf(Table.Unique("group_participant_row_id", "device_jid_row_id")),
        )

        val group_participants by table(
            hasId = true,
            // select * from sqlite_schema where sql like '%group_participant%'. there's an unique index.
            uniques = listOf(Table.Unique("gjid", "jid")),
        )

        // reference count for each media
        val media_refs by table(hasId = true)

        // thumb binary data
        val message_thumbnails by table(
            hasId = false,
            uniques = listOf(Table.Unique("key_remote_jid", "key_from_me", "key_id")),
            maxBatch = 1,
            dropFailingBatches = true,
            timestamp = "timestamp"
        )

        // maybe antispam stuff
        val message_forwarded by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", messages)),
            uniques = listOf(Table.Unique("message_row_id"))
        )

        val messages_links by table(
            hasId = true,
            refs = listOf(Table.Ref("message_row_id", messages))
        )

        val audio_data by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", messages)),
            uniques = listOf(Table.Unique("message_row_id"))
        )

        val user_device_info by table(
            hasId = false,
            refs = listOf(Table.Ref("user_jid_row_id", jid)),
            uniques = listOf(Table.Unique("user_jid_row_id")),
        )

        val chat by table(
            hasId = true,
            refs = listOf(
                Table.Ref("jid_row_id", jid),
                Table.Ref("display_message_row_id", messages),
                Table.Ref("last_message_row_id", messages),
                Table.Ref("last_read_message_row_id", messages),
                // Seen these columns to be inconsistent even in unmodified databases
                Table.Ref("last_read_receipt_sent_message_row_id", messages, ignoreConsistencyChecks = true),
                Table.Ref("last_important_message_row_id", messages, ignoreConsistencyChecks = true),
                Table.Ref("change_number_notified_message_row_id", messages, ignoreConsistencyChecks = true),
                Table.Ref("last_read_ephemeral_message_row_id", messages),
            ),
            uniques = listOf(Table.Unique("jid_row_id")),
        )

        val message_media by table(
            hasId = false,
            refs = listOf(
                // Seen these columns to be inconsistent even in unmodified databases
                Table.Ref("message_row_id", messages, ignoreConsistencyChecks = true),
                Table.Ref("chat_row_id", chat)
            ),
            uniques = listOf(Table.Unique("message_row_id"))
        )

        val receipt_user by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", messages),
                Table.Ref("receipt_user_jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("message_row_id", "receipt_user_jid_row_id"))
        )

        val receipt_device by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", messages),
                Table.Ref("receipt_device_jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("message_row_id", "receipt_device_jid_row_id"))
        )

        val receipts by table(hasId = true)
    }
}