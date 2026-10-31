package io.github.x6801318d8d.kimaitimer

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * What the app keeps between runs, in its private preferences: the Kimai connection, how it hears about changes,
 * the timer as this device knows it with the changes waiting for Kimai, and Kimai's projects and activities (so that
 * the timer works without the network).
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(value) = prefs.edit().putString("url", value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    /** How the app hears about changes made elsewhere: one of the [Delivery] mode names, or [Delivery.AUTO]. */
    var mode: String
        get() = prefs.getString("mode", Delivery.AUTO) ?: Delivery.AUTO
        set(value) = prefs.edit().putString("mode", value).apply()

    /** The UnifiedPush endpoint the distributor gave us, and whether Kimai accepted it. */
    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) = prefs.edit().putString("endpoint", value).apply()

    var endpointRegistered: Boolean
        get() = prefs.getBoolean("endpoint_registered", false)
        set(value) = prefs.edit().putBoolean("endpoint_registered", value).apply()

    /** Why UnifiedPush did not work (registration failed, or Kimai refused the endpoint): automatic mode goes direct.
     * Empty when it works or was not tried. */
    var pushError: String
        get() = prefs.getString("push_error", "") ?: ""
        set(value) = prefs.edit().putString("push_error", value).apply()

    /** The direct connection to Kimai is open right now. */
    var streamConnected: Boolean
        get() = prefs.getBoolean("stream_connected", false)
        set(value) = prefs.edit().putBoolean("stream_connected", value).apply()

    /** Last problem met while talking to Kimai or setting up updates, shown in the app; empty when all is well. */
    var lastError: String
        get() = prefs.getString("last_error", "") ?: ""
        set(value) = prefs.edit().putString("last_error", value).apply()

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(value) = prefs.edit().putLong("last_sync", value).apply()

    /** Since when syncs fail because Kimai or the network is not there; 0 once one works again. */
    var unreachableSince: Long
        get() = prefs.getLong("unreachable_since", 0)
        set(value) = prefs.edit().putLong("unreachable_since", value).apply()

    val unreachable: Boolean
        get() = unreachableSince > 0

    /** The Kimai user's timezone: Kimai's API reads and writes times in it. Empty until the first connection. */
    var timezone: String
        get() = prefs.getString("timezone", "") ?: ""
        set(value) = prefs.edit().putString("timezone", value).apply()

    var catalog: Catalog
        get() = prefs.getString("catalog", null)?.let { Catalog.fromJson(JSONObject(it)) } ?: Catalog.EMPTY
        set(value) = prefs.edit().putString("catalog", value.toJson().toString()).putLong("catalog_sync", System.currentTimeMillis()).apply()

    val catalogSync: Long
        get() = prefs.getLong("catalog_sync", 0)

    /** Changes Kimai refused, one line each, until the user dismisses their notification. */
    var refused: List<String>
        get() = prefs.getString("refused", null)?.let { json -> JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.orEmpty()
        set(value) = prefs.edit().putString("refused", JSONArray(value).toString()).apply()

    /**
     * The timer as this device knows it, with the changes Kimai has not received: read and written as one, and
     * written to disk at once (a tap must not get lost if the system ends the app). Go through [Tracker].
     */
    var local: Local
        get() = Local(
            running = prefs.getString("running", null)?.let { Timer.fromJson(JSONObject(it)) },
            // "last": the single latest entry saved before 1.3.0
            recent = prefs.getString("recent", null)?.let { timers(it) }
                ?: listOfNotNull(prefs.getString("last", null)?.let { Timer.fromJson(JSONObject(it)) }),
            stoppedUntil = prefs.getLong("stopped_until", 0),
            changes = prefs.getString("changes", null)?.let { json ->
                JSONArray(json).let { a -> (0 until a.length()).map { Change.fromJson(a.getJSONObject(it)) } }
            }.orEmpty(),
            nextId = prefs.getInt("next_id", -1),
        )
        @SuppressLint("ApplySharedPref") // on purpose: a few hundred bytes, and they must be on disk before the tap is done
        set(value) {
            prefs.edit()
                .putString("running", value.running?.toJson()?.toString())
                .putString("recent", JSONArray(value.recent.map { it.toJson() }).toString())
                .remove("last")
                .putLong("stopped_until", value.stoppedUntil)
                .putString("changes", JSONArray(value.changes.map { it.toJson() }).toString())
                .putInt("next_id", value.nextId)
                .commit()
        }

    val configured: Boolean
        get() = url.isNotEmpty() && token.isNotEmpty()

    fun api(): KimaiApi? {
        if (!configured) return null
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        return KimaiApi(url, token, zone)
    }

    private fun timers(json: String): List<Timer> =
        JSONArray(json).let { a -> (0 until a.length()).map { Timer.fromJson(a.getJSONObject(it)) } }
}
