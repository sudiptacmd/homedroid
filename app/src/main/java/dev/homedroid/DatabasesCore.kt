package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger

/**
 * SQL text handling for the database browser, without Android so the JVM tests cover it.
 * The scanner knows SQLite's quoting ('text', "id", `id`, [id]) and comments (-- and /* */),
 * which is all that's needed to find statement boundaries and leading keywords.
 */
object Sql {
    class Token(val kind: Char, val text: String, val start: Int, val end: Int)

    /** Words ('w'), quoted strings or names ('q'), comments ('c') and single punctuation ('p'). */
    fun tokens(sql: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            val start = i
            when {
                c.isWhitespace() -> { i++; continue }
                c == '-' && sql.startsWith("--", i) -> {
                    i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                    out += Token('c', "", start, i)
                }
                c == '/' && sql.startsWith("/*", i) -> {
                    i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                    out += Token('c', "", start, i)
                }
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    i++
                    while (i < sql.length) {
                        if (sql[i] == close) {
                            // A doubled quote is an escaped one, except inside [brackets].
                            if (close != ']' && i + 1 < sql.length && sql[i + 1] == close) i += 2 else break
                        } else i++
                    }
                    i = minOf(i + 1, sql.length)
                    out += Token('q', sql.substring(start, i), start, i)
                }
                c.isLetterOrDigit() || c == '_' || c == '$' || c.code > 127 -> {
                    while (i < sql.length && sql[i].let { it.isLetterOrDigit() || it == '_' || it == '$' || it.code > 127 }) i++
                    out += Token('w', sql.substring(start, i), start, i)
                }
                else -> { i++; out += Token('p', c.toString(), start, i) }
            }
        }
        return out
    }

    /** [sql] with each comment replaced by a space; strings and names are left as they are. */
    fun stripComments(sql: String): String {
        val sb = StringBuilder()
        var last = 0
        for (t in tokens(sql)) if (t.kind == 'c') {
            sb.append(sql, last, t.start).append(' ')
            last = t.end
        }
        return sb.append(sql, last, sql.length).toString().trim()
    }

    /**
     * The one statement in [sql], without comments or a trailing semicolon. More than one is
     * refused, which also rules out CREATE TRIGGER bodies (BEGIN … ; … END).
     */
    fun single(sql: String): String {
        val toks = tokens(sql).filter { it.kind != 'c' }
        val semi = toks.indexOfFirst { it.kind == 'p' && it.text == ";" }
        if (semi >= 0) require(toks.drop(semi + 1).all { it.kind == 'p' && it.text == ";" }) { "Run one statement at a time" }
        val text = stripComments(if (semi >= 0) sql.substring(0, toks[semi].start) else sql)
        require(text.isNotEmpty()) { "Enter an SQL statement" }
        require(text.length <= 100_000) { "That statement is too long" }
        return text
    }

    private val READ_START = setOf("SELECT", "WITH", "PRAGMA", "EXPLAIN")
    /** What a WITH can lead into besides SELECT; none of them can be an unquoted name. */
    private val WRITES = setOf("INSERT", "UPDATE", "DELETE", "REPLACE")
    /** Pragmas that take an argument and only read. */
    private val PRAGMA_ARGS = setOf("table_info", "table_xinfo", "table_list", "index_list", "index_info", "index_xinfo",
        "foreign_key_list", "foreign_key_check", "integrity_check", "quick_check")
    /** Pragmas that do work even without an argument. */
    private val PRAGMA_ACTIONS = setOf("wal_checkpoint", "optimize", "incremental_vacuum", "shrink_memory", "writable_schema")

    /**
     * Whether a single statement (from [single]) only reads. The database is opened read-only
     * as well; this is the friendlier first check, with a message instead of an SQLite error.
     */
    fun isRead(stmt: String): Boolean {
        val toks = tokens(stmt).filter { it.kind != 'c' }
        val first = toks.firstOrNull()?.takeIf { it.kind == 'w' }?.text?.uppercase() ?: return false
        if (first !in READ_START) return false
        if (first == "EXPLAIN") return true
        if (first == "PRAGMA") {
            var name = toks.getOrNull(1)?.text.orEmpty()
            if (toks.getOrNull(2)?.text == ".") name = toks.getOrNull(3)?.text.orEmpty()
            name = name.lowercase()
            if (toks.any { it.kind == 'p' && it.text == "=" } || name in PRAGMA_ACTIONS) return false
            return toks.none { it.kind == 'p' && it.text == "(" } || name in PRAGMA_ARGS
        }
        // A WITH can lead into INSERT/UPDATE/DELETE. "replace" is also a function: only REPLACE INTO counts.
        return toks.withIndex().none { (i, t) ->
            t.kind == 'w' && t.text.uppercase().let { w ->
                w in WRITES && (w != "REPLACE" || toks.getOrNull(i + 1)?.text?.uppercase() == "INTO")
            }
        }
    }

    /** A SELECT or WITH statement limited to [cap] + 1 rows, so the caller can tell it was cut off. */
    fun capped(stmt: String, cap: Int): String {
        val first = tokens(stmt).firstOrNull { it.kind != 'c' }?.text?.uppercase()
        return if (first == "SELECT" || first == "WITH") "SELECT * FROM (\n$stmt\n) LIMIT ${cap + 1}" else stmt
    }

    fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * One page of [table] for the grid. Each column comes back as two: a value with long text
     * and blobs cut to a preview, then its full length.
     */
    fun browse(table: String, columns: List<String>, order: String?, desc: Boolean, limit: Int, offset: Long): String {
        require(columns.isNotEmpty()) { "That table has no columns" }
        require(order == null || order in columns) { "No column $order" }
        val select = columns.joinToString(", ") { c ->
            val q = quote(c)
            "CASE typeof($q) WHEN 'blob' THEN substr($q, 1, ${DbValues.BLOB_PREVIEW}) " +
                "WHEN 'text' THEN substr($q, 1, ${DbValues.TEXT_PREVIEW}) ELSE $q END, length($q)"
        }
        val sort = order?.let { " ORDER BY ${quote(it)} ${if (desc) "DESC" else "ASC"}" }.orEmpty()
        return "SELECT $select FROM ${quote(table)}$sort LIMIT $limit OFFSET $offset"
    }

    /** Counts at most [cap] + 1 rows: a full count of a huge table would take too long. */
    fun count(table: String, cap: Int) = "SELECT count(*) FROM (SELECT 1 FROM ${quote(table)} LIMIT ${cap + 1})"
}

