package io.github.x6801318d8d.kimaitimer

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Kimai's projects and activities, saved for starting timers while Kimai cannot be reached. */
data class Catalog(val projects: List<Project>, val activities: List<Activity>) {

    data class Project(val id: Int, val name: String, val customer: String, val globalActivities: Boolean) {
        /** "Customer · Project" */
        val label: String
            get() = listOf(customer, name).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** [projectId] 0: a global activity, for every project that allows them. */
    data class Activity(val id: Int, val name: String, val projectId: Int)

    /** The activities a timer of [project] can use: the project's own first, then the global ones. */
    fun activities(project: Project): List<Activity> =
        activities.filter { it.projectId == project.id } +
            if (project.globalActivities) activities.filter { it.projectId == 0 } else emptyList()

    fun toJson(): JSONObject = JSONObject()
        .put("projects", JSONArray(projects.map {
            JSONObject().put("id", it.id).put("name", it.name).put("customer", it.customer).put("global", it.globalActivities)
        }))
        .put("activities", JSONArray(activities.map {
            JSONObject().put("id", it.id).put("name", it.name).put("project", it.projectId)
        }))

    companion object {
        val EMPTY = Catalog(emptyList(), emptyList())

        fun fromJson(json: JSONObject): Catalog {
            val projects = json.getJSONArray("projects")
            val activities = json.getJSONArray("activities")
            return Catalog(
                (0 until projects.length()).map { projects.getJSONObject(it) }.map {
                    Project(it.getInt("id"), it.getString("name"), it.optString("customer"), it.optBoolean("global", true))
                },
                (0 until activities.length()).map { activities.getJSONObject(it) }.map {
                    Activity(it.getInt("id"), it.getString("name"), it.optInt("project"))
                },
            )
        }

        /** From Kimai's /api/projects and /api/activities; projects that ended or have not started yet are left out. */
        fun fromKimai(projects: JSONArray, activities: JSONArray, today: LocalDate = LocalDate.now()): Catalog = Catalog(
            (0 until projects.length()).map { projects.getJSONObject(it) }
                .filter { open(it, today) }
                .map {
                    val customer = if (it.isNull("parentTitle")) "" else it.getString("parentTitle")
                    Project(it.getInt("id"), it.getString("name"), customer, it.optBoolean("globalActivities", true))
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
            (0 until activities.length()).map { activities.getJSONObject(it) }
                .map { Activity(it.getInt("id"), it.getString("name"), if (it.isNull("project")) 0 else it.optInt("project")) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
        )

        private fun open(project: JSONObject, today: LocalDate): Boolean {
            fun date(key: String) = if (project.isNull(key)) null else runCatching { LocalDate.parse(project.getString(key).take(10)) }.getOrNull()
            return date("start")?.isAfter(today) != true && date("end")?.isBefore(today) != true
        }
    }
}
