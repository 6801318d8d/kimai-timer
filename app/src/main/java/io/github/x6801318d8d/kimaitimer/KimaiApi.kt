package io.github.x6801318d8d.kimaitimer

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * The few calls of Kimai's REST API the app needs, with an API token (Kimai: Profile → API access), plus the plugin
 * AppPushBundle's push endpoints and event stream. Blocking: call from a background thread.
 *
 * Kimai reads and writes times as local times of the user's timezone ([zone], from [user]).
 */
class KimaiApi(private val url: String, private val token: String, private val zone: ZoneId = ZoneId.systemDefault()) : Remote {

    /** Kimai answered with an error status. */
    class HttpException(val code: Int, message: String) : IOException(message)

    data class User(val name: String, val timezone: String)

    /** The user the token belongs to (alias or username) and their timezone. */
    fun user(): User {
        val json = JSONObject(request("GET", "/api/users/me"))
        return User(json.optString("alias").ifEmpty { json.optString("username") }, json.optString("timezone"))
    }

    /** Kimai answers within a few seconds: any status below 500, even a refused token, means it is up. */
    fun reachable(): Boolean = try {
        val connection = open("GET", "/api/ping")
        connection.connectTimeout = PROBE_TIMEOUT
        connection.readTimeout = PROBE_TIMEOUT
        connection.setRequestProperty("Connection", "close")
        try {
            connection.responseCode < 500
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        false
    }

    /** The running timers of the user, newest first. */
    override fun active(): List<Timer> = timers(request("GET", "/api/timesheets/active")).sortedByDescending { it.begin }

    /** The user's latest finished entries, newest first. */
    fun recent(size: Int): List<Timer> =
        timers(request("GET", "/api/timesheets?size=$size&full=true&active=0&orderBy=begin&order=DESC"))

    override fun timesheet(id: Int): Timer? = try {
        Timer.fromKimai(JSONObject(request("GET", "/api/timesheets/$id")))
    } catch (e: HttpException) {
        if (e.code != 404) throw e
        null
    }

    override fun create(start: Change.Start, end: Long?): Int {
        val body = JSONObject()
            .put("project", start.projectId)
            .put("activity", start.activityId)
            .put("begin", local(start.begin))
            .put("description", start.description)
        if (end != null) body.put("end", local(end))
        return entity(request("POST", "/api/timesheets", body)).getInt("id")
    }

    override fun restart(id: Int, begin: Long): Int =
        entity(request("PATCH", "/api/timesheets/$id/restart", JSONObject().put("begin", local(begin)))).getInt("id")

    override fun setEnd(id: Int, end: Long) {
        entity(request("PATCH", "/api/timesheets/$id", JSONObject().put("end", local(end))))
    }

    /** Same project and activity, a begin within [FIND_WINDOW] (rounding rules may move it), changed since [Change.Start.sentAt]. */
    override fun find(start: Change.Start): Int? {
        val query = listOf(
            "project=${start.projectId}",
            "activity=${start.activityId}",
            "begin=" + encode(local(start.begin - FIND_WINDOW)),
            "end=" + encode(local(start.begin + FIND_WINDOW)),
            // stored in UTC
            "modified_after=" + encode(DATE.format(Instant.ofEpochMilli(start.sentAt - FIND_WINDOW).atOffset(ZoneOffset.UTC))),
            "size=10",
        ).joinToString("&")
        return timers(request("GET", "/api/timesheets?$query")).minByOrNull { abs(it.begin - start.begin) }?.id
    }

    /** The visible projects and activities, for starting timers while Kimai cannot be reached. */
    fun catalog(): Catalog = Catalog.fromKimai(
        JSONArray(request("GET", "/api/projects?visible=1")),
        JSONArray(request("GET", "/api/activities?visible=1")),
    )

    fun registerEndpoint(endpoint: String, name: String) {
        request("POST", "/api/app-push/endpoints", JSONObject().put("endpoint", endpoint).put("name", name))
    }

    fun unregisterEndpoint(endpoint: String) {
        request("DELETE", "/api/app-push/endpoints?endpoint=" + encode(endpoint))
    }

    /**
     * Reads the plugin's event stream until the server ends it (after half an hour) or the connection breaks, calling
     * [onEvent] with each event name ("ready" first, then "change"). [onOpen] gets the connection, so that another
     * thread can close it.
     */
    fun stream(onOpen: (HttpURLConnection) -> Unit, onEvent: (String) -> Unit) {
        val connection = open("GET", "/api/app-push/events")
        // the server sends a keep-alive line every 50 seconds: two missing ones mean a dead connection
        connection.readTimeout = 130_000
        connection.setRequestProperty("Accept", "text/event-stream")
        onOpen(connection)
        try {
            check(connection)
            connection.inputStream.bufferedReader().use { reader ->
                var event = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (event.isNotEmpty()) onEvent(event)
                            event = ""
                        }
                        line.startsWith("event:") -> event = line.substring(6).trim()
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun local(epochMillis: Long): String = DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    private fun timers(json: String): List<Timer> {
        val array = JSONArray(json)
        return (0 until array.length()).map { Timer.fromKimai(array.getJSONObject(it)) }
    }

    /** An entry Kimai saved; a form with errors (Kimai may send it with status 200) becomes a 400. */
    private fun entity(json: String): JSONObject {
        val entity = JSONObject(json)
        if (!entity.has("id")) throw HttpException(400, "HTTP 400: " + entity.optString("message").ifEmpty { json.take(300) })
        return entity
    }

    private fun request(method: String, path: String, body: JSONObject? = null): String {
        val connection = open(method, path)
        // the app calls the API only now and then: a connection kept open for reuse would only let the server's TCP
        // keep-alive packets (Caddy: every 15 s) wake the phone until it closes
        connection.setRequestProperty("Connection", "close")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val connection = URL(url + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun check(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val error = runCatching { JSONObject(connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()) }.getOrNull()
            val message = error?.optString("message").orEmpty()
            val details = error?.optJSONObject("errors")?.let { formErrors(it) }.orEmpty().joinToString("; ")
            throw HttpException(
                code,
                "HTTP $code" + (if (message.isNotEmpty()) ": $message" else "") + (if (details.isNotEmpty()) " ($details)" else ""),
            )
        }
    }

    /** What a form Kimai refused says: {"errors": [...], "children": {"activity": {"errors": [...]}}} as "activity: ...". */
    private fun formErrors(form: JSONObject, name: String = ""): List<String> {
        val own = form.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            .map { if (name.isEmpty()) it else "$name: $it" }
        val children = form.optJSONObject("children") ?: return own
        return own + children.keys().asSequence().toList().flatMap { key -> children.optJSONObject(key)?.let { formErrors(it, key) }.orEmpty() }
    }

    private companion object {
        const val TIMEOUT = 15_000
        const val PROBE_TIMEOUT = 5_000
        const val FIND_WINDOW = 5 * 60_000L

        // HTML5 datetime-local, as Kimai's API wants it
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