/** Values between SQLite and JSON. */
object DbValues {
    const val TEXT_PREVIEW = 2000
    const val BLOB_PREVIEW = 32

    /**
     * A cell as JSON: null, a number or a string, or for blobs {"blob": size, "hex": first bytes}
     * and for text cut short {"text": start, "size": full length}. [size] is the full length when
     * [v] is already a preview.
     */
    fun cell(v: Any?, size: Long? = null): Any = when (v) {
        null -> JSONObject.NULL
        is Long, is Int -> v
        is Double -> if (v.isFinite()) v else v.toString()
        is ByteArray -> JSONObject().put("blob", size ?: v.size.toLong()).put("hex", hex(v, BLOB_PREVIEW))
        is String -> if (v.length > TEXT_PREVIEW || size != null && size > v.length) {
            JSONObject().put("text", v.take(TEXT_PREVIEW)).put("size", maxOf(size ?: 0, v.length.toLong()))
        } else v
        else -> v.toString()
    }

    fun hex(b: ByteArray, max: Int = b.size) = b.take(max).joinToString("") { "%02x".format(it) }

    /** A JSON query parameter as a value to bind: null, Long, Double, String or (from {"hex": …}) ByteArray. */
    fun param(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is Boolean -> if (v) 1L else 0L
        is Int, is Long, is Short, is Byte -> (v as Number).toLong()
        is BigInteger -> { require(v.bitLength() < 64) { "Number too large" }; v.toLong() }
        is Number -> v.toDouble()
        is String -> v
        is JSONObject -> {
            val h = v.optString("hex")
            require(v.length() == 1 && v.has("hex") && Regex("([0-9a-fA-F]{2})*").matches(h)) { "Parameters must be values or {\"hex\": …} for a blob" }
            ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        else -> throw IllegalArgumentException("Parameters must be strings, numbers, booleans, null or {\"hex\": …}")
    }

    fun params(a: JSONArray?): List<Any?> {
        if (a == null) return emptyList()
        require(a.length() <= 999) { "Too many parameters" }
        return (0 until a.length()).map { param(a.opt(it)) }
    }
}

/** The JSON document store: collections are tables of id, JSON text and timestamps. */
object Docs {
    private val NAME = Regex("[A-Za-z0-9_-]{1,64}")
    private val ID = Regex("[A-Za-z0-9_.:-]{1,128}")
    const val PREFIX = "docs_"
    val META = setOf("_id", "_created", "_updated")

