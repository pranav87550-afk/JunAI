package com.junai.app.agent.action

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.junai.app.agent.screen.ScreenContextEngine
import kotlinx.coroutines.delay

data class ActionResult(val success: Boolean, val message: String)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

enum class SettingsType {
    WIFI, BLUETOOTH, DISPLAY, SOUND, DND, AIRPLANE_MODE,
    WRITE_SETTINGS_PERMISSION, APP_DETAILS
}

/**
 * ActionEngine — executes real actions on the phone.
 *
 * TRUST BOUNDARY: this object assumes the caller (AgentEngine) already ran
 * DecisionEngine.evaluate() and received PROCEED before calling anything
 * here — it does not re-check SafetyLayer for payments/deletes/etc, that
 * gate already happened upstream. The one absolute exception is
 * [typeText]: it refuses to type into a password field no matter what,
 * since that's a flat prohibition (READ_CREDENTIAL), not a confirmable risk.
 *
 * VERIFICATION STRATEGY: for system-level toggles (Bluetooth, brightness,
 * volume, flashlight, DND, WiFi) this verifies against the *actual system
 * state* (e.g. BluetoothAdapter.isEnabled()) rather than screen text —
 * far more reliable than hoping a confirmation string appears somewhere.
 * For UI actions (tap, openApp) there's no such system-level signal, so
 * those retry the underlying call itself and, where meaningful, re-check
 * against ScreenContextEngine.
 */
@Suppress("DEPRECATION")
object ActionEngine {

    private const val TAG = "ActionEngine"
    private const val MAX_RETRIES = 2

    private fun log(action: String, target: String, result: ActionResult) {
        android.util.Log.d(TAG, "[$action] target=\"$target\" success=${result.success} msg=${result.message} t=${System.currentTimeMillis()}")
    }

    private fun service(): JunAccessibilityService? = JunAccessibilityService.instance

    private fun noServiceResult() =
        ActionResult(false, "Jun's Accessibility permission isn't on yet — turn it on in Settings to let me do this.")

    /** Generic retry wrapper — retries [check] up to [maxRetries] times if it returns false. */
    private suspend fun retryUntil(
        maxRetries: Int = MAX_RETRIES,
        delayMs: Long = 400L,
        check: suspend (attempt: Int) -> Boolean
    ): Pair<Boolean, Int> {
        var attempt = 0
        while (attempt <= maxRetries) {
            if (check(attempt)) return true to (attempt + 1)
            attempt++
            if (attempt <= maxRetries) delay(delayMs)
        }
        return false to attempt
    }

    // ══════════════════ ACCESSIBILITY-BASED ACTIONS ══════════════════

