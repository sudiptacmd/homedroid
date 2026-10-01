package dev.homedroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiCoreTest {
    private val openai = Provider("openai", "openai", "OpenAI", AiCore.baseUrl("openai", ""), "sk-1", listOf("gpt-4o-mini"))
    private val ollama = Provider("pc", "compatible", "PC", AiCore.baseUrl("compatible", "http://192.168.1.10:11434/v1/"), "", listOf("llama3.1:8b"))
    private val providers = listOf(openai, ollama)

    @Test fun modelIdsPickTheirService() {
        val local = AiCore.resolve("local/qwen", providers, "qwen", localUp = true, hot = false, fallback = "")
        assertTrue(local.local)
        assertEquals("http://127.0.0.1:8091/v1/chat/completions", local.url)
        val cloud = AiCore.resolve("openai/gpt-4o-mini", providers, "qwen", true, false, "")
        assertEquals("https://api.openai.com/v1/chat/completions", cloud.url)
        assertEquals("gpt-4o-mini", cloud.model)
        assertEquals("sk-1", cloud.key)
        // A model name may contain slashes and colons; only the first part names the service.
        val pc = AiCore.resolve("pc/llama3.1:8b", providers, null, false, false, "")
        assertEquals("http://192.168.1.10:11434/v1/chat/completions", pc.url)
        assertEquals("llama3.1:8b", pc.model)
    }

    @Test fun unknownModelsAndEmptyNamesAreRefused() {
        for (m in listOf("nope/x", "openai/", "local/other", "", "local/")) {
            assertThrows(m, IllegalArgumentException::class.java) { AiCore.resolve(m, providers, "qwen", true, false, "") }
        }
    }

    @Test fun localModelFallsBackToTheCloudWhenDownOrHot() {
        val down = AiCore.resolve("local/qwen", providers, "qwen", localUp = false, hot = false, fallback = "openai/gpt-4o-mini")
        assertFalse(down.local)
        assertTrue(down.note!!.contains("isn't running"))
        val hot = AiCore.resolve("local/qwen", providers, "qwen", localUp = true, hot = true, fallback = "openai/gpt-4o-mini")
        assertTrue(hot.note!!.contains("too hot"))
        // Hot without a fallback: still answers locally, just slowly.
        assertTrue(AiCore.resolve("local/qwen", providers, "qwen", true, true, "").local)
        assertThrows(IllegalStateException::class.java) { AiCore.resolve("local/qwen", providers, "qwen", false, false, "") }
        // A fallback is never another local model.
        assertThrows(IllegalStateException::class.java) { AiCore.resolve("local/qwen", providers, "qwen", false, false, "local/qwen") }
    }

    @Test fun onlyChatModelsAreListed() {
        val openaiIds = listOf("gpt-4o-mini", "gpt-4o-audio-preview", "text-embedding-3-small", "o3-mini", "dall-e-3", "whisper-1", "gpt-4o-realtime-preview", "tts-1")
        assertEquals(listOf("gpt-4o-mini", "o3-mini"), AiCore.chatModels("openai", openaiIds))
        val gemini = listOf("models/gemini-2.5-flash", "models/text-embedding-004", "models/gemini-2.0-flash-live-001", "models/imagen-3.0")
        assertEquals(listOf("gemini-2.5-flash"), AiCore.chatModels("gemini", gemini))
        assertEquals(listOf("a", "b"), AiCore.chatModels("compatible", listOf("b", "a", "a")))
    }

    @Test fun addressesAndIds() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", AiCore.baseUrl("gemini", "ignored"))
        assertTrue(AiCore.validBaseUrl("http://192.168.1.10:11434/v1"))
        assertTrue(AiCore.validBaseUrl("https://openrouter.ai/api/v1"))
        assertFalse(AiCore.validBaseUrl("file:///etc/passwd"))
        assertFalse(AiCore.validBaseUrl("http://x/v1?y=1"))
        assertFalse(AiCore.validBaseUrl("http://a b/"))
        assertEquals("my-pc", AiCore.slug("My PC!", emptyList()))
        assertEquals("openai-2", AiCore.slug("openai", listOf("openai")))
        assertEquals("local-2", AiCore.slug("Local", emptyList()))
    }

    @Test fun answersAndErrorsAreRead() {
        assertEquals("Hi", AiCore.content(JSONObject("""{"choices":[{"message":{"role":"assistant","content":" Hi "}}]}""")))
        assertEquals("bad key", AiCore.errorMessage("""{"error":{"message":"bad key","type":"auth"}}"""))
        assertEquals("nope", AiCore.errorMessage("""{"error":"nope"}"""))
        assertEquals("<html>", AiCore.errorMessage("<html>"))
    }

    @Test fun keysStayOutOfThePublicView() {
        val o = openai.toPublic()
        assertFalse(o.toString().contains("sk-1"))
        assertTrue(o.getBoolean("hasKey"))
        assertEquals("sk-1", Provider.from(openai.toStored()).apiKey)
    }

    @Test fun catalogFilesAreGgufWithPinnedHashes() {
        for (m in AiCore.CATALOG) {
            assertTrue(m.file.endsWith(".gguf"))
            assertTrue(m.url.startsWith("https://huggingface.co/"))
            assertTrue(Regex("[0-9a-f]{64}").matches(m.sha256))
        }
        assertEquals(AiCore.CATALOG.size, AiCore.CATALOG.map { it.id }.toSet().size)
    }
}
