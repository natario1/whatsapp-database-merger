package dev.natario

enum class Table(
    val refs: List<Ref> = emptyList(),
    val uniques: List<Unique> = emptyList(),
    val excludes: List<Exclude> = emptyList(),
    val maxBatch: Int = Int.MAX_VALUE,
    val dropFailingBatches: Boolean = false,
    val overwrite: Boolean = true,
) {
    jid(
        uniques = listOf(Unique("raw_string")),
    ),

    messages_quotes(
        refs = listOf(Ref("quoted_row_id", "messages_quotes"))
    ),

    messages(
        refs = listOf(Ref("quoted_row_id", messages_quotes.name)),
    ),

    messages_vcards(
        refs = listOf(Ref("message_row_id", messages.name)),
    ),

    messages_vcards_jids(
        refs = listOf(
            Ref("message_row_id", messages.name),
            Ref("vcard_row_id", messages_vcards.name),
        ),
    ),

    message_vcard_jid(
        refs = listOf(
            Ref("message_row_id", messages.name),
            Ref("vcard_jid_row_id", jid.name),
        ),
    ),

    group_participant_user(
        refs = listOf(
            Ref("group_jid_row_id", jid.name),
            Ref("user_jid_row_id", jid.name),
        ),
    ),

    group_participant_device(
        refs = listOf(
            Ref("group_participant_row_id", group_participant_user.name),
            Ref("device_jid_row_id", jid.name),
        ),
    ),

    group_participants(
        // select * from sqlite_schema where sql like '%group_participants%'
        // there's an unique index.
        uniques = listOf(Unique("gjid", "jid")),
    ),

    // reference count for each media
    media_refs,

    // thumb binary data
    message_thumbnails(
        maxBatch = 1,
        dropFailingBatches = true
    ),

    // maybe antispam stuff
    message_forwarded(
        refs = listOf(Ref("message_row_id", messages.name))
    ),

    messages_links(
        refs = listOf(Ref("message_row_id", messages.name))
    ),

    audio_data(
        refs = listOf(Ref("message_row_id", messages.name))
    ),

    user_device_info(
        refs = listOf(Ref("user_jid_row_id", jid.name))
    ),

    chat(
        refs = listOf(
            Ref("jid_row_id", jid.name),
            Ref("display_message_row_id", messages.name),
            Ref("last_message_row_id", messages.name),
            Ref("last_read_message_row_id", messages.name),
            // Seen these column to be inconsistent even in unmodified databases
            Ref("last_read_receipt_sent_message_row_id", messages.name, ignoreConsistencyChecks = true),
            Ref("last_important_message_row_id", messages.name, ignoreConsistencyChecks = true),
            Ref("change_number_notified_message_row_id", messages.name, ignoreConsistencyChecks = true),
            Ref("last_read_ephemeral_message_row_id", messages.name),
        ),
        uniques = listOf(Unique("jid_row_id")),
    ),

    message_media(
        refs = listOf(
            Ref("message_row_id", messages.name, ignoreConsistencyChecks = true),
            Ref("chat_row_id", chat.name)
        )
    ),

    receipt_user(
        refs = listOf(
            Ref("message_row_id", messages.name),
            Ref("receipt_user_jid_row_id", jid.name)
        )
    ),

    receipt_device(
        refs = listOf(
            Ref("message_row_id", messages.name),
            Ref("receipt_device_jid_row_id", jid.name)
        )
    ),

    receipts;

    class Ref(val name: String, private val tableName: String, val ignoreConsistencyChecks: Boolean = false) {
        val table get() = values().first { it.name == tableName }
    }

    class Unique(vararg names: String) {
        val names = names.toList()
    }

    class Exclude(val name: String)
}