package com.mend.app.alarms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether this phone will actually let Mend arrive on time.
 *
 * Every one of these can silently be off, and each failure looks identical from
 * the user's side: nothing happens. Since the whole premise is an app worth
 * obeying, "did not fire" has to be diagnosable from inside the app rather than
 * discovered a week later.
 *
 * The OEM part is the ugly bit. Several Android manufacturers — Xiaomi, Oppo,
 * vivo, OnePlus, Huawei, Samsung to a lesser degree — run their own background
 * process killers on top of Doze, which ignore the standard exemptions. There
 * is no API to detect or request this; it can only be pointed at.
 */
data class ReliabilityStatus(
    val notificationsEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val ignoringBatteryOptimisation: Boolean,
    val manufacturer: String,
    val oemGuidance: OemGuidance?,
) {
    /** True when nothing stands between a scheduled alarm and the user. */
    val isFullyReliable: Boolean
        get() = notificationsEnabled && exactAlarmsAllowed && ignoringBatteryOptimisation

    /** The things still standing in the way, worst first. */
    val problems: List<String>
        get() = buildList {
            if (!notificationsEnabled) {
                add("Notifications are blocked, so nothing can reach you at all.")
            }
            if (!exactAlarmsAllowed) {
                add(
                    "Exact alarms are not allowed, so reminders may arrive minutes " +
                        "late — or during Doze, not until the phone is next used.",
                )
            }
            if (!ignoringBatteryOptimisation) {
                add(
                    "Battery optimisation is on for Mend, which lets the system " +
                        "delay or drop background work.",
                )
            }
        }

    companion object {
        fun of(context: Context, scheduler: AlarmScheduler): ReliabilityStatus {
            val power = context.getSystemService(PowerManager::class.java)
            val manufacturer = Build.MANUFACTURER.lowercase()

            return ReliabilityStatus(
                notificationsEnabled = MendNotifications.areEnabled(context),
                exactAlarmsAllowed = scheduler.canScheduleExact(),
                ignoringBatteryOptimisation = power
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: false,
                manufacturer = Build.MANUFACTURER,
                oemGuidance = OemGuidance.forManufacturer(manufacturer),
            )
        }
    }
}

/**
 * What to do about a manufacturer that kills background apps regardless.
 *
 * There is no programmatic way to detect whether these settings are active, or
 * to request them. All the app can do is know that this phone has such a
 * setting, say where it lives, and be honest that the standard Android
 * exemptions are not sufficient here.
 */
data class OemGuidance(
    val vendor: String,
    val whatHappens: String,
    val whatToDo: String,
    /** A settings screen that gets close, if one is reachable. */
    val settingsIntent: Intent?,
) {
    companion object {

        fun forManufacturer(manufacturer: String): OemGuidance? = when {
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> OemGuidance(
                vendor = "vivo / iQOO",
                whatHappens = "Funtouch OS closes background apps aggressively and " +
                    "resets them on reboot, even when Android's own battery " +
                    "exemption is granted.",
                whatToDo = "Battery → Background power consumption management → Mend → " +
                    "Allow background power consumption. Also switch on " +
                    "Settings → More settings → Permission management → " +
                    "Autostart for Mend.",
                settingsIntent = componentIntent(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
                ),
            )

            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> OemGuidance(
                vendor = "Xiaomi / Redmi / POCO",
                whatHappens = "MIUI restricts background activity and blocks autostart " +
                    "by default.",
                whatToDo = "Security app → Permissions → Autostart → enable Mend. " +
                    "Then Settings → Apps → Mend → Battery saver → No restrictions.",
                settingsIntent = componentIntent(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )

            manufacturer.contains("oppo") || manufacturer.contains("realme") ||
                manufacturer.contains("oneplus") -> OemGuidance(
                vendor = "OPPO / realme / OnePlus",
                whatHappens = "ColorOS and OxygenOS freeze background apps and clear " +
                    "their alarms.",
                whatToDo = "Settings → Battery → Mend → allow background running, and " +
                    "enable Auto-launch in the Phone Manager app.",
                settingsIntent = componentIntent(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
            )

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                OemGuidance(
                    vendor = "Huawei / Honor",
                    whatHappens = "EMUI's Phone Manager closes apps it considers " +
                        "inactive and undoes battery exemptions on its own.",
                    whatToDo = "Phone Manager → Battery → App launch → Mend → manage " +
                        "manually, with all three switches on.",
                    settingsIntent = componentIntent(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                )

            manufacturer.contains("samsung") -> OemGuidance(
                vendor = "Samsung",
                whatHappens = "One UI puts unused apps to sleep, which stops their " +
                    "alarms without warning.",
                whatToDo = "Settings → Battery → Background usage limits → make sure " +
                    "Mend is not in \"Sleeping apps\", and turn off " +
                    "\"Put unused apps to sleep\".",
                settingsIntent = null,
            )

            else -> null
        }

        /**
         * An intent at a vendor settings screen.
         *
         * These are undocumented and change between OS versions, so it is only
         * offered as a shortcut — resolvability is checked before it is shown,
         * and the written instructions stand on their own.
         */
        private fun componentIntent(pkg: String, cls: String): Intent =
            Intent().setClassName(pkg, cls)
    }
}

/** The standard Android battery-exemption request. */
fun batteryOptimisationIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))

/** The app's own notification settings. */
fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
