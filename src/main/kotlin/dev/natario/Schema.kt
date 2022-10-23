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
    object October2022 : Schema() {
        val jid by table(
            hasId = true,
            uniques = listOf(Table.Unique("raw_string")) //has other columns defined as unique together but raw string is the result of their combination
        )

        val chat: Table by table(
            hasId = true,
            refs = listOf(
                Table.Ref("jid_row_id", jid),
                Table.Ref("display_message_row_id", { message }),
                Table.Ref("last_message_row_id", { message }),
                Table.Ref("last_read_message_row_id", { message }, ignoreConsistencyChecks = true),
                // Seen these columns to be inconsistent even in unmodified databases
                Table.Ref("last_read_receipt_sent_message_row_id", { message }, ignoreConsistencyChecks = true),
                Table.Ref("last_important_message_row_id", { message }, ignoreConsistencyChecks = true),
                Table.Ref("change_number_notified_message_row_id", { message }, ignoreConsistencyChecks = true),
                // New October schema additions
                Table.Ref("last_read_ephemeral_message_row_id", { message }, ignoreConsistencyChecks = true),
                Table.Ref("last_message_reaction_row_id", { message }, ignoreConsistencyChecks = true),
                Table.Ref("last_seen_message_reaction_row_id", { message }, ignoreConsistencyChecks = true),
                // These are sort_ids just using message as the sort_id therefore should have same offset
                Table.Ref("last_read_message_sort_id", { message }, ignoreConsistencyChecks = true),
                Table.Ref("display_message_sort_id", { message }),
                Table.Ref("last_message_sort_id", { message }),
                Table.Ref("last_read_receipt_sent_message_sort_id", { message }, ignoreConsistencyChecks = true)
            ),
            uniques = listOf(Table.Unique("jid_row_id")),
        )
	//Typically ConsistencyChecks will fail on 'last read' style messages because you may be missing that msg in an even older database.
	//Also fails when there is a -1 value there which is not possible for an ID, may be due to corruption?
	
        val message by table(
            hasId = true,
            refs = listOf(
                Table.Ref("chat_row_id", chat, ignoreConsistencyChecks = true), //All my database had a -1 entry, unsure if this is typical
                Table.Ref("sender_jid_row_id", jid, ignoreConsistencyChecks = true),
		),
            selfRefs = listOf("sort_id"),
            uniques = listOf(Table.Unique("chat_row_id", "from_me", "key_id", "sender_jid_row_id")),
            timestamp = "timestamp"
        )

        val message_quoted by table(
            hasId = false,
            refs = listOf(
                Table.Ref("message_row_id", message),
                Table.Ref("chat_row_id", chat),
                Table.Ref("parent_message_chat_row_id", chat),
                Table.Ref("sender_jid_row_id", jid),
            ),
            timestamp = "timestamp"
        )

        val message_vcard by table(
            hasId = true,
            refs = listOf(Table.Ref("message_row_id", message)),
            uniques = listOf(Table.Unique("message_row_id", "vcard"))
        )

        val message_vcard_jid by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", message),
                Table.Ref("vcard_row_id", message_vcard),
                Table.Ref("vcard_jid_row_id", jid),
            ),
            uniques = listOf(Table.Unique("vcard_jid_row_id", "vcard_row_id", "message_row_id"))
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

        // thumb binary data in March schema - my October one is empty
        val message_thumbnails by table(
            hasId = false,
            uniques = listOf(Table.Unique("key_remote_jid", "key_from_me", "key_id")),
            maxBatch = 1,
            dropFailingBatches = true,
            timestamp = "timestamp"
        )
		
        // Potentially new thumb binary data location in October schema
        val message_thumbnail by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message)),
            maxBatch = 1,
            dropFailingBatches = true
        )

        // maybe antispam stuff
        val message_forwarded by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message)),
            uniques = listOf(Table.Unique("message_row_id")) // not techinically constraint in table but probably no harm
        )

        val message_link by table(
            hasId = true,
            refs = listOf(
            	Table.Ref("chat_row_id", chat),
            	Table.Ref("message_row_id", message)
            ),
            uniques = listOf(Table.Unique("message_row_id", "link_index")),
        )

        val message_add_on by table(
            hasId = true,
            refs = listOf(
            	Table.Ref("chat_row_id", chat),
                Table.Ref("sender_jid_row_id", jid, ignoreConsistencyChecks = true),//uses -1 instead of 0 for messages from self
            	Table.Ref("parent_message_row_id", message)
            ),
            uniques = listOf(Table.Unique("chat_row_id", "from_me", "key_id", "sender_jid_row_id")),
        )

        val message_add_on_reaction by table(
            hasId = false,
            refs = listOf(Table.Ref("message_add_on_row_id", message_add_on)),
        )

        val message_system by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message)),
        )

        val message_system_value_change by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message)),
        )
	
        val message_location by table(
            hasId = false,
            refs = listOf(
                Table.Ref("message_row_id", message),
                Table.Ref("chat_row_id", chat)
            ),
            uniques = listOf(Table.Unique("message_row_id", "jid_row_id"))
        )
		
		val message_mentions by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", message),
                Table.Ref("jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("message_row_id", "jid_row_id"))
        )
		
		//quoted additions,current duplicated of those in message_quoted but with type specific columns
		
		val message_quoted_location by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message))
        )
		
		val message_quoted_media by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message))
        )
		
		val message_quoted_mentions by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", message),
                Table.Ref("jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("message_row_id", "jid_row_id"))
        )
		
		val message_quoted_text by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message))
        )
		
		val message_quoted_vcard by table(
            hasId = true,
            refs = listOf(Table.Ref("message_row_id", message)),
            uniques = listOf(Table.Unique("message_row_id", "vcard"))
        )
		
		val message_ftsv2_docsize by table(
            hasId = false,
            refs = listOf(Table.Ref("docid", message))
        )
		
		val message_ftsv2_content by table(
            hasId = false,
            refs = listOf(Table.Ref("docid", message))
        )
		
		val message_view_once_media by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message))
        )

        val audio_data by table(
            hasId = false,
            refs = listOf(Table.Ref("message_row_id", message)),
            uniques = listOf(Table.Unique("message_row_id")) // not techinically constraint in table but probably no harm
        )

        val user_device_info by table(
            hasId = false,
            refs = listOf(Table.Ref("user_jid_row_id", jid)),
            uniques = listOf(Table.Unique("user_jid_row_id")),
            timestamp = "timestamp"
        )

        val user_device by table(
            hasId = true,
            refs = listOf(
            	Table.Ref("user_jid_row_id", jid),
            	Table.Ref("device_jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("user_jid_row_id","device_jid_row_id"))
        )

        val message_media by table(
            hasId = false,
            refs = listOf(
                // Seen these columns to be inconsistent even in unmodified databases. Potentially from deleted media msgs
                Table.Ref("message_row_id", message, ignoreConsistencyChecks = true),
                Table.Ref("chat_row_id", chat)
            ),
            uniques = listOf(Table.Unique("message_row_id")) // not techinically constraint in table but probably no harm
        )

        val receipt_user by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", message),
                Table.Ref("receipt_user_jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("message_row_id", "receipt_user_jid_row_id"))
        )

        val receipt_device by table(
            hasId = true,
            refs = listOf(
                Table.Ref("message_row_id", message),
                Table.Ref("receipt_device_jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("message_row_id", "receipt_device_jid_row_id"))
        )
		
        // Would this be unnecessary since it is named orphaned and doesnt refer to a msg
		val receipt_orphaned by table(
            hasId = true,
            refs = listOf(
                Table.Ref("chat_row_id", chat, ignoreConsistencyChecks = true),
                Table.Ref("receipt_device_jid_row_id", jid, ignoreConsistencyChecks = true),
                Table.Ref("receipt_recipient_jid_row_id", jid, ignoreConsistencyChecks = true)
            ),
            uniques = listOf(Table.Unique("chat_row_id", "from_me", "key_id", "receipt_device_jid_row_id", "receipt_recipient_jid_row_id", "status"))
        )

        val receipts by table(hasId = true)
	
		val community_chat by table(
            hasId = false,
            refs = listOf(Table.Ref("chat_row_id", chat)),
            timestamp = "last_activity_ts"
        )
		
		val call_log by table(
            hasId = true,
            refs = listOf(
                Table.Ref("jid_row_id", jid),
                Table.Ref("group_jid_row_id", jid),
                Table.Ref("call_creator_device_jid_row_id", jid),
            ),
            uniques = listOf(Table.Unique("jid_row_id", "from_me", "call_id", "transaction_id")),
            timestamp = "timestamp"
        )
		
		val call_log_participant_v2 by table(
            hasId = true,
            refs = listOf(
                Table.Ref("call_log_row_id", call_log),
                Table.Ref("jid_row_id", jid)
            ),
            uniques = listOf(Table.Unique("call_log_row_id", "jid_row_id")),
            timestamp = "timestamp"
        )
    }
	
	// Previous schema
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
