package com.ericdevwang.inputbridge.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.ericdevwang.inputbridge.server.DEFAULT_SERVER_HOST
import com.ericdevwang.inputbridge.server.DEFAULT_SERVER_PORT
import com.ericdevwang.inputbridge.server.InputBridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class InputBridgeService : Service() {
    private val bridgeServer: InputBridgeServer by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        InputBridgeNotification.createChannel(this)
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            InputBridgeNotification.NOTIFICATION_ID,
            InputBridgeNotification.build(this),
            foregroundServiceType,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupJob?.isActive != true) {
            startupJob = serviceScope.launch { startServerWithRetries() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        startupJob?.cancel()
        serviceScope.launch {
            try {
                bridgeServer.stop()
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun startServerWithRetries() {
        val started = ServerStartupRetry(
            start = bridgeServer::start,
            wait = { millis -> delay(millis) },
            onFailure = { attempt, error ->
                Log.e(TAG, "TCP server start failed, attempt=$attempt", error)
            },
        ).run()
        if (started) {
            Log.i(TAG, "TCP server started on $DEFAULT_SERVER_HOST:$DEFAULT_SERVER_PORT")
        }
    }

    private companion object {
        const val TAG = "InputBridgeService"
    }
}
