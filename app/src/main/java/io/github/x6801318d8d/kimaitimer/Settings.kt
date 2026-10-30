package io.github.x6801318d8d.kimaitimer

import android.content.Context
import org.json.JSONObject

/**
 * What the app keeps between runs, in its private preferences: the Kimai connection, how it hears about changes,
 * and the last known state of the timer (so the notification can be redrawn without the network).
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

    var running: Timer?
        get() = prefs.getString("running", null)?.let { Timer.fromJson(JSONObject(it)) }
        set(value) = prefs.edit().putString("running", value?.toJson()?.toString()).apply()

    /** The latest finished entry: the one Restart starts again. */
    var last: Timer?
        get() = prefs.getString("last", null)?.let { Timer.fromJson(JSONObject(it)) }
        set(value) = prefs.edit().putString("last", value?.toJson()?.toString()).apply()

    /** After a stop from the notification, its "Stopped" card stays until this time. */
    var stoppedUntil: Long
        get() = prefs.getLong("stopped_until", 0)
        set(value) = prefs.edit().putLong("stopped_until", value).apply()

    val configured: Boolean
        get() = url.isNotEmpty() && token.isNotEmpty()

    fun api(): KimaiApi? = if (configured) KimaiApi(url, token) else null
}