    suspend fun tap(nodeId: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("tap", nodeId, it) }
        val (ok, attempts) = retryUntil { _ ->
            val node = svc.findNodeByText(nodeId) ?: svc.findNodeById(nodeId)
            node?.let { svc.tap(it).also { _ -> it.recycle() } } ?: false
        }
        val result = if (ok) ActionResult(true, "Tapped \"$nodeId\" (attempt $attempts).")
        else ActionResult(false, "Couldn't find or tap \"$nodeId\" after $attempts attempt(s).")
        log("tap", nodeId, result)
        return result
    }

    /**
     * Tap variant used by macro replay, which has richer per-step info than
     * a single search string. See findNodeByIdDisambiguated() — when a
     * resourceId is shared across several near-identical elements (Quick
     * Settings tiles being the clearest example: Bluetooth/Location/WiFi
     * can all use the same tile template id), plain tap(resourceId) always
     * landed on the first match regardless of which tile was actually
     * recorded. Passing the recorded text/contentDescription too lets us
     * pick the RIGHT one among same-id candidates instead of just the first.
     */
    suspend fun tapStep(resourceId: String?, text: String?, contentDescription: String?): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("tapStep", resourceId ?: text ?: "?", it) }
        val disambiguator = text ?: contentDescription
        val label = resourceId ?: text ?: contentDescription ?: "?"
        val (ok, attempts) = retryUntil { _ ->
            val node = if (!resourceId.isNullOrBlank()) {
                svc.findNodeByIdDisambiguated(resourceId, disambiguator)
            } else {
                svc.findNodeByText(disambiguator ?: "")
            }
            node?.let { svc.tap(it).also { _ -> it.recycle() } } ?: false
        }
        val result = if (ok) ActionResult(true, "Tapped \"$label\" (attempt $attempts).")
        else ActionResult(false, "Couldn't find or tap \"$label\" after $attempts attempt(s).")
        log("tapStep", label, result)
        return result
    }

    suspend fun longPress(nodeId: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("longPress", nodeId, it) }
        val (ok, attempts) = retryUntil { _ ->
            val node = svc.findNodeByText(nodeId) ?: svc.findNodeById(nodeId)
            node?.let { svc.longPress(it).also { _ -> it.recycle() } } ?: false
        }
        val result = if (ok) ActionResult(true, "Long-pressed \"$nodeId\".")
        else ActionResult(false, "Couldn't long-press \"$nodeId\" after $attempts attempt(s).")
        log("longPress", nodeId, result)
        return result
    }

    suspend fun scroll(direction: ScrollDirection, nodeId: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("scroll", nodeId, it) }
        val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
        val (ok, attempts) = retryUntil { _ ->
            val node = svc.findNodeById(nodeId) ?: svc.findNodeByText(nodeId)
            node?.let { svc.scroll(it, forward).also { _ -> it.recycle() } } ?: false
        }
        val result = if (ok) ActionResult(true, "Scrolled \"$nodeId\" ${direction.name.lowercase()}.")
        else ActionResult(false, "Couldn't scroll \"$nodeId\" after $attempts attempt(s).")
        log("scroll", nodeId, result)
        return result
    }

    /** Refuses outright if the resolved field is a password field — no exceptions, ever. */
    suspend fun typeText(nodeId: String, text: String): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("typeText", nodeId, it) }

        val precheck = svc.findNodeById(nodeId) ?: svc.findNodeByText(nodeId)
        if (precheck?.isPassword == true) {
            precheck.recycle()
            val result = ActionResult(false, "Jun never types into password fields — no exceptions.")
            log("typeText", nodeId, result)
            return result
        }
        precheck?.recycle()

        val (ok, attempts) = retryUntil { _ ->
            val node = svc.findNodeById(nodeId) ?: svc.findNodeByText(nodeId)
            if (node == null) return@retryUntil false
            if (node.isPassword) { node.recycle(); return@retryUntil false }
            val typed = svc.typeText(node, text)
            // BUGFIX: `node` is a point-in-time snapshot. After performAction()
            // mutates the real view, this same AccessibilityNodeInfo object
            // still reports its PRE-action text until explicitly refreshed —
            // so the old code almost always read stale (often empty/hint)
            // text here and reported "couldn't find the message field" even
            // when typing actually succeeded. refresh() re-syncs the node
            // with the live view before we read .text.
            node.refresh()
            val confirmed = typed && node.text?.toString()?.contains(text) == true
            node.recycle()
            confirmed
        }
        val result = if (ok) ActionResult(true, "Typed into \"$nodeId\".")
        else ActionResult(false, "Couldn't confirm text was typed into \"$nodeId\" after $attempts attempt(s).")
        log("typeText", nodeId, result)
        return result
    }

    fun pressBack(): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("pressBack", "-", it) }
        val ok = svc.pressBack()
        val result = if (ok) ActionResult(true, "Pressed back.") else ActionResult(false, "Couldn't press back.")
        log("pressBack", "-", result)
        return result
    }

    fun pressHome(): ActionResult {
        val svc = service() ?: return noServiceResult().also { log("pressHome", "-", it) }
        val ok = svc.pressHome()
        val result = if (ok) ActionResult(true, "Pressed home.") else ActionResult(false, "Couldn't press home.")
        log("pressHome", "-", result)
        return result
    }

    suspend fun openApp(context: Context, packageName: String): ActionResult {
        var launched = false
        val (ok, attempts) = retryUntil(delayMs = 500L) { _ ->
            try {
                if (ScreenContextEngine.isAppInForeground(packageName)) return@retryUntil true
                // BUGFIX: previously this re-sent the launch Intent on every
                // retry (every ~1.1s) whenever isAppInForeground still read
                // false. If the app WAS actually opening but the foreground
                // snapshot just hadn't caught up yet, re-launching mid
                // open-animation restarts/interrupts that transition —
                // which could keep currentApp from ever settling long enough
                // to be sampled as "in foreground", so confirmation failed
                // forever even though the app was genuinely open on screen.
                // Now the intent fires exactly once; remaining attempts only
                // poll the snapshot, giving it time to catch up.
                if (!launched) {
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return@retryUntil false
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    launched = true
                }
                delay(500L)
                ScreenContextEngine.isAppInForeground(packageName)
            } catch (e: Exception) {
                false
            }
        }
        var result = if (ok) ActionResult(true, "Opened $packageName.")
        else if (!launched) ActionResult(false, "Couldn't find $packageName — it may not be installed.")
        else ActionResult(false, "Opened $packageName but couldn't confirm it's in the foreground after $attempts attempt(s).")

        // BUGFIX: the first version of this extended wait only kicked in if
        // looksLikeLockScreen() was ALREADY true right after the initial 3
        // quick attempts. But a slow cold-start (e.g. on low battery, where
        // Android throttles app launch) can just be genuinely slow with NO
        // lock screen at all — that case fell through with no extra wait
        // and no banner, and just failed. Now we always give a slow-to-open
        // app more time; the banner only appears if/when a lock screen is
        // actually spotted DURING that wait, and its wording adapts if one
        // shows up partway through.
        if (!ok && launched) {
            var bannerShown = false
            val unlocked = run {
                val deadline = System.currentTimeMillis() + 25_000L
                while (System.currentTimeMillis() < deadline) {
                    if (ScreenContextEngine.isAppInForeground(packageName) && !ScreenContextEngine.looksLikeLockScreen()) {
                        return@run true
                    }
                    val secondsLeft = ((deadline - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
                    if (ScreenContextEngine.looksLikeLockScreen()) {
                        bannerShown = true
                        LockWaitBannerOverlay.show(context, "🔒 Waiting for you to unlock $packageName… (${secondsLeft}s)")
                    }
                    delay(500L)
                }
                false
            }
            if (bannerShown) LockWaitBannerOverlay.hide()
            result = when {
                unlocked && bannerShown -> ActionResult(true, "Opened $packageName (waited for you to unlock it).")
                unlocked -> ActionResult(true, "Opened $packageName (took a bit longer than usual).")
                bannerShown -> ActionResult(false, "$packageName is locked and wasn't unlocked in time — unlock it and try again.")
                else -> ActionResult(false, "Opened $packageName but couldn't confirm it's in the foreground after waiting.")
            }
        }

        log("openApp", packageName, result)
        return result
    }


    /** UP/DOWN scrolls whatever's currently scrollable on screen. LEFT/RIGHT not yet supported. */
    suspend fun navigate(direction: ScrollDirection): ActionResult {
        val result = when (direction) {
            ScrollDirection.UP, ScrollDirection.DOWN -> {
                val scrollableId = ScreenContextEngine.getCurrentContext().scrollableAreas.firstOrNull()
                if (scrollableId == null) ActionResult(false, "Nothing scrollable found on this screen.")
                else scroll(direction, scrollableId)
            }
            ScrollDirection.LEFT, ScrollDirection.RIGHT ->
                ActionResult(false, "Horizontal navigation isn't supported yet — only vertical scrolling.")
        }
        log("navigate", direction.name, result)
        return result
    }

    suspend fun waitForScreen(expectedText: String, timeoutMs: Long = 5000L): ActionResult {
        val found = ScreenContextEngine.waitForText(expectedText, timeoutMs)
        val result = if (found) ActionResult(true, "\"$expectedText\" appeared.")
        else ActionResult(false, "\"$expectedText\" never appeared within ${timeoutMs}ms.")
        log("waitForScreen", expectedText, result)
        return result
    }

    // ══════════════════════ SYSTEM-LEVEL ACTIONS ══════════════════════

    /**
     * WiFi can't be toggled programmatically on modern Android without
     * being a system app. Best-effort: open settings, try to tap the
     * toggle via Accessibility, then verify against the REAL state via
     * WifiManager.isWifiEnabled() (the getter still works even though the
     * setter is restricted). Heuristic — may need on-device tuning per OEM
     * settings UI (Samsung / Xiaomi / stock Android all differ).
     */
    suspend fun setWifi(context: Context, enabled: Boolean): ActionResult {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager?.isWifiEnabled == enabled) {
            val result = ActionResult(true, "WiFi already ${if (enabled) "on" else "off"}.")
            log("setWifi", enabled.toString(), result)
            return result
        }

        openSpecificSettings(context, SettingsType.WIFI)
        delay(800L)
        val svc = service()

        val (ok, attempts) = retryUntil { _ ->
            if (svc != null) {
                val node = svc.findNodeByText("Wi-Fi") ?: svc.findNodeByText("WiFi")
                node?.let { svc.tap(it).also { _ -> it.recycle() } }
                delay(500L)
            }
            wifiManager?.isWifiEnabled == enabled
        }
        val result = if (ok) ActionResult(true, "WiFi is now ${if (enabled) "on" else "off"}.")
        else ActionResult(false, "Opened WiFi settings but couldn't confirm the toggle changed after $attempts attempt(s) — please toggle it manually.")
        log("setWifi", enabled.toString(), result)
        return result
    }

    suspend fun setBluetooth(context: Context, enabled: Boolean): ActionResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            val result = ActionResult(false, "Bluetooth permission isn't granted — grant it in Permission Centre first.")
            log("setBluetooth", enabled.toString(), result)
            return result
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            val result = ActionResult(false, "This device doesn't seem to have Bluetooth.")
            log("setBluetooth", enabled.toString(), result)
            return result
        }
        val (ok, attempts) = retryUntil { _ ->
            try {
                if (enabled) adapter.enable() else adapter.disable()
            } catch (e: SecurityException) {
                // fall through — the state check below will reflect reality either way
            }
            delay(600L)
            adapter.isEnabled == enabled
        }
        val result = if (ok) ActionResult(true, "Bluetooth ${if (enabled) "enabled" else "disabled"}.")
        else ActionResult(false, "Couldn't confirm Bluetooth changed after $attempts attempt(s).")
        log("setBluetooth", enabled.toString(), result)
        return result
    }

    fun setBrightness(context: Context, level: Int): ActionResult {
        val clamped = level.coerceIn(0, 255)
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val result = ActionResult(false, "Need permission to change brightness — opened the settings page to grant it.")
            log("setBrightness", clamped.toString(), result)
            return result
        }
        val result = try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
            val actual = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (actual == clamped) ActionResult(true, "Brightness set to $clamped/255.")
            else ActionResult(false, "Tried to set brightness but the system reports $actual/255 instead.")
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change brightness: ${e.message}")
        }
        log("setBrightness", clamped.toString(), result)
        return result
    }

    fun setVolume(context: Context, stream: Int, level: Int): ActionResult {
        val result = try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(stream)
            val clamped = level.coerceIn(0, max)
            audioManager.setStreamVolume(stream, clamped, 0)
            val actual = audioManager.getStreamVolume(stream)
            if (actual == clamped) ActionResult(true, "Volume set to $clamped/$max.")
            else ActionResult(false, "Tried to set volume but the system reports $actual/$max instead.")
        } catch (e: SecurityException) {
            ActionResult(false, "Don't have permission to change this volume stream — likely needs Do Not Disturb access.")
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change volume: ${e.message}")
        }
        log("setVolume", "$stream:$level", result)
        return result
    }

    fun toggleFlashlight(context: Context, enabled: Boolean): ActionResult {
        val result = try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                ActionResult(false, "This device doesn't have a flashlight.")
            } else {
                cameraManager.setTorchMode(cameraId, enabled)
                ActionResult(true, "Flashlight ${if (enabled) "on" else "off"}.")
            }
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change flashlight: ${e.message}")
        }
        log("toggleFlashlight", enabled.toString(), result)
        return result
    }

    fun toggleDND(context: Context, enabled: Boolean): ActionResult {
        val result = try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult(false, "Need Do Not Disturb permission — opened the settings page to grant it.")
            } else {
                notificationManager.setInterruptionFilter(
                    if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                val actuallyOn = notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
                if (actuallyOn == enabled) ActionResult(true, "Do Not Disturb ${if (enabled) "on" else "off"}.")
                else ActionResult(false, "Tried to change Do Not Disturb but it didn't take effect.")
            }
        } catch (e: Exception) {
            ActionResult(false, "Couldn't change Do Not Disturb: ${e.message}")
        }
        log("toggleDND", enabled.toString(), result)
        return result
    }

    /** Android doesn't allow programmatic airplane mode toggling for non-system apps — open settings instead. */
    fun toggleAirplaneMode(context: Context): ActionResult {
        val opened = openSpecificSettings(context, SettingsType.AIRPLANE_MODE)
        val result = ActionResult(
            opened.success,
            "Opened Airplane Mode settings — please toggle it manually, Android doesn't allow apps to do this directly."
        )
        log("toggleAirplaneMode", "-", result)
        return result
    }

    fun openSpecificSettings(context: Context, type: SettingsType): ActionResult {
        val action = when (type) {
            SettingsType.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsType.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsType.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsType.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsType.DND -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            SettingsType.AIRPLANE_MODE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SettingsType.WRITE_SETTINGS_PERMISSION -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            SettingsType.APP_DETAILS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        val result = try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (type == SettingsType.WRITE_SETTINGS_PERMISSION || type == SettingsType.APP_DETAILS) {
                intent.data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            ActionResult(true, "Opened settings.")
        } catch (e: Exception) {
            ActionResult(false, "Couldn't open settings: ${e.message}")
        }
        log("openSpecificSettings", type.name, result)
        return result
    }
}
