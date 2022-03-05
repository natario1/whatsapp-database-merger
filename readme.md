# whatsapp-database-merger

A small utility that can be used to merge multiple WhatsApp databases (`msgstore.db`) into one.
A few notes:
- input databases must be already decrypted
- output database will also be in decrypted form.
  To use it, it must either be moved to `/data/data/com.whatsapp/databases` on a rooted phone,
  or otherwise encrypted again and restored as usual.
- Java 11 is required to run the program.
- this is a JVM tool that must be compiled. You can just `./gradlew build` or use the IntelliJ run
  configuration that is checked into source code.
- The only argument required is a path to a directory on your local machine. For example,
  if `mydir` is specified, the program expects all input databases in `mydir/input` and will create
  output in `mydir/output/msgstore.db`.

### How it works

The program is inspired by [whapa](https://github.com/B16f00t/whapa). WhatsApp msgstore.db is just a SQLite database
and as such it can be read and written to with common tools.

The program will merge all relevant tables, not just messages and chats. See `src/main/kotlin/dev/natario/Table` for a list.

When merging them, extra care is taken:
- primary keys, when possible, are given the appropriate offset in order to not collide with existing entries in the other
  database(s).
- When this happens, we also apply the same offset in every other column of the database that were 
  referencing them, ensuring consistency of data.

For example, `messages._id` will receive an offset to avoid collision, then the program will also
modify all other columns pointing to messages, like for example `chat.last_read_message_row_id`.

### Known issues

The tool can have troubles with binary data, especially in the `message_thumbnails` table. If the data contains many null bytes (`\u0000`),
the SQL string will include many concatenations (like `'foo'||char(0)||'bar'`) and there is a hard limit in the driver about 
how many concatenation operations it can handle for a single statement.

By default, the merger will simply drop thumbnails that it was not able to copy.

### It doesn't work for me!

I'm sorry. This happened to work for me in March 2022, but I am unlikely to spend time on this utility in the future,
especially since the database schema can change over time. However, I am happy to review and accept pull requests. 
The source code should be very easy to understand.