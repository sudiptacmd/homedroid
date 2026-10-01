package dev.homedroid

import org.json.JSONArray
import org.json.JSONObject

/** A cloud AI service the user connected: OpenAI, Google Gemini, or any OpenAI-compatible API. */
class Provider(
    val id: String,
    /** "openai", "gemini" or "compatible". */
    val type: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    /** Chat models it offers, as last listed. */
    val models: List<String>,
) {
    /** For the dashboard: never the key itself. */
    fun toPublic(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("baseUrl", baseUrl)
        .put("hasKey", apiKey.isNotEmpty()).put("models", JSONArray(models))

    fun toStored(): JSONObject = toPublic().put("apiKey", apiKey).apply { remove("hasKey") }

    fun withModels(m: List<String>) = Provider(id, type, name, baseUrl, apiKey, m)

    companion object {
        fun from(o: JSONObject) = Provider(
            o.getString("id"), o.getString("type"), o.optString("name"), o.getString("baseUrl"), o.optString("apiKey"),
            o.optJSONArray("models")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
        )
    }
}

/** A model the dashboard offers to download and run on the phone. */
class LocalModel(val id: String, val name: String, val url: String, val bytes: Long, val sha256: String, val note: String) {
    val file get() = url.substringAfterLast('/')

    /** Rough memory needed to run it with a 4k context: weights plus cache and runtime. */
    val ramMb get() = bytes / 1048576 * 13 / 10 + 400
}

/**
 * Where an AI request goes. Model ids carry their origin: "local/<name>" runs on this phone
 * (llama.cpp's server on [LOCAL_PORT]), "<provider id>/<model>" goes to that cloud service.
 * Every service speaks the OpenAI chat API, Gemini through Google's compatibility endpoint.
 */
object AiCore {
    const val PORT = 8090
    const val LOCAL_PORT = 8091

    private const val HF = "https://huggingface.co"
    val CATALOG = listOf(
        LocalModel("qwen2.5-0.5b", "Qwen2.5 0.5B Instruct", "$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491400032, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", "Fastest; fine for short summaries"),
        LocalModel("qwen2.5-1.5b", "Qwen2.5 1.5B Instruct", "$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1117320736, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", "Good balance for most phones"),
        LocalModel("qwen2.5-3b", "Qwen2.5 3B Instruct", "$HF/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            2104932768, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d", "Better answers; needs 6 GB of RAM or more"),
        LocalModel("llama3.2-1b", "Llama 3.2 1B Instruct", "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            807694464, "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", "Small and quick"),
        LocalModel("llama3.2-3b", "Llama 3.2 3B Instruct", "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            2019377696, "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff", "Good writing; needs 6 GB of RAM or more"),
        LocalModel("gemma3-1b", "Gemma 3 1B", "$HF/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            806058240, "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135", "Small, multilingual"),
        LocalModel("gemma3-4b", "Gemma 3 4B", "$HF/ggml-org/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            2489757856, "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863", "Best quality here; needs 8 GB of RAM"),
    )

    /** The API root for a provider [type]; only "compatible" uses the address the user gave. */
    fun baseUrl(type: String, given: String): String = when (type) {
        "openai" -> "https://api.openai.com/v1"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai"
        else -> given.trim().trimEnd('/')
    }

    fun validBaseUrl(s: String) = Regex("""https?://[A-Za-z0-9.\-\[\]:]+(:\d{1,5})?(/[A-Za-z0-9._~\-/]*)?""").matches(s)

    /** A short id for a provider from its name, unique among [taken]. */
    fun slug(name: String, taken: Collection<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "ai" }
        if (base != "local" && base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }

    /** Chat models from a provider's /models list; OpenAI also lists speech, image and embedding models. */
    fun chatModels(type: String, ids: List<String>): List<String> {
        val clean = ids.map { it.removePrefix("models/") }.distinct()
        return when (type) {
            "openai" -> clean.filter { Regex("^(gpt-|o\\d|chatgpt-)").containsMatchIn(it) &&
                !Regex("audio|realtime|tts|transcribe|image|embedding|search|instruct|moderation").containsMatchIn(it) }
            "gemini" -> clean.filter { it.startsWith("gemini") && !Regex("embedding|image|tts|live|aqa").containsMatchIn(it) }
            else -> clean
        }.sorted()
    }

    class Target(
        /** The chat completions URL. */
        val url: String,
        val key: String,
        /** The model name the service knows. */
        val model: String,
        val local: Boolean,
        /** "On this phone" or the service's name, for labels and logs. */
        val label: String,
        /** Why a fallback was used, if it was. */
        val note: String? = null,
        /** The full id that was used. */
        val id: String = "",
    )

    /**
     * Picks where [model] runs. A local model that isn't running, or a phone that is too [hot],
     * uses [fallback] when one is set. Throws IllegalArgumentException for an unknown model and
     * IllegalStateException when nothing can answer.
     */
    fun resolve(model: String, providers: List<Provider>, localAlias: String?, localUp: Boolean, hot: Boolean, fallback: String): Target {
        fun pick(m: String): Target? {
            if (m.startsWith("local/")) {
                if (localAlias == null || m != "local/$localAlias") return null
                return Target("http://127.0.0.1:$LOCAL_PORT/v1/chat/completions", "", localAlias, true, "On this phone", id = m)
            }
            val p = providers.firstOrNull { m.startsWith(it.id + "/") && m.length > it.id.length + 1 } ?: return null
            return Target("${p.baseUrl}/chat/completions", p.apiKey, m.removePrefix(p.id + "/"), false, p.name, id = m)
        }
        val t = pick(model) ?: throw IllegalArgumentException("Unknown model $model")
        if (!t.local || localUp && !hot) return t
        val fb = fallback.takeIf { it.isNotEmpty() && it != model }?.let(::pick)?.takeIf { !it.local }
        if (fb != null) {
            val why = if (!localUp) "the on-phone model isn't running" else "the phone is too hot"
            return Target(fb.url, fb.key, fb.model, false, fb.label, "Used ${fb.label} because $why", fb.id)
        }
        if (!localUp) throw IllegalStateException("The on-phone model isn't running yet. Pick a model in AI → On this phone, or choose a cloud model.")
        return t
    }

    /** The text of a non-streaming chat completion. */
    fun content(response: JSONObject): String =
        response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()

    /** A provider's error message from its JSON body, in either common shape. */
    fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: o.optString("error").ifEmpty { body.take(300) }
    } catch (_: Exception) { body.take(300) }
}
