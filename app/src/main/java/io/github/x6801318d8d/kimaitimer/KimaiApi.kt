package io.github.x6801318d8d.kimaitimer

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The few calls of Kimai's REST API the app needs, with an API token (Kimai: Profile → API access), plus the plugin
 * AppPushBundle's push endpoints and event stream. Blocking: call from a background thread.
 */
class KimaiApi(private val url: String, private val token: String) {

    /** Kimai answered with an error status. */
    class HttpException(val code: Int, message: String) : IOException(message)

    /** The user the token belongs to (alias or username), to check the connection. */
    fun me(): String {
        val json = JSONObject(request("GET", "/api/users/me"))
        return json.optString("alias").ifEmpty { json.optString("username") }
    }

    /** The running timers of the user, newest first. */
    fun active(): List<Timer> {
        val array = JSONArray(request("GET", "/api/timesheets/active"))
        return (0 until array.length()).map { Timer.fromKimai(array.getJSONObject(it)) }.sortedByDescending { it.begin }
    }

    /** The user's most recent finished entry, if any. */
    fun latest(): Timer? {
        val array = JSONArray(request("GET", "/api/timesheets?size=1&full=true&active=0&orderBy=begin&order=DESC"))
        return if (array.length() == 0) null else Timer.fromKimai(array.getJSONObject(0))
    }

    /** Stops a running timer; returns its duration in seconds. */
    fun stop(id: Int): Long = JSONObject(request("PATCH", "/api/timesheets/$id/stop")).optLong("duration")

    /** Starts a new timer with the project and activity of entry [id]. */
    fun restart(id: Int) {
        request("PATCH", "/api/timesheets/$id/restart", JSONObject())
    }

    fun registerEndpoint(endpoint: String, name: String) {
        request("POST", "/api/app-push/endpoints", JSONObject().put("endpoint", endpoint).put("name", name))
    }

    fun unregisterEndpoint(endpoint: String) {
        request("DELETE", "/api/app-push/endpoints?endpoint=" + URLEncoder.encode(endpoint, "UTF-8"))
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
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val message = runCatching { JSONObject(error).optString("message") }.getOrNull().orEmpty()
            throw HttpException(code, "HTTP $code" + if (message.isNotEmpty()) ": $message" else "")
        }
    }

    private companion object {
        const val TIMEOUT = 15_000
    }
}
