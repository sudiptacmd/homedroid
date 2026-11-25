package dev.homedroid

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteProgram
import android.os.CancellationSignal
import android.os.OperationCanceledException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The dashboard's database manager: an SQLite browser for the files apps keep (Home
 * Assistant, Jellyfin, AdGuard, deployments) and a small JSON document store.
 *
 * Databases are opened in place, read-only, rather than copied: Jellyfin's library can be
 * hundreds of MB. A read-only connection only takes shared locks (in WAL mode, a read mark in
 * the -shm file, like any other reader); it never checkpoints, changes the journal mode or
 * writes android_metadata (NO_LOCALIZED_COLLATORS). Each request opens and closes its own
 * connection and queries are stopped after [TIMEOUT_S], so a running app's checkpoints are
 * never held back for long. If a WAL database has no -shm file yet, SQLite creates one, as the
 * sqlite3 shell would; the app uses it as its own. Android's default error handler deletes a
 * database it thinks is corrupt, so every open passes one that does nothing.
 *
 * Writing is opt-in per statement (write + confirm) and refused while the app owning the
 * file is running. A WAL database is opened with WAL enabled, or Android would switch it back
 * to a rollback journal.
 */
class Databases(
    private val paths: Paths,
    private val files: FileBrowser,
    private val apps: Apps,
    private val deploys: Deploys,
    private val alpine: Alpine,
) {
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "db-timeout").apply { isDaemon = true } }
    private val docsDir = File(paths.root, "db")

    private class Owner(val name: String, val services: List<String>)

    fun roots(): List<FileRoot> = buildList {
        addAll(files.roots())
        if (alpine.root.isDirectory) add(FileRoot("alpine", "Alpine (app settings and databases)", alpine.root))
        add(FileRoot("homedroid", "Homedroid documents", docsDir.apply { mkdirs() }))
    }.distinctBy { it.id }

    fun handle(r: Request, seg: List<String>): Response = guard {
        when {
            r.method == "GET" && seg.isEmpty() -> Response.json(list(r.query["refresh"] == "1"))
            r.method == "GET" && seg == listOf("schema") -> open(r.query) { db, f -> schema(db, f) }
            r.method == "GET" && seg == listOf("rows") -> open(r.query) { db, _ -> rows(db, r.query) }
            r.method == "POST" && seg == listOf("query") -> query(r.json())
            else -> Response.error(404, "no such endpoint")
        }
    }

    fun handleDocs(r: Request, seg: List<String>): Response = guard {
        val col = seg.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val id = seg.getOrNull(1)?.let(Docs::id)
        when {
            col == null && r.method == "GET" -> Response.json(collections())
            col == null || seg.size > 2 -> Response.error(404, "no such endpoint")
            r.method == "GET" && id == null -> findDocs(col, r.query)
            r.method == "GET" -> getDoc(col, id!!)?.let { Response.json(it) } ?: Response.error(404, "No document $id")
            r.method == "POST" && id == null -> insertDoc(col, r.json())
            r.method == "PUT" && id != null -> replaceDoc(col, id, r.json())
            r.method == "DELETE" && id != null -> deleteDoc(col, id)
            r.method == "DELETE" -> dropCollection(col)
            else -> Response.error(404, "no such endpoint")
        }
    }

    fun close() {
        synchronized(this) { docs?.close(); docs = null }
        timer.shutdownNow()
    }

    private fun guard(block: () -> Response): Response = try {
        block()
    } catch (e: IllegalArgumentException) {
        Response.error(400, e.message ?: "invalid request")
    } catch (_: OperationCanceledException) {
        Response.error(408, "Stopped after $TIMEOUT_S seconds; narrow the query down")
    } catch (_: SQLiteDatabaseLockedException) {
        Response.error(409, "The database is busy; try again")
    } catch (e: SQLiteException) {
        Response.error(400, e.message?.substringBefore(" (code")?.ifBlank { null } ?: "SQLite error")
    }

    // --- finding database files ----------------------------------------------------------

    private var listed: Pair<Long, JSONArray>? = null

    @Synchronized
    private fun list(refresh: Boolean): JSONArray {
        listed?.let { (at, list) -> if (!refresh && System.currentTimeMillis() - at < CACHE_MS) return list }
        val deadline = System.currentTimeMillis() + SCAN_MS
        val found = ArrayList<JSONObject>()
        val owners = owners()
        for (root in roots()) {
            val base = root.dir.canonicalFile
            var dirs = 0
            fun walk(dir: File, depth: Int) {
                if (found.size >= MAX_FILES || dirs++ >= MAX_DIRS || System.currentTimeMillis() > deadline) return
                for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                    if (Files.isSymbolicLink(f.toPath())) continue
                    if (f.isDirectory) {
                        if (!DbFiles.skip(f.name, depth + 1, root.id)) walk(f, depth + 1)
                    } else if (DbFiles.candidate(f.name) && found.size < MAX_FILES) {
                        val header = header(f) ?: continue
                        if (DbFiles.isSqlite(header)) found += describe(root, f.relativeTo(base).path, f, header, owners)
                    }
                }
            }
            walk(base, 0)
        }
        return jsonArray(found).also { listed = System.currentTimeMillis() to it }
    }

    private fun describe(root: FileRoot, rel: String, f: File, header: ByteArray, owners: Map<String, List<Pair<String, Owner>>>): JSONObject {
        val owner = DbFiles.owner(rel, owners[root.id].orEmpty())
        return JSONObject().put("root", root.id).put("rootLabel", root.label).put("path", rel).put("name", f.name)
            .put("size", f.length()).put("walSize", File(f.path + "-wal").length()).put("modified", f.lastModified())
            .put("wal", DbFiles.isWal(header)).put("app", owner?.name ?: JSONObject.NULL).put("inUse", owner?.let(::running) == true)
    }

    private fun header(f: File): ByteArray? = try {
        RandomAccessFile(f, "r").use { raf -> ByteArray(100).also { if (raf.read(it) < 100) return null } }
    } catch (_: Exception) {
        null
    }

    /** Which app a database belongs to, by root and path prefix. */
    private fun owners(): Map<String, List<Pair<String, Owner>>> {
        val installed = apps.installed()
        val inAlpine = installed.flatMap { a -> a.data.map { it.trim('/') to Owner(a.name, a.serviceNames) } } +
            deploys.all().map { "srv/${it.id}" to Owner(it.name, listOf(it.service)) }
        val libraries = installed.filter { it.storagePath != null }.groupBy { it.storageKey.lowercase() }
            .mapValues { (_, g) -> listOf("" to Owner(g.joinToString { it.name }, g.flatMap { it.serviceNames })) }
        return libraries + ("alpine" to inAlpine)
    }

    private fun running(o: Owner) = o.services.any { name ->
        ServerService.supervisor?.daemons?.firstOrNull { it.spec.name == name }?.state.let { it != null && it != Supervisor.State.STOPPED }
    }

    // --- opening ----------------------------------------------------------------------------

    private class Target(val root: FileRoot, val rel: String, val file: File, val header: ByteArray)

    private fun target(q: Map<String, String>): Target {
        val root = roots().firstOrNull { it.id == q["root"] } ?: throw IllegalArgumentException("No such location")
        val rel = q["path"].orEmpty()
        val f = DbFiles.resolve(root.dir, rel) ?: throw IllegalArgumentException("Pick a file inside ${root.label}")
        val header = f.takeIf { it.isFile }?.let(::header)?.takeIf(DbFiles::isSqlite)
            ?: throw IllegalArgumentException("${f.name} is not an SQLite database")
        return Target(root, f.relativeTo(root.dir.canonicalFile).path, f, header)
    }

    private fun <T> open(t: Target, write: Boolean, block: (SQLiteDatabase) -> T): T {
        var flags = SQLiteDatabase.NO_LOCALIZED_COLLATORS or if (write) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
        if (write && DbFiles.isWal(t.header)) flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        return SQLiteDatabase.openDatabase(t.file.path, null, flags, KEEP_FILES).use(block)
    }

    private fun open(q: Map<String, String>, block: (SQLiteDatabase, Target) -> JSONObject): Response {
        val t = target(q)
        return Response.json(open(t, write = false) { block(it, t) })
    }

    /** A query with typed parameters (rawQuery only binds strings), stopped by [signal]. */
    private fun cursor(db: SQLiteDatabase, sql: String, args: List<Any?> = emptyList(), signal: CancellationSignal? = null): Cursor =
        db.rawQueryWithFactory({ _, driver, table, query -> bind(query, args); SQLiteCursor(driver, table, query) }, sql, null, "", signal)

    private fun bind(p: SQLiteProgram, args: List<Any?>) = args.forEachIndexed { i, v ->
        when (v) {
            null -> p.bindNull(i + 1)
            is Long -> p.bindLong(i + 1, v)
            is Double -> p.bindDouble(i + 1, v)
            is ByteArray -> p.bindBlob(i + 1, v)
            else -> p.bindString(i + 1, v.toString())
        }
    }

    private fun <T> timed(block: (CancellationSignal) -> T): T {
        val signal = CancellationSignal()
        val stop = timer.schedule({ signal.cancel() }, TIMEOUT_S, TimeUnit.SECONDS)
        try {
            return block(signal)
        } finally {
            stop.cancel(false)
        }
    }

    private fun cell(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> DbValues.cell(c.getDouble(i))
        Cursor.FIELD_TYPE_STRING -> DbValues.cell(c.getString(i))
        else -> DbValues.cell(c.getBlob(i))
    }

    private fun strings(db: SQLiteDatabase, sql: String, args: List<Any?> = emptyList()) =
        cursor(db, sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // --- schema and rows ----------------------------------------------------------------

    private fun schema(db: SQLiteDatabase, t: Target): JSONObject {
        val objects = cursor(db, "SELECT type, name, sql FROM sqlite_master WHERE type IN ('table', 'view') ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
        }
        val tables = objects.map { (type, name, sql) ->
            val virtual = sql.orEmpty().trimStart().startsWith("CREATE VIRTUAL", ignoreCase = true)
            val o = JSONObject().put("name", name).put("type", if (virtual) "virtual" else type).put("sql", sql ?: JSONObject.NULL)
                .put("internal", name.startsWith("sqlite_"))
            try {
                o.put("columns", columns(db, name)).put("indexes", indexes(db, name)).put("foreignKeys", foreignKeys(db, name))
            } catch (e: SQLiteException) {
                // A virtual table whose module this SQLite lacks, for instance.
                o.put("error", e.message?.substringBefore(" (code"))
            }
        }
        // Counts last and under one time limit: whatever isn't counted in time stays null.
        timed { signal ->
            for (o in tables) {
                if (o.has("error")) { o.put("rows", JSONObject.NULL); continue }
                val n = try {
                    cursor(db, Sql.count(o.getString("name"), COUNT_CAP), signal = signal).use { c -> c.moveToFirst(); c.getLong(0) }
                } catch (_: OperationCanceledException) { null } catch (_: SQLiteException) { null }
                o.put("rows", n ?: JSONObject.NULL).put("rowsCapped", n != null && n > COUNT_CAP)
            }
        }
        val relations = tables.flatMap { tbl ->
            val fks = tbl.optJSONArray("foreignKeys") ?: JSONArray()
            (0 until fks.length()).map { i ->
                val fk = fks.getJSONObject(i)
                JSONObject().put("from", tbl.getString("name")).put("fromColumns", fk.get("from"))
                    .put("to", fk.get("table")).put("toColumns", fk.get("to"))
            }
        }
        val triggers = strings(db, "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
        return JSONObject().put("file", describe(t.root, t.rel, t.file, t.header, owners()))
            .put("sqlite", strings(db, "SELECT sqlite_version()").first())
            .put("journal", strings(db, "PRAGMA journal_mode").firstOrNull() ?: JSONObject.NULL)
            .put("userVersion", strings(db, "PRAGMA user_version").firstOrNull()?.toLongOrNull() ?: 0)
            .put("tables", jsonArray(tables)).put("relations", jsonArray(relations)).put("triggers", jsonArray(triggers))
    }

    private fun columns(db: SQLiteDatabase, table: String) = jsonArray(cursor(db, "PRAGMA table_info(${Sql.quote(table)})").use { c ->
        buildList {
            while (c.moveToNext()) add(
                JSONObject().put("name", c.getString(1)).put("type", c.getString(2).orEmpty())
                    .put("notnull", c.getInt(3) != 0).put("default", c.getString(4) ?: JSONObject.NULL).put("pk", c.getInt(5))
            )
        }
    })

    private fun indexes(db: SQLiteDatabase, table: String) = jsonArray(cursor(db, "PRAGMA index_list(${Sql.quote(table)})").use { c ->
        buildList {
            while (c.moveToNext()) {
                val name = c.getString(1)
                val cols = cursor(db, "PRAGMA index_info(${Sql.quote(name)})").use { ic ->
                    buildList { while (ic.moveToNext()) add(ic.getString(2) ?: "(expression)") }
                }
                add(JSONObject().put("name", name).put("unique", c.getInt(2) != 0)
                    .put("origin", if (c.columnCount > 3) c.getString(3) else "c")
                    .put("partial", c.columnCount > 4 && c.getInt(4) != 0).put("columns", jsonArray(cols)))
            }
        }
    })

    /** Grouped by id: a composite key is one entry with several columns. */
    private fun foreignKeys(db: SQLiteDatabase, table: String) = jsonArray(cursor(db, "PRAGMA foreign_key_list(${Sql.quote(table)})").use { c ->
        val byId = LinkedHashMap<Int, JSONObject>()
        while (c.moveToNext()) {
            val fk = byId.getOrPut(c.getInt(0)) {
                JSONObject().put("table", c.getString(2)).put("from", JSONArray()).put("to", JSONArray())
                    .put("onUpdate", c.getString(5)).put("onDelete", c.getString(6))
            }
            fk.getJSONArray("from").put(c.getString(3))
            // A missing target column means the referenced table's primary key.
            fk.getJSONArray("to").put(c.getString(4) ?: JSONObject.NULL)
        }
        byId.values
    })

    private fun rows(db: SQLiteDatabase, q: Map<String, String>): JSONObject {
        val table = q["table"].orEmpty()
        require(strings(db, "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?", listOf(table)).isNotEmpty()) { "No table $table" }
        val cols = cursor(db, "PRAGMA table_info(${Sql.quote(table)})").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1) to c.getString(2).orEmpty()) }
        }
        val limit = (q["limit"]?.toIntOrNull() ?: 100).coerceIn(1, MAX_PAGE)
        val offset = (q["offset"]?.toLongOrNull() ?: 0).coerceAtLeast(0)
        val order = q["order"]?.ifEmpty { null }
        val sql = Sql.browse(table, cols.map { it.first }, order, q["dir"].equals("desc", true), limit, offset)
        return timed { signal ->
            val rows = cursor(db, sql, signal = signal).use { c ->
                buildList {
                    while (c.moveToNext()) add(jsonArray(cols.indices.map { i ->
                        val size = if (c.isNull(2 * i + 1)) null else c.getLong(2 * i + 1)
                        when (c.getType(2 * i)) {
                            Cursor.FIELD_TYPE_STRING -> DbValues.cell(c.getString(2 * i), size)
                            Cursor.FIELD_TYPE_BLOB -> DbValues.cell(c.getBlob(2 * i), size)
                            else -> cell(c, 2 * i)
                        }
                    }))
                }
            }
            val total = try {
                cursor(db, Sql.count(table, COUNT_CAP), signal = signal).use { c -> c.moveToFirst(); c.getLong(0) }
            } catch (_: OperationCanceledException) { null }
            JSONObject().put("table", table).put("columns", jsonArray(cols.map { it.first })).put("types", jsonArray(cols.map { it.second }))
                .put("offset", offset).put("limit", limit).put("total", total ?: JSONObject.NULL)
                .put("totalCapped", total != null && total > COUNT_CAP).put("rows", jsonArray(rows))
        }
    }

    // --- SQL console ---------------------------------------------------------------------

    private fun query(body: JSONObject): Response {
        val t = target(mapOf("root" to body.optString("root"), "path" to body.optString("path")))
        val stmt = Sql.single(body.optString("sql"))
        val args = DbValues.params(body.optJSONArray("params"))
        val read = Sql.isRead(stmt)
        val write = body.optBoolean("write")
        if (!write) require(read) { "Only SELECT, WITH, PRAGMA and EXPLAIN run read-only; turn on writing to change the database" }
        if (write) {
            require(body.optBoolean("confirm")) { "Writing changes the database: confirm it first" }
            DbFiles.owner(t.rel, owners()[t.root.id].orEmpty())?.takeIf(::running)?.let {
                return Response.error(409, "${it.name} is running and uses this database; stop it before writing to it")
            }
            require(t.file.canWrite()) { "${t.file.name} can't be written" }
            listed = null
        }
        val started = System.nanoTime()
        val out = open(t, write) { db ->
            if (!read) {
                val changed = db.compileStatement(stmt).use { st -> bind(st, args); st.executeUpdateDelete() }
                return@open JSONObject().put("columns", JSONArray()).put("rows", JSONArray()).put("rowsAffected", changed).put("truncated", false)
            }
            timed { signal ->
                cursor(db, Sql.capped(stmt, ROW_CAP), args, signal).use { c ->
                    val rows = JSONArray()
                    while (rows.length() < ROW_CAP && c.moveToNext()) rows.put(jsonArray((0 until c.columnCount).map { cell(c, it) }))
                    JSONObject().put("columns", jsonArray(c.columnNames.toList())).put("rows", rows)
                        .put("rowsAffected", JSONObject.NULL).put("truncated", c.moveToNext())
                }
            }
        }
        return Response.json(out.put("ms", (System.nanoTime() - started) / 1_000_000))
    }

    // --- JSON documents ------------------------------------------------------------------

    private var docs: SQLiteDatabase? = null
    private var json1 = true

    @Synchronized
    private fun docsDb(): SQLiteDatabase = docs ?: SQLiteDatabase.openDatabase(
        File(docsDir.apply { mkdirs() }, "docs.sqlite").path, null,
        SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS, KEEP_FILES,
    ).also { db ->
        // Not every phone's SQLite has the JSON functions; without them filters run here instead.
        json1 = try { strings(db, "SELECT json_extract('{\"a\":1}', '$.a')"); true } catch (_: SQLiteException) { false }
        docs = db
    }

    private fun exists(db: SQLiteDatabase, col: String) =
        strings(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", listOf(Docs.PREFIX + col)).isNotEmpty()

    private fun collections(): JSONArray {
        val db = docsDb()
        val names = strings(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND substr(name, 1, ${Docs.PREFIX.length}) = ? ORDER BY name", listOf(Docs.PREFIX))
        return jsonArray(names.map { it.removePrefix(Docs.PREFIX) }.map { col ->
            JSONObject().put("name", col).put("count", strings(db, "SELECT count(*) FROM ${Docs.table(col)}").first().toLong())
        })
    }

    private fun findDocs(col: String, q: Map<String, String>): Response {
        val table = Docs.table(col)
        val db = docsDb()
        if (!exists(db, col)) return Response.error(404, "No collection $col")
        val filter = q["q"]?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        val limit = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, MAX_PAGE)
        val offset = (q["offset"]?.toLongOrNull() ?: 0).coerceAtLeast(0)
        fun doc(c: Cursor) = Docs.output(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))
        val (items, total) = if (json1) {
            val (where, args) = Docs.where(filter)
            val total = strings(db, "SELECT count(*) FROM $table$where", args).first().toLong()
            cursor(db, "SELECT id, data, created, updated FROM $table$where ORDER BY created, id LIMIT $limit OFFSET $offset", args).use { c ->
                buildList { while (c.moveToNext()) add(doc(c)) }
            } to total
        } else {
            val all = cursor(db, "SELECT id, data, created, updated FROM $table ORDER BY created, id").use { c ->
                buildList { while (c.moveToNext()) doc(c).takeIf { Docs.matches(it, filter) }?.let(::add) }
            }
            all.drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(limit) to all.size.toLong()
        }
        return Response.json(JSONObject().put("collection", col).put("total", total).put("offset", offset).put("limit", limit).put("items", jsonArray(items)))
    }

    private fun getDoc(col: String, id: String): JSONObject? {
        val db = docsDb()
        if (!exists(db, col)) return null
        return cursor(db, "SELECT id, data, created, updated FROM ${Docs.table(col)} WHERE id = ?", listOf(id)).use { c ->
            if (c.moveToFirst()) Docs.output(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)) else null
        }
    }

    private fun insertDoc(col: String, body: JSONObject): Response {
        val db = docsDb()
        db.execSQL(Docs.create(col))
        val id = body.optString("_id").ifEmpty { UUID.randomUUID().toString().replace("-", "") }.let(Docs::id)
        val now = System.currentTimeMillis()
        val data = Docs.clean(body).toString()
        try {
            db.compileStatement("INSERT INTO ${Docs.table(col)} (id, data, created, updated) VALUES (?, ?, ?, ?)").use { st ->
                bind(st, listOf(id, data, now, now)); st.executeInsert()
            }
        } catch (_: SQLiteConstraintException) {
            return Response.error(409, "A document $id already exists in $col")
        }
        return Response.json(Docs.output(id, data, now, now), 201)
    }

    private fun replaceDoc(col: String, id: String, body: JSONObject): Response {
        val db = docsDb()
        if (!exists(db, col)) return Response.error(404, "No collection $col")
        val now = System.currentTimeMillis()
        val data = Docs.clean(body).toString()
        val changed = db.compileStatement("UPDATE ${Docs.table(col)} SET data = ?, updated = ? WHERE id = ?").use { st ->
            bind(st, listOf(data, now, id)); st.executeUpdateDelete()
        }
        return if (changed == 0) Response.error(404, "No document $id") else Response.json(getDoc(col, id)!!)
    }

    private fun deleteDoc(col: String, id: String): Response {
        val db = docsDb()
        if (!exists(db, col)) return Response.error(404, "No collection $col")
        val changed = db.compileStatement("DELETE FROM ${Docs.table(col)} WHERE id = ?").use { st -> bind(st, listOf(id)); st.executeUpdateDelete() }
        return if (changed == 0) Response.error(404, "No document $id") else Response.ok()
    }

    private fun dropCollection(col: String): Response {
        val db = docsDb()
        if (!exists(db, col)) return Response.error(404, "No collection $col")
        db.execSQL("DROP TABLE ${Docs.table(col)}")
        return Response.ok()
    }

    companion object {
        private const val TIMEOUT_S = 10L
        private const val ROW_CAP = 1000
        private const val COUNT_CAP = 100_000
        private const val MAX_PAGE = 1000
        private const val MAX_FILES = 300
        private const val MAX_DIRS = 5000
        private const val SCAN_MS = 8000L
        private const val CACHE_MS = 60_000L
        private val KEEP_FILES = DatabaseErrorHandler { }
    }
}
