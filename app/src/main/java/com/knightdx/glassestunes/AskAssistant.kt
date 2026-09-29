package com.knightdx.glassestunes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Answers questions with Google's Gemini, grounded in Google Search, and
 * phrases the answer for speaking in the glasses.
 *
 * Needs a Gemini API key (free from aistudio.google.com). On Google's free tier,
 * only the Gemini 2.5 models include Google Search, and Google may keep those
 * from new keys. So we try, in order, until one works (and remember it):
 * 2.5 Flash + Search, 3.8 Flash + Search (paid keys), 3.8 Flash without Search.
 */
class AskAssistant(private val context: Context) {

    data class Turn(val question: String, val answer: String)

    sealed class Answer {
        data class Spoken(val text: String, val searched: Boolean) : Answer()
        data class Failed(val reason: String) : Answer()
    }

    private data class Route(val model: String, val search: Boolean)

    private val routes = listOf(
        Route("gemini-2.5-flash", search = true),
        Route("gemini-3.8-flash", search = true),
        Route("gemini-3.8-flash", search = false),
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun ask(question: String, history: List<Turn>, done: (Answer) -> Unit) {
        val key = Prefs.geminiKey(context)
        if (key.isBlank()) {
            done(Answer.Failed("To ask questions, add a Gemini key in the Glasses Tunes app"))
            return
        }
        io.execute {
            val answer = try {
                askWithFallback(key, question, history)
            } catch (e: Exception) {
                Log.w(TAG, "question failed", e)
                Answer.Failed("I couldn't reach Gemini")
            }
            main.post { done(answer) }
        }
    }

    fun shutdown() = io.shutdownNow()

    private fun askWithFallback(key: String, question: String, history: List<Turn>): Answer {
        val remembered = Prefs.geminiRoute(context)
        val order = routes.sortedByDescending { "${it.model}|${it.search}" == remembered }
        var lastProblem = "Gemini didn't answer"
        for (route in order) {
            when (val r = call(key, route, question, history)) {
                is Result.Ok -> {
                    Prefs.setGeminiRoute(context, "${route.model}|${route.search}")
                    return Answer.Spoken(r.text, route.search)
                }
                is Result.Fatal -> return Answer.Failed(r.reason)
                is Result.TryNext -> lastProblem = r.reason
            }
        }
        return Answer.Failed(lastProblem)
    }

    private sealed class Result {
        data class Ok(val text: String) : Result()
        /** This model/search combination isn't available to this key; try another. */
        data class TryNext(val reason: String) : Result()
        data class Fatal(val reason: String) : Result()
    }

    private fun call(key: String, route: Route, question: String, history: List<Turn>): Result {
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions()))))
            val contents = JSONArray()
            history.takeLast(3).forEach { turn ->
                contents.put(message("user", turn.question))
                contents.put(message("model", turn.answer))
            }
            contents.put(message("user", question))
            put("contents", contents)
            if (route.search) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            put("generationConfig", JSONObject().put("maxOutputTokens", 400).put("temperature", 0.4))
        }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${route.model}:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                val answer = extractText(text) ?: return Result.TryNext("Gemini gave an empty answer")
                return Result.Ok(SpokenText.clean(answer))
            }
            val message = try {
                JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
            } catch (e: Exception) {
                ""
            }
            Log.w(TAG, "${route.model} search=${route.search}: HTTP $code $message")
            return when {
                code == 400 && message.contains("API key", ignoreCase = true) ->
                    Result.Fatal("Your Gemini key isn't valid. Check it in the Glasses Tunes app.")
                code == 429 -> Result.TryNext("Gemini's free limit is used up for now. Try again later.")
                code == 400 || code == 403 || code == 404 -> Result.TryNext("Gemini isn't available with this key")
                else -> Result.TryNext("Gemini had a problem")
            }
        } catch (e: SocketTimeoutException) {
            return Result.Fatal("Gemini took too long to answer")
        } catch (e: IOException) {
            return Result.Fatal("I couldn't reach Gemini. Check your internet connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun message(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun instructions(): String {
        val today = SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(Date())
        val city = Prefs.homeCity(context)
        return buildString {
            append("You are Jarvis, a voice assistant speaking through the user's smart glasses. ")
            append("Your answer is read aloud, so reply in one to three short, natural sentences: ")
            append("no markdown, no lists, no links, no emojis, no citations. ")
            append("Use Google Search for anything current (news, scores, weather, prices, hours, people). ")
            append("If you truly need more information, ask one short question. ")
            append("It is $today. ")
            if (city.isNotBlank()) append("The user is in or near $city; use that for weather and local questions. ")
        }
    }

    private fun extractText(json: String): String? {
        val candidates = JSONObject(json).optJSONArray("candidates") ?: return null
        val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .joinToString(" ").trim()
        return text.ifBlank { null }
    }

    companion object {
        private const val TAG = "AskAssistant"
    }
}

/** Makes model output pleasant to hear. Pure, for unit tests. */
object SpokenText {
    fun clean(raw: String, maxChars: Int = 450): String {
        var t = raw
            .replace(Regex("\\[\\d+(,\\s*\\d+)*\\]"), "") // [1], [2, 3] citations
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`#>]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([.,!?;:])"), "$1") // left behind by removed citations/links
            .trim()
        if (t.length > maxChars) {
            // Stop at the last full sentence that fits.
            val cut = t.substring(0, maxChars)
            val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "))
            t = if (end > 40) cut.substring(0, end + 1) else cut.substringBeforeLast(' ') + "…"
        }
        return t
    }
}
