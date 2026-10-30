package io.github.x6801318d8d.kimaitimer

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A Kimai timesheet as the notification needs it. [duration] in seconds, only for stopped entries. */
data class Timer(
    val id: Int,
    val begin: Long,
    val project: String,
    val activity: String,
    val customer: String,
    val description: String,
    val duration: Long = 0,
) {
    /** "Project · Activity" */
    val title: String
        get() = listOf(project, activity).filter { it.isNotEmpty() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("begin", begin)
        .put("project", project)
        .put("activity", activity)
        .put("customer", customer)
        .put("description", description)
        .put("duration", duration)

    companion object {
        // Kimai sends "2026-10-30T10:32:07+0100"
        private val KIMAI_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[XXX][XX]")

        fun fromJson(json: JSONObject) = Timer(
            id = json.getInt("id"),
            begin = json.getLong("begin"),
            project = json.optString("project"),
            activity = json.optString("activity"),
            customer = json.optString("customer"),
            description = json.optString("description"),
            duration = json.optLong("duration"),
        )

        /** One timesheet of the API with project, customer and activity expanded ("full"). */
        fun fromKimai(json: JSONObject): Timer {
            val project = json.optJSONObject("project")
            return Timer(
                id = json.getInt("id"),
                begin = OffsetDateTime.parse(json.getString("begin"), KIMAI_DATE).toInstant().toEpochMilli(),
                project = project?.optString("name").orEmpty(),
                activity = json.optJSONObject("activity")?.optString("name").orEmpty(),
                customer = project?.optJSONObject("customer")?.optString("name").orEmpty(),
                description = if (json.isNull("description")) "" else json.optString("description"),
                duration = json.optLong("duration"),
            )
        }

        /** HH:MM:SS, hours not limited to 24. */
        fun formatDuration(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }
    }
}