    fun table(collection: String): String {
        require(NAME.matches(collection)) { "Collection names use letters, digits, - and _ (up to 64)" }
        return Sql.quote(PREFIX + collection)
    }

    fun id(s: String): String {
        require(ID.matches(s)) { "Ids use letters, digits and . _ : - (up to 128)" }
        return s
    }

    fun create(collection: String) = "CREATE TABLE IF NOT EXISTS ${table(collection)} " +
        "(id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)"

    /** The document's own fields, without the ones the store adds. */
    fun clean(o: JSONObject) = JSONObject().also { out -> o.keys().forEach { if (it !in META) out.put(it, o.get(it)) } }

    fun output(id: String, data: String, created: Long, updated: Long): JSONObject =
        JSONObject(data).put("_id", id).put("_created", created).put("_updated", updated)

    private fun path(key: String): String {
        require(key.isNotEmpty() && key.length <= 128 && key.none { it == '"' || it == '\\' || it.code < 32 }) { "Invalid filter field $key" }
        return "$.\"$key\""
    }

    /**
     * Equality on top-level fields as an SQL condition (json_extract/json_type) and its
     * parameters. The type is checked too, so "5" doesn't match 5 and 1 doesn't match true.
     */
    fun where(q: JSONObject): Pair<String, List<Any?>> {
        val parts = ArrayList<String>()
        val args = ArrayList<Any?>()
        for (key in q.keys().asSequence().sorted()) {
            val p = path(key)
            when (val v = q.get(key)) {
                JSONObject.NULL -> { parts += "json_type(data, ?) = 'null'"; args += p }
                is Boolean -> { parts += "json_type(data, ?) = ?"; args += p; args += v.toString() }
                is Number -> { parts += "json_type(data, ?) IN ('integer', 'real') AND json_extract(data, ?) = ?"; args += p; args += p; args += DbValues.param(v) }
                is String -> { parts += "json_type(data, ?) = 'text' AND json_extract(data, ?) = ?"; args += p; args += p; args += v }
                else -> throw IllegalArgumentException("Filter values must be strings, numbers, booleans or null")
            }
        }
        return (if (parts.isEmpty()) "" else " WHERE " + parts.joinToString(" AND ")) to args
    }

    /** The same test as [where], for SQLite builds without the JSON functions. */
    fun matches(doc: JSONObject, q: JSONObject): Boolean = q.keys().asSequence().all { key ->
        path(key)
        val want = q.get(key)
        val have = doc.opt(key)
        when (want) {
            JSONObject.NULL -> have == JSONObject.NULL
            is Boolean -> have == want
            is Number -> have is Number && have.toDouble() == want.toDouble()
            is String -> have == want
            else -> throw IllegalArgumentException("Filter values must be strings, numbers, booleans or null")
        }
    }
}

/** Finding and opening database files inside the file browser's locations. */
object DbFiles {
    const val MAX_DEPTH = 7
    private val EXTENSIONS = setOf("db", "sqlite", "sqlite3")
    private val SKIP = setOf("node_modules", ".git", "proc", "sys", "dev", "__pycache__", "site-packages", ".venv", "venv", ".cache", ".npm")
    /** System folders at the top of Alpine: packages, not data. */
    private val SKIP_ALPINE = setOf("usr", "lib", "bin", "sbin", "run", "tmp", "etc", "media")

    fun candidate(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun skip(name: String, depth: Int, root: String) =
        name in SKIP || depth > MAX_DEPTH || root == "alpine" && depth == 1 && name in SKIP_ALPINE

    /** The first 100 bytes of an SQLite file start with this. */
    fun isSqlite(header: ByteArray) = header.size >= 100 && String(header, 0, 16, Charsets.ISO_8859_1) == "SQLite format 3\u0000"

    /** File format versions 2/2 mean the database is in WAL mode. */
    fun isWal(header: ByteArray) = isSqlite(header) && header[18].toInt() == 2 && header[19].toInt() == 2

    /** [rel] inside [base], symlinks resolved; null if it leads outside or names [base] itself. */
    fun resolve(base: File, rel: String): File? {
        if (rel.isBlank() || '\u0000' in rel) return null
        val root = base.canonicalFile
        val f = File(root, rel.trimStart('/')).canonicalFile
        return f.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    /** The owner whose path prefix (relative to the root, "" for all of it) matches [path] most closely. */
    fun <T> owner(path: String, prefixes: List<Pair<String, T>>): T? = prefixes
        .filter { (p, _) -> p.isEmpty() || path == p || path.startsWith("$p/") }
        .maxByOrNull { it.first.length }?.second
}
