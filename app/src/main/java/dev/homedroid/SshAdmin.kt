package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Dashboard API for SSH access: the authorized keys, the session log sshd writes, and the
 * command history of the phone's shell and of Alpine's.
 */
class SshAdmin(private val paths: Paths, private val alpine: Alpine) {
    private class Key(val line: String, val type: String, val blob: ByteArray, val comment: String) {
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }

    private val alpineHistory get() = File(alpine.root, "root/.ash_history")

    fun handle(r: Request, seg: List<String>): Response = when {
        r.method == "GET" && seg == listOf("keys") -> Response.json(jsonArray(keys().map {
            JSONObject().put("type", it.type).put("fingerprint", it.fingerprint).put("name", it.comment)
        }))
        r.method == "POST" && seg == listOf("keys") -> addKey(r.json())
        r.method == "DELETE" && seg == listOf("keys") -> removeKey(r.query["fingerprint"].orEmpty())
        r.method == "GET" && seg == listOf("sessions") -> Response.json(JSONArray(
            tail(paths.sessionLog, 200).asReversed().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        ))
        r.method == "DELETE" && seg == listOf("sessions") -> { paths.sessionLog.delete(); Response.ok() }
        r.method == "GET" && seg == listOf("history") -> Response.json(
            JSONObject()
                .put("phone", jsonArray(tail(paths.shellHistory, 200).asReversed().map { line ->
                    val t = line.substringBefore(' ').toLongOrNull()
                    JSONObject().put("time", t ?: JSONObject.NULL).put("command", if (t != null) line.substringAfter(' ') else line)
                }))
                .put("alpine", jsonArray(tail(alpineHistory, 200).asReversed().map { JSONObject().put("command", it) }))
        )
        r.method == "DELETE" && seg == listOf("history") -> {
            paths.shellHistory.delete()
            alpineHistory.delete()
            Response.ok()
        }
        else -> Response.error(404, "no such endpoint")
    }

    private fun keys(): List<Key> = paths.authorizedKeys.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull(::parse)

    /** A key line: optional options, then "<type> <base64> [comment]". */
    private fun parse(line: String): Key? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val parts = t.split(Regex("\\s+"))
        val i = parts.indexOfFirst { it in TYPES }
        if (i < 0 || i + 1 >= parts.size) return null
        val blob = try {
            Base64.getDecoder().decode(parts[i + 1])
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Key(line, parts[i], blob, parts.drop(i + 2).joinToString(" "))
    }

    private fun addKey(o: JSONObject): Response {
        val text = o.optString("key").trim()
        val key = parse(text)?.takeIf { text.split(Regex("\\s+")).first() in TYPES }
            ?: return Response.error(400, "that isn't a public key: paste a line like ssh-ed25519 AAAA… you@laptop (from ~/.ssh/id_ed25519.pub)")
        if (keys().any { it.fingerprint == key.fingerprint }) return Response.error(409, "this key is already allowed")
        val name = o.optString("name").trim().replace(Regex("[\\r\\n]"), " ").ifEmpty { key.comment }
        val line = listOf(key.type, Base64.getEncoder().encodeToString(key.blob), name).filter { it.isNotEmpty() }.joinToString(" ")
        val current = paths.authorizedKeys.takeIf { it.exists() }?.readText().orEmpty()
        write(current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + line + "\n")
        return Response.json(JSONObject().put("fingerprint", key.fingerprint).put("name", name), 201)
    }

    private fun removeKey(fingerprint: String): Response {
        val lines = paths.authorizedKeys.takeIf { it.exists() }?.readLines().orEmpty()
        val kept = lines.filter { parse(it)?.fingerprint != fingerprint }
        if (kept.size == lines.size) return Response.error(404, "no such key")
        write(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
        return Response.ok()
    }

    /** sshd re-reads the file on every login, so a replaced file takes effect at once. */
    private fun write(text: String) {
        paths.authorizedKeys.parentFile!!.mkdirs()
        val tmp = File(paths.authorizedKeys.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(paths.authorizedKeys)
    }

    private fun tail(f: File, n: Int): List<String> =
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }.takeLast(n)

    companion object {
        private val TYPES = setOf(
            "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com",
        )
    }
}
