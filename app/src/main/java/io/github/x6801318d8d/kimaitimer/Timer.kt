package io.github.x6801318d8d.kimaitimer

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A Kimai timesheet as the app needs it. [id] is negative for an entry started on this device that Kimai has not
 * received yet. [begin] and [end] in epoch milliseconds, [end] 0 while running; [duration] in seconds, only for
 * stopped entries. [projectId] and [activityId] are 0 when unknown (state saved by versions before 1.3.0).
 */
data class Timer(
    val id: Int,
    val begin: Long,
    val project: String,
    val activity: String,
    val customer: String,
    val description: String,
    val duration: Long = 0,
    val end: Long = 0,
    val projectId: Int = 0,
    val activityId: Int = 0,
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
        .put("end", end)
        .put("project_id", projectId)
        .put("activity_id", activityId)

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
            end = json.optLong("end"),
            projectId = json.optInt("project_id"),
            activityId = json.optInt("activity_id"),
        )

        /** One timesheet of the API: project and activity are objects with "full", plain ids without. */
        fun fromKimai(json: JSONObject): Timer {
            val project = json.optJSONObject("project")
            val activity = json.optJSONObject("activity")
            return Timer(
                id = json.getInt("id"),
                begin = parse(json.getString("begin")),
                project = project?.optString("name").orEmpty(),
                activity = activity?.optString("name").orEmpty(),
                customer = project?.optJSONObject("customer")?.optString("name").orEmpty(),
                description = if (json.isNull("description")) "" else json.optString("description"),
                duration = json.optLong("duration"),
                end = if (json.isNull("end")) 0 else parse(json.getString("end")),
                projectId = project?.optInt("id") ?: json.optInt("project"),
                activityId = activity?.optInt("id") ?: json.optInt("activity"),
            )
        }

        private fun parse(date: String) = OffsetDateTime.parse(date, KIMAI_DATE).toInstant().toEpochMilli()

        /** HH:MM:SS, hours not limited to 24. */
        fun formatDuration(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }
    }
}
