package io.github.x6801318d8d.kimaitimer

import org.json.JSONObject

/**
 * A change made on this device that Kimai has not received yet, with the time of the tap. The queue keeps them in
 * order until they go through (see [Sender]).
 */
sealed class Change {
    abstract fun toJson(): JSONObject

    /**
     * A timer started here. [id] is the entry's local (negative) id; [from] the Kimai entry it restarts, used only when
     * [projectId] is unknown; [title] for messages. [sentAt]: when it was first sent, 0 before; an answer lost on the
     * way may hide an entry that Kimai did create.
     */
    data class Start(
        val id: Int,
        val projectId: Int,
        val activityId: Int,
        val description: String,
        val begin: Long,
        val title: String,
        val from: Int = 0,
        val sentAt: Long = 0,
    ) : Change() {
        override fun toJson(): JSONObject = JSONObject()
            .put("type", "start")
            .put("id", id)
            .put("project_id", projectId)
            .put("activity_id", activityId)
            .put("description", description)
            .put("begin", begin)
            .put("title", title)
            .put("from", from)
            .put("sent_at", sentAt)
    }

    /** A timer stopped here at [end]. [id] is Kimai's, or the local one of an entry started here. */
    data class Stop(val id: Int, val end: Long) : Change() {
        override fun toJson(): JSONObject = JSONObject().put("type", "stop").put("id", id).put("end", end)
    }

    companion object {
        fun fromJson(json: JSONObject): Change = when (json.getString("type")) {
            "start" -> Start(
                id = json.getInt("id"),
                projectId = json.getInt("project_id"),
                activityId = json.getInt("activity_id"),
                description = json.optString("description"),
                begin = json.getLong("begin"),
                title = json.optString("title"),
                from = json.optInt("from"),
                sentAt = json.optLong("sent_at"),
            )
            else -> Stop(json.getInt("id"), json.getLong("end"))
        }
    }
}
