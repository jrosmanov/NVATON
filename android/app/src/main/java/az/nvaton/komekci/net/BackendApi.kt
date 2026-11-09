package az.nvaton.komekci.net

import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import az.nvaton.komekci.core.AlarmSpec
import az.nvaton.komekci.alarms.Alarm
import az.nvaton.komekci.core.I18n
import android.util.Base64
import az.nvaton.komekci.core.FamilyContact
import az.nvaton.komekci.core.ServerIntent
import az.nvaton.komekci.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [ai]: the server has a working AI key; [provider]: "gemini" or "claude". */
data class Health(val ai: Boolean, val provider: String, val tts: Boolean, val stt: Boolean)

data class VisionResult(
    val readable: Boolean,
    val kind: String,
    val title: String,
    val speech: String,
    val details: List<Pair<String, String>>,
)


/** Non-2xx answer from the backend: 503 = no AI key, 422 = refused, 401 = wrong app token. */
class ApiException(val code: Int, message: String) : IOException(message)

/** Talks to our own backend (never to the AI provider directly: API keys stay on the server). */
class BackendApi(private val settings: AppSettings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val wavType = "audio/wav".toMediaType()

    private fun request(path: String, body: JSONObject? = null): Request =
        request(path, body?.toString()?.toRequestBody(jsonType))

    private fun request(path: String, body: RequestBody?): Request {
        val builder = Request.Builder().url(settings.serverUrl.trimEnd('/') + path)
        settings.appToken.takeIf { it.isNotBlank() }?.let { builder.header("X-App-Token", it) }
        if (body != null) builder.post(body)
        return builder.build()
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        body.put("language", I18n.lang.code) // what the user speaks and what the answer must be in
        client.newCall(request(path, body)).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, text.take(300))
            JSONObject(text)
        }
    }

    /** null when the server can't be reached. */
    suspend fun health(): Health? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request("/health")).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val o = JSONObject(res.body?.string().orEmpty())
                Health(
                    ai = o.optBoolean("ai"),
                    provider = o.optString("provider"),
                    tts = o.optBoolean("tts"),
                    stt = o.optBoolean("stt"),
                )
            }
        }.getOrNull()
    }

    /**
     * [alarms] lets "delete the 7 o'clock alarm" pick one, [now] resolves "tomorrow", [context] is what the assistant
     * just read aloud (a screen or messages) so follow-up questions make sense.
     */
    suspend fun intent(
        text: String,
        contacts: List<FamilyContact>,
        assistantName: String,
        alarms: List<Alarm> = emptyList(),
        context: String? = null,
    ): ServerIntent {
        val contactsJson = JSONArray()
        contacts.forEach { c ->
            contactsJson.put(JSONObject().put("id", c.id).put("name", c.name).put("aliases", JSONArray(c.aliases)))
        }
        val alarmsJson = JSONArray()
        alarms.take(30).forEach { a ->
            alarmsJson.put(
                JSONObject().put("id", a.id).put("time", "%02d:%02d".format(a.hour, a.minute)).put("label", a.label.take(100))
                    .put("repeat_daily", a.repeatDaily),
            )
        }
        val now = ZonedDateTime.now()
        val body = JSONObject().put("text", text).put("assistant_name", assistantName).put("contacts", contactsJson)
            .put("alarms", alarmsJson)
            .put("now", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm, EEEE", Locale.ENGLISH)))
        context?.let { body.put("context", it.take(6000)) }
        val o = postJson("/api/intent", body)
        val ids = o.optJSONArray("contact_ids") ?: JSONArray()
        val alarmIds = o.optJSONArray("alarm_ids") ?: JSONArray()
        return ServerIntent(
            intent = o.optString("intent", "unknown"),
            contactIds = List(ids.length()) { ids.getString(it) },
            messageText = o.optString("message_text"),
            videoCall = o.optBoolean("video_call"),
            reply = o.optString("reply"),
            language = o.optString("language"),
            alarm = AlarmSpec(
                hour = o.optInt("alarm_hour", -1),
                minute = o.optInt("alarm_minute", 0),
                inMinutes = o.optInt("alarm_in_minutes", 0),
                dayOffset = o.optInt("alarm_day_offset", 0),
                repeatDaily = o.optBoolean("alarm_repeat_daily"),
                label = o.optString("alarm_label"),
            ),
            alarmIds = List(alarmIds.length()) { alarmIds.getString(it) },
            recipientName = o.optString("recipient_name"),
            phoneNumber = o.optString("phone_number"),
            contactAlias = o.optString("contact_alias"),
        )
    }

    /** [history]: earlier (question, answer) pairs on the same camera screen, oldest first. */
    suspend fun vision(jpeg: ByteArray, question: String?, history: List<Pair<String, String>> = emptyList()): VisionResult {
        val body = JSONObject()
            .put("image_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            .put("media_type", "image/jpeg")
        if (!question.isNullOrBlank()) body.put("question", question.take(500))
        if (history.isNotEmpty()) {
            val turns = JSONArray()
            history.takeLast(6).forEach { (q, a) -> turns.put(JSONObject().put("question", q.take(500)).put("answer", a.take(1500))) }
            body.put("history", turns)
        }
        val o = postJson("/api/vision", body)
        val details = o.optJSONArray("details") ?: JSONArray()
        return VisionResult(
            readable = o.optBoolean("readable"),
            kind = o.optString("kind"),
            title = o.optString("title"),
            speech = o.optString("speech"),
            details = List(details.length()) { i ->
                val d = details.getJSONObject(i)
                d.optString("label") to d.optString("value")
            },
        )
    }

    /** A spoken reading of the screen text [ScreenReader] collected. */
    suspend fun screen(text: String, app: String, question: String?): String {
        val body = JSONObject().put("text", text.take(6000)).put("app", app.take(100))
        question?.let { body.put("question", it.take(500)) }
        return postJson("/api/screen", body).optString("speech")
    }

    /** Recognised text for a 16 kHz mono WAV (Gemini or Azure on the server); "" when nothing was understood. */
    suspend fun stt(wav: ByteArray): String = withContext(Dispatchers.IO) {
        client.newCall(request("/api/stt?lang=${I18n.lang.code}", wav.toRequestBody(wavType))).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(res.code, text.take(300))
            JSONObject(text).optString("text")
        }
    }

    /** MP3 from Azure through the backend, or null when the server has no TTS configured. */
    suspend fun tts(text: String): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(request("/api/tts", JSONObject().put("text", text.take(1500)))).execute().use { res ->
            when {
                res.code == 501 -> null
                !res.isSuccessful -> throw ApiException(res.code, "tts failed")
                else -> res.body?.bytes()
            }
        }
    }
}
