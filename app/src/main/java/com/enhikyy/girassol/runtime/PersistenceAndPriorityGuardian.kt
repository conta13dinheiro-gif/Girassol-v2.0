package com.enhikyy.girassol.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/**
 * Cooperative runtime governor. It never pins CPU cores or requests battery-optimization
 * exemptions; ADPF is advisory and every wake-lock lease expires automatically.
 */
internal class PersistenceAndPriorityGuardian(context: Context, private val scheduler: Handler) : AutoCloseable {
    private val appContext = context.applicationContext
    private val power = requireNotNull(appContext.getSystemService(PowerManager::class.java))
    private val battery = appContext.getSystemService(BatteryManager::class.java)
    private val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Girassol:Biosphere")
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Girassol:LanIngest")
        } else null
    }.getOrNull()
    private val wifiRelease = Runnable { releaseLanWifiLease() }
    private var hintSession: PerformanceHintManager.Session? = null
    private var closed = false

    fun beginCycle(): Long {
        if (closed) return SystemClock.elapsedRealtimeNanos()
        if (hintSession == null) {
            hintSession = runCatching {
                appContext.getSystemService(PerformanceHintManager::class.java)
                    ?.createHintSession(intArrayOf(Process.myTid()), NORMAL_TARGET_NS)
            }.getOrNull()
        }
        renewWakeLock()
        return SystemClock.elapsedRealtimeNanos()
    }

    /** Refreshes a finite lease instead of holding a wake lock indefinitely. */
    fun renewWakeLock(timeoutMs: Long = WAKE_LEASE_MS) {
        if (closed) return
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire(timeoutMs.coerceIn(1_000L, MAX_WAKE_LEASE_MS))
        }
    }

    fun finishCycle(startedAtNanos: Long, thermalStatus: Int, memoryLow: Boolean, speed: Int): Long {
        val elapsed = (SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(1L)
        val batteryPercent = runCatching {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }.getOrDefault(-1)
        val batteryConstrained = power.isPowerSaveMode || batteryPercent in 0..15
        val thermallyConstrained = thermalStatus > PowerManager.THERMAL_STATUS_LIGHT
        val constrained = memoryLow || batteryConstrained || thermallyConstrained
        val target = when {
            memoryLow || thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> HOT_TARGET_NS
            batteryConstrained || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> WARM_TARGET_NS
            else -> NORMAL_TARGET_NS
        }
        runCatching {
            hintSession?.updateTargetWorkDuration(target)
            hintSession?.reportActualWorkDuration(elapsed)
        }
        return when {
            memoryLow || thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 2_000L
            batteryConstrained || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 1_000L
            constrained -> 750L
            else -> (500L / speed.coerceIn(1, 4)).coerceAtLeast(125L)
        }
    }

    /**
     * Optional LAN-ingestion lease. Call only while an mDNS/transfer session is active;
     * the scheduler releases it automatically even if the caller disappears.
     */
    @Synchronized
    fun acquireLanWifiLease(timeoutMs: Long = 60_000L): Boolean {
        if (closed || wifiLock == null) return false
        return runCatching {
            scheduler.removeCallbacks(wifiRelease)
            if (!wifiLock.isHeld) wifiLock.acquire()
            scheduler.postDelayed(wifiRelease, timeoutMs.coerceIn(1_000L, MAX_WIFI_LEASE_MS))
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun releaseLanWifiLease() {
        scheduler.removeCallbacks(wifiRelease)
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    fun releaseWakeLock() {
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseWakeLock()
        releaseLanWifiLease()
        runCatching { hintSession?.close() }
        hintSession = null
    }

    private companion object {
        const val WAKE_LEASE_MS = 60_000L
        const val MAX_WAKE_LEASE_MS = 120_000L
        const val MAX_WIFI_LEASE_MS = 120_000L
        const val NORMAL_TARGET_NS = 16_666_667L
        const val WARM_TARGET_NS = 33_333_334L
        const val HOT_TARGET_NS = 50_000_000L
    }
}
