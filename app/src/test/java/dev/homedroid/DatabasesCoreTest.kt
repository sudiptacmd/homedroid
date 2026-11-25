package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatabasesCoreTest {
    @Test fun commentsAreStrippedButStringsKept() {
        assertEquals("SELECT 1", Sql.stripComments("-- hi\nSELECT 1 /* trailing */"))
        assertEquals("SELECT '-- not a comment', \"/* nor */\"", Sql.stripComments("SELECT '-- not a comment', \"/* nor */\""))
        assertEquals("SELECT 'it''s'", Sql.stripComments("/* a */SELECT 'it''s'"))
    }

    @Test fun onlyOneStatementRuns() {
        assertEquals("SELECT 1", Sql.single("SELECT 1;"))
        assertEquals("SELECT 1", Sql.single("SELECT 1 ; -- done\n;"))
        assertEquals("SELECT ';' FROM [a;b]", Sql.single("SELECT ';' FROM [a;b]"))
        for (bad in listOf("SELECT 1; DROP TABLE t", "SELECT 1; /* x */ SELECT 2", "", "  -- only a comment", ";"))
            assertThrows(IllegalArgumentException::class.java) { Sql.single(bad) }
    }

    @Test fun readStatementsAreRecognised() {
        for (s in listOf("SELECT * FROM t", "select replace(a, 'x', 'y') from t", "WITH x AS (SELECT 1) SELECT * FROM x",
            "PRAGMA table_info(\"t\")", "PRAGMA main.index_list(t)", "PRAGMA user_version", "EXPLAIN QUERY PLAN DELETE FROM t",
            "SELECT 'DELETE FROM t'", "SELECT \"update\" FROM t"))
            assertTrue(s, Sql.isRead(Sql.single(s)))
        for (s in listOf("DELETE FROM t", "INSERT INTO t VALUES (1)", "WITH x AS (SELECT 1) DELETE FROM t",
            "WITH x AS (SELECT 1) REPLACE INTO t SELECT * FROM x", "PRAGMA journal_mode = DELETE", "PRAGMA user_version=3",
            "PRAGMA wal_checkpoint", "PRAGMA cache_size(10)", "ATTACH 'x.db' AS x", "VACUUM", "/* SELECT */ DROP TABLE t", "VALUES (1)"))
            assertFalse(s, Sql.isRead(Sql.single(s)))
    }

    @Test fun selectsAreCappedOthersNot() {
        assertEquals("SELECT * FROM (\nSELECT a FROM t\n) LIMIT 1001", Sql.capped("SELECT a FROM t", 1000))
        assertTrue(Sql.capped("WITH x AS (SELECT 1) SELECT * FROM x", 5).endsWith("LIMIT 6"))
        assertEquals("PRAGMA table_info(t)", Sql.capped("PRAGMA table_info(t)", 5))
    }

    @Test fun identifiersAreQuoted() {
        assertEquals("\"plain\"", Sql.quote("plain"))
        assertEquals("\"a\"\"; DROP TABLE x; --\"", Sql.quote("a\"; DROP TABLE x; --"))
        val sql = Sql.browse("my table", listOf("id", "da\"ta"), "id", true, 50, 100)
        assertTrue(sql.contains("FROM \"my table\" ORDER BY \"id\" DESC LIMIT 50 OFFSET 100"))
        assertTrue(sql.contains("length(\"da\"\"ta\")"))
        assertThrows(IllegalArgumentException::class.java) { Sql.browse("t", listOf("id"), "nope", false, 1, 0) }
        assertEquals("SELECT count(*) FROM (SELECT 1 FROM \"t\" LIMIT 11)", Sql.count("t", 10))
    }

    @Test fun valuesBecomeJson() {
        assertEquals(JSONObject.NULL, DbValues.cell(null))
        assertEquals(5L, DbValues.cell(5L))
        assertEquals(1.5, DbValues.cell(1.5))
        assertEquals("Infinity", DbValues.cell(Double.POSITIVE_INFINITY))
        assertEquals("short", DbValues.cell("short"))
        val long = DbValues.cell("x".repeat(3000)) as JSONObject
        assertEquals(DbValues.TEXT_PREVIEW, long.getString("text").length)
        assertEquals(3000, long.getLong("size"))
        assertEquals(9000, (DbValues.cell("abc", 9000) as JSONObject).getLong("size"))
        val blob = DbValues.cell(ByteArray(40) { it.toByte() }) as JSONObject
        assertEquals(40, blob.getLong("blob"))
        assertEquals(64, blob.getString("hex").length)
        assertTrue(blob.getString("hex").startsWith("000102"))
        assertEquals(1000L, (DbValues.cell(byteArrayOf(1), 1000) as JSONObject).getLong("blob"))
    }

    @Test fun paramsAreTyped() {
        val p = DbValues.params(JSONArray("[null, true, 7, 2.5, \"s\", {\"hex\": \"00ff\"}]"))
        assertNull(p[0])
        assertEquals(1L, p[1])
        assertEquals(7L, p[2])
        assertEquals(2.5, p[3])
        assertEquals("s", p[4])
        assertArrayEquals(byteArrayOf(0, -1), p[5] as ByteArray)
        for (bad in listOf("[[1]]", "[{\"hex\": \"zz\"}]", "[{\"a\": 1}]"))
            assertThrows(IllegalArgumentException::class.java) { DbValues.params(JSONArray(bad)) }
    }

    @Test fun docFiltersBindEverything() {
        val (where, args) = Docs.where(JSONObject("{\"name\": \"x' OR 1=1\", \"n\": 3, \"ok\": true, \"gone\": null}"))
        assertFalse(where.contains("OR 1=1"))
        assertEquals(" WHERE json_type(data, ?) = 'null' AND json_type(data, ?) IN ('integer', 'real') AND json_extract(data, ?) = ?" +
            " AND json_type(data, ?) = 'text' AND json_extract(data, ?) = ? AND json_type(data, ?) = ?", where)
        assertEquals(listOf("$.\"gone\"", "$.\"n\"", "$.\"n\"", 3L, "$.\"name\"", "$.\"name\"", "x' OR 1=1", "$.\"ok\"", "true"), args)
        assertEquals("" to emptyList<Any?>(), Docs.where(JSONObject()))
        for (bad in listOf("{\"a\": [1]}", "{\"a\": {}}", "{\"a\\\"b\": 1}"))
            assertThrows(IllegalArgumentException::class.java) { Docs.where(JSONObject(bad)) }
    }

    @Test fun docFiltersMatchInMemoryToo() {
        val doc = JSONObject("{\"a\": 5, \"b\": \"5\", \"c\": true, \"d\": null}")
        assertTrue(Docs.matches(doc, JSONObject("{\"a\": 5.0, \"b\": \"5\", \"c\": true, \"d\": null}")))
        assertFalse(Docs.matches(doc, JSONObject("{\"a\": \"5\"}")))
        assertFalse(Docs.matches(doc, JSONObject("{\"b\": 5}")))
        assertFalse(Docs.matches(doc, JSONObject("{\"c\": 1}")))
        assertFalse(Docs.matches(doc, JSONObject("{\"missing\": null}")))
    }

    @Test fun docNamesAndMetaFields() {
        assertEquals("\"docs_notes-2\"", Docs.table("notes-2"))
        for (bad in listOf("", "a b", "a\"b", "../x", "x".repeat(65))) assertThrows(IllegalArgumentException::class.java) { Docs.table(bad) }
        assertThrows(IllegalArgumentException::class.java) { Docs.id("a/b") }
        val clean = Docs.clean(JSONObject("{\"_id\": \"x\", \"_created\": 1, \"title\": \"t\"}"))
        assertEquals(setOf("title"), clean.keys().asSequence().toSet())
        val out = Docs.output("x", clean.toString(), 1, 2)
        assertEquals("x", out.getString("_id"))
        assertEquals(2, out.getLong("_updated"))
    }

    @Test fun pathsStayInsideTheirRoot() {
        val base = Files.createTempDirectory("dbroot").toFile()
        val outside = Files.createTempDirectory("dbout").toFile()
        try {
            File(base, "var/lib").mkdirs()
            val db = File(base, "var/lib/app.db").apply { writeText("") }
            assertEquals(db.canonicalFile, DbFiles.resolve(base, "var/lib/app.db"))
            assertEquals(db.canonicalFile, DbFiles.resolve(base, "/var/lib/../lib/app.db"))
            for (bad in listOf("", "../x.db", "var/../../x.db", ".", "a\u0000b")) assertNull(bad, DbFiles.resolve(base, bad))
            Files.createSymbolicLink(File(base, "out").toPath(), outside.toPath())
            assertNull(DbFiles.resolve(base, "out/x.db"))
        } finally {
            base.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun discoveryRulesAndHeaders() {
        assertTrue(DbFiles.candidate("home-assistant_v2.db"))
        assertTrue(DbFiles.candidate("x.SQLITE3"))
        assertFalse(DbFiles.candidate("x.db-wal"))
        assertTrue(DbFiles.skip("node_modules", 2, "home"))
        assertTrue(DbFiles.skip("usr", 1, "alpine"))
        assertFalse(DbFiles.skip("usr", 1, "home"))
        assertFalse(DbFiles.skip("var", 1, "alpine"))
        assertTrue(DbFiles.skip("deep", DbFiles.MAX_DEPTH + 1, "home"))
        val header = ByteArray(100).also { "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(it) }
        assertTrue(DbFiles.isSqlite(header))
        assertFalse(DbFiles.isWal(header))
        header[18] = 2; header[19] = 2
        assertTrue(DbFiles.isWal(header))
        assertFalse(DbFiles.isSqlite(ByteArray(100)))
        assertFalse(DbFiles.isSqlite(header.copyOf(50)))
    }

    @Test fun ownersMatchTheLongestPrefix() {
        val owners = listOf("var/lib/jellyfin" to "Jellyfin", "srv/blog" to "Blog", "var/lib" to "Other")
        assertEquals("Jellyfin", DbFiles.owner("var/lib/jellyfin/data/library.db", owners))
        assertEquals("Other", DbFiles.owner("var/lib/jellyfin2/x.db", owners))
        assertEquals("Blog", DbFiles.owner("srv/blog/prisma/dev.db", owners))
        assertNull(DbFiles.owner("root/x.db", owners))
        assertEquals("All", DbFiles.owner("anything.db", listOf("" to "All")))
    }
}
