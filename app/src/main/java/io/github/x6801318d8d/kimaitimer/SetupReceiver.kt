package io.github.x6801318d8d.kimaitimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Setup from a computer over adb, so the token need not be typed on the phone:
 *
 *     adb shell am broadcast -n io.github.x6801318d8d.timer/io.github.x6801318d8d.kimaitimer.SetupReceiver --es url https://kimai.example.com --es token <token>
 *
 * then open the app. Optional extra "mode": auto, unifiedpush, direct or periodic. Only the adb shell and the system
 * can send it: the receiver requires android.permission.DUMP, which apps cannot get.
 */
class SetupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = Settings(context)
        intent.getStringExtra("url")?.let { settings.url = it }
        intent.getStringExtra("token")?.let {
            settings.token = it
            settings.endpointRegistered = false
        }
        intent.getStringExtra("mode")?.takeIf { it in MODES }?.let { settings.mode = it }
        settings.pushError = ""
        settings.lastError = ""
        resultData = "configured: ${settings.url}, mode ${settings.mode}"
    }

    private companion object {
        val MODES = setOf(Delivery.AUTO, Delivery.UNIFIEDPUSH, Delivery.DIRECT, Delivery.PERIODIC)
    }
}
