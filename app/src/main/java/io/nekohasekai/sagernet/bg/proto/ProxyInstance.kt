package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        if (notTmp) Logs.d(safeConfigDiagnostics(config, 0))
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        Logs.d(safeConfigDiagnostics(config, pluginConfigs.size))
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        // Assign the looper synchronously so close() always observes it (no
        // launch/close race). GlobalScope matches the previous scope semantics:
        // runOnDefaultDispatcher was GlobalScope.launch(Dispatchers.Default), and
        // TrafficLooper.start() launches its own loop on this scope.
        looper = service?.let { TrafficLooper(it.data, GlobalScope) }
        looper?.start()
    }

    override fun close() {
        super.close()
        // Teardown is called on the main thread; the final traffic flush in looper.stop() does
        // synchronous DAO writes, so run the blocking body on a background dispatcher to keep it
        // off the UI thread (Plan 027 — main-thread-DB allowance removed).
        runBlocking(Dispatchers.Default) {
            looper?.stop()
            looper = null
        }
    }
}
