# whatsapp-database-merger

A small command-line utility that can be used to merge multiple WhatsApp databases (`msgstore.db`) into one.
A few notes:
- input databases must be already decrypted
- output database will also be in decrypted form. To use it, it must either be moved to `/data/data/com.whatsapp/databases` on a rooted phone,
  or otherwise encrypted again and restored as usual.
- Java 11 should be installed to build and run the program.

### Usage

You can either build the tool yourself or download the binary from the [releases page](https://github.com/natario1/whatsapp-database-merger/releases).
The tool requires a single argument - a path to a directory on your local machine. For example:

```
./whatsapp-database-merger /Users/Me/Desktop/WhatsApp
```

In this example, the program will look for input databases in the `/Users/Me/Desktop/WhatsApp/input` folder, 
and will create the merged database in `/Users/Me/Desktop/WhatsApp/output`.

### How it works

The program is inspired by [whapa](https://github.com/B16f00t/whapa). WhatsApp msgstore.db is just a SQLite database
and as such it can be read and written to with common tools.

The program will merge all relevant tables, not just messages and chats. See `src/main/kotlin/dev/natario/Table` for a list.

When merging them, extra care is taken:
- primary keys, when possible, are given the appropriate offset in order to not collide with existing entries in the other
  database(s).
- When this happens, we also apply the same offset in every other column of the database that were referencing them, 
  ensuring consistency of data.
- Special logic addresses the case where entries could not be inserted due to unique constraint defined in the database,
  in which case all references to the skipped entry will be updated to match the existing entry which prevailed.

For example, `messages._id` will receive an offset to avoid collision, then the program will also
modify all other columns pointing to messages, like `chat.last_read_message_row_id`.

### Known issues

The tool can have troubles with binary data, especially in the `message_thumbnails` table. If the data contains many null bytes (`\u0000`),
the SQL string will include many concatenations (like `'foo'||char(0)||'bar'`) and there is a hard limit in the driver about 
how many concatenation operations it can handle for a single statement.

By default, the merger will simply drop thumbnails that it was not able to copy.

This version makes it easier for the user to search for the entries in `message` table that should have a `sender_jid_row` of 0 by searching for the id that will be reported as missing in the output consistency check. They can then fix it if they like.
The user needs to change the `sort_id` for the first line in `message` table to 1 from 0, otherwise you will not get the full output consistency check as it will fail on this column.

### It doesn't work for me!

I'm sorry. This happened to work for me in March 2022, but I am unlikely to spend too much time on this utility in the future,
especially since the database schema can change very quickly. However, I am happy to review and accept pull requests. 
The source code should be very easy to understand.
