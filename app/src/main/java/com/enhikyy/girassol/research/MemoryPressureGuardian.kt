package com.enhikyy.girassol.research

import android.app.ActivityManager
import android.content.*
import android.content.res.Configuration
import android.net.*
import android.os.*
import java.util.concurrent.CopyOnWriteArrayList

/** Live admission and cancellation shared by networking, indexing, review and experiments. */
internal class MemoryPressureGuardian(context: Context) : ResearchGate, AutoCloseable, ComponentCallbacks2 {
    private val app=context.applicationContext
    private val connectivity=app.getSystemService(ConnectivityManager::class.java)
    private val listeners=CopyOnWriteArrayList<()->Unit>()
    @Volatile var wifiOnly=true
    @Volatile var enabled=false
    @Volatile private var trimmed=false
    private val thermal=PowerManager.OnThermalStatusChangedListener { if(it>=2) revoke() }
    private val network=object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { revoke() }
        override fun onCapabilitiesChanged(network: Network,networkCapabilities: NetworkCapabilities) { if(!connected()) revoke() }
    }
    init { app.registerComponentCallbacks(this); connectivity.registerDefaultNetworkCallback(network); app.getSystemService(PowerManager::class.java).addThermalStatusListener(thermal) }
    fun onRevoked(listener: ()->Unit) { listeners+=listener }
    fun green(): Boolean {
        val m=ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return MemoryPressurePolicy.green(m.availMem,m.lowMemory,app.getSystemService(PowerManager::class.java).currentThermalStatus) && !trimmed
    }
    fun resume() { trimmed=false }
    private fun connected(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && (!wifiOnly || it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    } ?: false
    /** Shared CPU/ML admission that does not require network or auto-research to be enabled. */
    fun checkInference() {
        if(Thread.currentThread().isInterrupted) throw ResearchPaused("Inferência interrompida")
        if(!green()) throw ResearchPaused("Aguardando memória verde e temperatura adequada")
    }
    fun checkLocal() {
        checkInference()
        if(!enabled) throw ResearchPaused("Auto-pesquisa desativada")
    }
    override fun check() {
        checkLocal()
        if(!connected()) throw ResearchPaused(if(wifiOnly) "Aguardando Wi-Fi validado" else "Aguardando conexão")
    }
    private fun revoke() { listeners.forEach { it() } }
    override fun onTrimMemory(level: Int) { if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level!=ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) { trimmed=true; revoke() } }
    override fun onLowMemory() { trimmed=true; revoke() }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun close() { enabled=false; revoke(); app.unregisterComponentCallbacks(this); connectivity.unregisterNetworkCallback(network); app.getSystemService(PowerManager::class.java).removeThermalStatusListener(thermal) }
}
