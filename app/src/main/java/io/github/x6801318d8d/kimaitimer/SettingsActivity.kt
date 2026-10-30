package io.github.x6801318d8d.kimaitimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import org.unifiedpush.android.connector.UnifiedPush
import kotlin.concurrent.thread

/** Settings and status: Kimai address and API token, how updates arrive, permissions. */
class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var modes: RadioGroup
    private lateinit var distributorButton: Button
    private lateinit var batteryButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000)
        }
    }
    private val modeIds = mapOf(
        Delivery.AUTO to R.id.mode_auto,
        Delivery.UNIFIEDPUSH to R.id.mode_unifiedpush,
        Delivery.DIRECT to R.id.mode_direct,
        Delivery.PERIODIC to R.id.mode_periodic,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        url = findViewById(R.id.url)
        token = findViewById(R.id.token)
        status = findViewById(R.id.status)
        modes = findViewById(R.id.modes)
        distributorButton = findViewById(R.id.distributor)
        batteryButton = findViewById(R.id.battery)
        TimerNotification.createChannel(this)

        url.setText(settings.url.ifEmpty { intent.getStringExtra("url").orEmpty() })
        token.setText(settings.token)
        modes.check(modeIds[settings.mode] ?: R.id.mode_auto)
        modes.setOnCheckedChangeListener { _, checked ->
            val mode = modeIds.entries.first { it.value == checked }.key
            if (mode != settings.mode) {
                settings.mode = mode
                settings.pushError = ""
                Delivery.apply(this, register = true)
            }
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.refresh).setOnClickListener { SyncWorker.enqueue(this, SyncWorker.REFRESH) }
        distributorButton.setOnClickListener { chooseDistributor() }
        batteryButton.setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.app_settings).setOnClickListener {
            startActivity(Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Delivery.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun save() {
        val newUrl = url.text.toString().trim().trimEnd('/')
        val newToken = token.text.toString().trim()
        if (!newUrl.startsWith("https://") && !newUrl.startsWith("http://")) {
            status.text = getString(R.string.error_url)
            return
        }
        status.text = getString(R.string.connecting)
        thread {
            val result = runCatching { KimaiApi(newUrl, newToken).me() }
            runOnUiThread {
                result.onSuccess { user ->
                    val changed = newUrl != settings.url || newToken != settings.token
                    settings.url = newUrl
                    settings.token = newToken
                    settings.lastError = ""
                    settings.pushError = ""
                    if (changed) {
                        settings.endpointRegistered = false
                    }
                    Delivery.apply(this, register = true)
                    if (changed && settings.endpoint.isNotEmpty()) {
                        SyncWorker.enqueue(this, SyncWorker.REGISTER)
                    }
                    SyncWorker.enqueue(this, SyncWorker.REFRESH)
                    status.text = getString(R.string.connected_as, user)
                }.onFailure {
                    status.text = getString(R.string.error_connection, it.message ?: it.javaClass.simpleName)
                }
            }
        }
    }

    private fun chooseDistributor() {
        val distributors = Delivery.distributors(this)
        if (distributors.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.no_distributor_help).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val labels = distributors.map { Delivery.label(this, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_distributor)
            .setSingleChoiceItems(labels, distributors.indexOf(Delivery.distributor(this))) { dialog, which ->
                dialog.dismiss()
                if (distributors[which] != UnifiedPush.getSavedDistributor(this)) {
                    // the old distributor's endpoint goes, the new one registers
                    Delivery.unregister(this)
                    UnifiedPush.saveDistributor(this, distributors[which])
                }
                settings.pushError = ""
                Delivery.apply(this, register = true)
            }
            .show()
    }

    @SuppressLint("BatteryLife") // a personal app the user installs by hand, not a Play Store one
    private fun askBatteryExemption() {
        startActivity(Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)))
    }

    private fun updateStatus() {
        val mode = Delivery.effective(this)
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        distributorButton.visibility = if (mode == Delivery.UNIFIEDPUSH || Delivery.distributors(this).size > 1) View.VISIBLE else View.GONE
        batteryButton.visibility = if (mode == Delivery.DIRECT && !exempt) View.VISIBLE else View.GONE
        if (!settings.configured) {
            status.text = getString(R.string.status_not_configured)
            return
        }
        val lines = mutableListOf<String>()
        val running = settings.running
        lines += if (running != null) {
            getString(R.string.status_running, running.title, DateFormat.format("HH:mm:ss", running.begin))
        } else {
            getString(R.string.status_idle)
        }
        if (settings.lastSync > 0) {
            lines += getString(R.string.status_last_check, DateFormat.format("yyyy-MM-dd HH:mm:ss", settings.lastSync))
        }
        lines += when (mode) {
            Delivery.UNIFIEDPUSH -> {
                val distributor = Delivery.distributor(this)?.let { Delivery.label(this, it) } ?: "?"
                when {
                    settings.endpointRegistered -> getString(R.string.status_push_on, distributor)
                    settings.pushError.isNotEmpty() -> getString(R.string.status_push_failed, settings.pushError)
                    else -> getString(R.string.status_push_pending, distributor)
                }
            }
            Delivery.DIRECT -> {
                val state = getString(if (settings.streamConnected) R.string.status_direct_connected else R.string.status_direct_connecting)
                val why = if (settings.mode == Delivery.AUTO && settings.pushError.isNotEmpty()) "\n" + getString(R.string.status_push_failed, settings.pushError) else ""
                state + why + if (exempt) "" else "\n" + getString(R.string.status_battery)
            }
            else -> getString(R.string.status_periodic)
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            lines += getString(R.string.status_notifications_off)
        }
        if (settings.lastError.isNotEmpty()) {
            lines += getString(R.string.status_error, settings.lastError)
        }
        status.text = lines.joinToString("\n")
    }
}
