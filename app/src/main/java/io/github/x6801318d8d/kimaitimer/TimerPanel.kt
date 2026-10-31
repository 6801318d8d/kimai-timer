package io.github.x6801318d8d.kimaitimer

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * The app's own timer screen, shown instead of Kimai's web interface while Kimai cannot be reached: the timer as this
 * device knows it, with Stop; a new timer from the projects and activities saved from Kimai; the latest entries (a
 * tap fills the form with one of them). It all goes into the same queue as the notification's buttons ([Tracker])
 * and reaches Kimai when it is back.
 */
class TimerPanel(private val activity: Activity, private val root: View) {
    private val message: TextView = root.findViewById(R.id.panel_message)
    private val clock: TextView = root.findViewById(R.id.panel_clock)
    private val title: TextView = root.findViewById(R.id.panel_title)
    private val subtitle: TextView = root.findViewById(R.id.panel_subtitle)
    private val stop: Button = root.findViewById(R.id.panel_stop)
    private val projects: Spinner = root.findViewById(R.id.panel_project)
    private val activities: Spinner = root.findViewById(R.id.panel_activity)
    private val description: EditText = root.findViewById(R.id.panel_description)
    private val start: Button = root.findViewById(R.id.panel_start)
    private val noCatalog: TextView = root.findViewById(R.id.panel_no_catalog)
    private val recent: LinearLayout = root.findViewById(R.id.panel_recent)
    private val pending: TextView = root.findViewById(R.id.panel_pending)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }
    private var catalog = Catalog.EMPTY
    private var catalogSync = -1L
    private var projectList: List<Catalog.Project> = emptyList()
    private var activityList: List<Catalog.Activity> = emptyList()
    /** What the lists show, to rebuild them only when it changes. */
    private var rendered: Local? = null
    /** The form is being filled by the app, not typed. */
    private var filling = false

    /** Shown by the app on its own (Kimai slow to answer), not after an error: it goes once Kimai's page loads. */
    var automatic = false
        private set

    /** The user did something here: the screen stays even if Kimai's page loads meanwhile. */
    var touched = false
        private set

    val visible: Boolean
        get() = root.visibility == View.VISIBLE

    init {
        stop.setOnClickListener {
            touched = true
            Tracker.read(activity).running?.let { Tracker.stop(activity, it.id) }
            render()
        }
        start.setOnClickListener {
            touched = true
            startTimer()
        }
        projects.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showActivities(activityList.getOrNull(activities.selectedItemPosition)?.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        description.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (!filling) touched = true
            }
        })
    }

    fun show(text: String, automatic: Boolean) {
        message.text = text
        if (!visible) {
            this.automatic = automatic
            touched = false
        } else if (!automatic) {
            this.automatic = false
        }
        root.visibility = View.VISIBLE
        rendered = null
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun hide() {
        root.visibility = View.GONE
        handler.removeCallbacks(tick)
    }

    fun pause() = handler.removeCallbacks(tick)

    fun resume() {
        if (visible) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    private fun render() {
        val settings = Settings(activity)
        if (settings.catalogSync != catalogSync) {
            catalogSync = settings.catalogSync
            catalog = settings.catalog
            showProjects()
        }
        val local = Tracker.read(activity)
        val running = local.running
        clock.text = Timer.formatDuration(running?.let { (System.currentTimeMillis() - it.begin) / 1000 } ?: 0)
        if (local == rendered) return
        rendered = local

        clock.visibility = if (running != null) View.VISIBLE else View.GONE
        stop.visibility = clock.visibility
        title.text = running?.project?.ifEmpty { running.title } ?: activity.getString(R.string.idle_title)
        subtitle.text = running?.let { listOf(it.activity, it.customer, it.description).filter { s -> s.isNotEmpty() }.joinToString(" · ") }.orEmpty()

        recent.removeAllViews()
        for (timer in local.recent) recent.addView(row(timer, local.pending(timer.id)))
        val waiting = local.changes.size
        pending.text = if (waiting > 0) activity.resources.getQuantityString(R.plurals.waiting, waiting, waiting) else ""
        pending.visibility = if (waiting > 0) View.VISIBLE else View.GONE
    }

    private fun showProjects() {
        val selected = projectList.getOrNull(projects.selectedItemPosition)?.id
        projectList = catalog.projects
        projects.adapter = adapter(projectList.map { it.label })
        projectList.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { projects.setSelection(it) }
        showActivities(activityList.getOrNull(activities.selectedItemPosition)?.id)
        noCatalog.visibility = if (projectList.isEmpty()) View.VISIBLE else View.GONE
        start.isEnabled = projectList.isNotEmpty()
    }

    /** The activities of the selected project, keeping [keep] selected if it is among them. */
    private fun showActivities(keep: Int?) {
        val project = projectList.getOrNull(projects.selectedItemPosition)
        activityList = project?.let { catalog.activities(it) }.orEmpty()
        activities.adapter = adapter(activityList.map { it.name })
        activityList.indexOfFirst { it.id == keep }.takeIf { it >= 0 }?.let { activities.setSelection(it) }
    }

    private fun startTimer() {
        val project = projectList.getOrNull(projects.selectedItemPosition) ?: return
        val chosen = activityList.getOrNull(activities.selectedItemPosition) ?: return
        val template = Timer(
            id = 0,
            begin = 0,
            project = project.name,
            activity = chosen.name,
            customer = project.customer,
            description = "",
            projectId = project.id,
            activityId = chosen.id,
        )
        Tracker.start(activity, template, description.text.toString().trim())
        filling = true
        description.setText("")
        filling = false
        render()
    }

    /** One of the latest entries; a tap puts its project, activity and description in the form. */
    private fun row(timer: Timer, waiting: Boolean): TextView {
        val end = if (timer.end > 0) timer.end else timer.begin + timer.duration * 1000
        val line = listOfNotNull(
            DateFormat.format("yyyy-MM-dd HH:mm", timer.begin).toString() + "–" + DateFormat.format("HH:mm", end),
            Timer.formatDuration(timer.duration),
            activity.getString(R.string.panel_waiting).takeIf { waiting },
        ).joinToString(" · ")
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
        val outValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return TextView(activity).apply {
            text = listOf(timer.title, timer.description, line).filter { it.isNotEmpty() }.joinToString("\n")
            setPadding(0, padding, 0, padding)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { fill(timer) }
        }
    }

    private fun fill(timer: Timer) {
        touched = true
        val position = projectList.indexOfFirst { it.id == timer.projectId }
        if (position >= 0) {
            projects.setSelection(position)
            showActivities(timer.activityId)
        }
        filling = true
        description.setText(timer.description)
        filling = false
    }

    private fun adapter(labels: List<String>) =
        ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
