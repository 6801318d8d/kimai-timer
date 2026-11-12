# Kimai Timer

An Android app for [Kimai](https://www.kimai.org) in two parts:

- **Kimai itself**: the app opens your Kimai's web interface, with its login, its plugins and everything else, so it
  replaces Kimai's web app installed from the browser. Links to other sites open in the browser, exports go to
  Downloads, file fields open Android's file picker.
- **The running timer in a notification, also on the lock screen**: a clock ticking in HH:MM:SS with project,
  activity, customer and description, and a Stop button. After a stop from the notification, the card offers Restart
  for 15 minutes. Tapping it opens Kimai in the app.

![The running timer on the lock screen](screenshots/lock-screen.webp)

![Expanded: description and Stop button](screenshots/expanded.webp)

Timers started or stopped anywhere else (the web pages, another app, the API) show up on the phone within a second
or two, with the Kimai plugin [AppPushBundle](https://github.com/6801318d8d/AppPushBundle) installed. Without it, the
app checks every 15 minutes.

## Without the network, or with Kimai down

The timer keeps working. Stop and Restart on the notification, and everything on the app's timer screen, take effect
at once on the phone and wait in a queue; the app sends them when Kimai is reachable again, **with the times of the
taps**: a timer stopped at 18:00 ends at 18:00 even if Kimai hears of it at 19:30. The notification's header says
"2 changes waiting for Kimai" meanwhile. The queue is sent as soon as the network comes back, and while Kimai itself
does not answer the app tries again after a pause that grows with the outage (10 seconds to 15 minutes); opening the
app or "Try again" sends it at once. Nothing is given up.

When Kimai cannot be reached, opening the app shows its own **timer screen** instead of Kimai's pages: the running
timer with Stop, a form to start a timer (project, activity, description, from the lists saved at the last
connection) and the latest entries, with the ones not yet sent marked "waiting" (a tap on one fills the form). Kimai's
web interface itself needs Kimai.

Other devices may change things meanwhile. As in Kimai, one timer runs at a time:

- a timer started on the phone while another one runs in Kimai: the one started later wins, the other one ends when
  it began;
- a timer stopped both on the phone and elsewhere: the earlier stop counts;
- an entry deleted elsewhere: the phone's change to it is dropped.

A change Kimai refuses (an activity hidden meanwhile, a locked period, no permission) is dropped and listed in a
separate notification, "Kimai refused a change", with its times, to be entered by hand. A refused token is not a
refusal: the queue waits for a working one.

## Download

The APK is attached to each [release](https://github.com/6801318d8d/kimai-timer/releases). Android 12 or newer.

## Setup

1. At the first start the app opens its settings: enter the Kimai address, tap "Save and connect" and allow
   notifications.
2. Kimai's login page opens: log in. The app then creates its own API token in Kimai, "Kimai Timer (device model)"
   under your profile → API access, and the notification starts.
3. On Xiaomi/HyperOS phones also turn on Autostart and allow the notifications on the lock screen (App settings).

Why a token as well as the login: the notification works in the background through Kimai's API, which takes only API
tokens, while the web interface uses the login session. The app makes the token through the web interface, right
after the login, as you would by hand (Kimai wants a fresh login there, not one restored by "remember me"). To use a
token of your own instead, paste it in the settings before "Save and connect"; emptying the field and "Save and
connect" makes the app create a new one. A user whom Kimai does not allow to create API tokens needs one made by an
administrator.

Settings later: long press on the app icon → Settings. The token (for the notification) and the login session (for
the web interface) stay in the app's private storage; the app talks only to your Kimai.

Downloads that the web page starts with a form (POST) or from JavaScript cannot go through Android's download manager:
for those, open Kimai in the browser.

## How changes made elsewhere arrive

Chosen in the app, under "Timers started or stopped elsewhere arrive":

- **Automatically** (default): UnifiedPush if a distributor app is installed and works, else the direct connection.
- **UnifiedPush**: through a [UnifiedPush](https://unifiedpush.org) distributor app (ntfy, NextPush, Sunup...),
  which keeps the phone's one push connection: the lightest on the battery. Kimai sends a message that only says
  "your timesheets changed", and the app reads the timer from the API. Apps' own embedded distributors are left out;
  with more than one distributor, "UnifiedPush distributor" chooses.
- **Direct connection**: the app keeps Kimai's event stream open in a foreground service, so its notification stays
  even when no timer runs ("No timer running", with the latest entry and Restart). Allow it in the background
  (battery optimisation) so the system does not close it.
- **Every 15 minutes only**.

The 15-minute check runs in every mode as a safety net, sends what is still waiting, and brings the direct
connection back if the system closed it.

### Setup over adb

To configure a phone from a computer without typing the token on it (only the adb shell can send this broadcast:
the receiver requires `android.permission.DUMP`):

```sh
adb shell am broadcast -n io.github.x6801318d8d.timer/io.github.x6801318d8d.kimaitimer.SetupReceiver --es url https://kimai.example.com --es token <token> [--es mode auto|unifiedpush|direct|periodic]
adb shell am start -S -n io.github.x6801318d8d.timer/io.github.x6801318d8d.kimaitimer.MainActivity
```

Without `--es token`, the app opens Kimai's login page and creates its token after the login, as in the normal setup.

## Build

JDK 17 or newer (tested with 25) and the Android SDK with platform 36:

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

The release APK is `app/build/outputs/apk/release/app-release.apk` (about 720 KB, minified). It is signed with the
key described in `signing/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; not in
git), or with the debug key when that file is missing.

`assembleStaging` builds the same app as "Kimai Timer (test)", application id `io.github.x6801318d8d.timer.test`: it
installs next to the real one, with its own settings, for tests against another Kimai (Play Protect blocks the
debug-signed build). The unit tests (`SenderTest`) run the queue against a fake Kimai: offline stops, conflicts, lost
answers, refusals.

Dependencies: AndroidX Core and WorkManager, and the UnifiedPush connector (which brings Tink for Web Push
decryption; AppPushBundle's messages carry no data and arrive unencrypted).

## Code

| File | Role |
| --- | --- |
| `MainActivity.kt` | Kimai's web interface (WebView): login, links, downloads, file picker, back navigation; the timer screen in its place when Kimai cannot be reached |
| `TimerPanel.kt` | The app's own timer screen: running timer, start form, latest entries |
| `SettingsActivity.kt` | Settings and status screen |
| `TimerNotification.kt` | The notification, one layout for every state (running, stopped, idle), and the one of refused changes |
| `Local.kt`, `Change.kt` | The timer as the phone knows it, and the queue of changes Kimai has not received |
| `Tracker.kt` | The buttons' side: changes the timer at once, queues the change, asks for a sync |
| `Sender.kt` | Sends the queue with the times of the taps; conflicts, lost answers, refusals |
| `SyncWorker.kt` | All API calls, in the background: sync (queue, then timer, entries, projects and activities), retries, endpoint registration |
| `Catalog.kt` | Projects and activities saved for the timer screen |
| `Delivery.kt` | The modes: picks UnifiedPush or the direct connection and starts or stops them |
| `PushService.kt` | UnifiedPush endpoint and messages |
| `StreamService.kt` | The direct connection (foreground service, type specialUse) |
| `KimaiApi.kt` | Kimai's REST API and AppPushBundle |
| `WebToken.kt` | Creates the app's API token through Kimai's web interface, with the WebView's login |
| `ActionReceiver.kt`, `BootReceiver.kt`, `SetupReceiver.kt` | Notification buttons and redraws; boot and update; setup over adb |

Android's Chronometer formats MM:SS below one hour and H:MM:SS above, so the leading zeros go in its format string
("00:%s", "0%s", "%s") and an exact, non-wakeup alarm redraws the notification at the 1 h and 10 h marks.

## License

MIT. The timer icon is Google's Material Icons "timer" (Apache License 2.0).
