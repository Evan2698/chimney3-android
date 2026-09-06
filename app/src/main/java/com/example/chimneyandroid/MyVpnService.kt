package com.example.chimneyandroid

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import vpncore.Vpncore

private const val TAG = "MyVpnService"

class MyVpnService : VpnService(), vpncore.Protect {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var currentConfig: VpnConfig? = null
    private var stopRequested = false
    private var coreStarted = false
    private val stateLock = Any()

    // 当前VPN状态，作为此Service进程内的"单一事实来源"
    @Volatile
    private var currentState: VpnState = VpnState.IDLE
    @Volatile
    private var currentMessage = "Service initialized"

    // AIDL回调列表，用于管理所有注册的UI客户端
    private val callbacks = RemoteCallbackList<IVpnServiceCallback>()

    // AIDL接口的实现
    private val binder = object : IVpnService.Stub() {
        override fun getStatus(): String {
            // UI调用时，返回当前Service的真实状态
            return currentState.name
        }

        override fun disconnect() {
            stopVpn()
        }

        override fun registerCallback(callback: IVpnServiceCallback?) {
            callback?.let {
                callbacks.register(it)
                // [!! 优化 !!] 注册后立即将当前状态回传给这个新的客户端
                // 这样可以避免UI在绑定和收到第一次回调之间存在状态延迟
                try {
                    callback.onStatusChanged(currentState.name, currentMessage)
                } catch (e: RemoteException) {
                    // 客户端可能在注册后立即死掉
                }
            }
        }

        override fun unregisterCallback(callback: IVpnServiceCallback?) {
            callback?.let { callbacks.unregister(it) }
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.example.chimneyandroid.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.chimneyandroid.ACTION_DISCONNECT"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created in process ${android.os.Process.myPid()}.")
        // 初始化状态
        updateStatusAndNotify(VpnState.IDLE, "Service initialized")
    }

    override fun onBind(intent: Intent): IBinder? {
        if (VpnService.SERVICE_INTERFACE == intent.action) {
            return super.onBind(intent)
        }
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "Received CONNECT action.")
                currentConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("vpn_config", VpnConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("vpn_config")
                }

                if (currentConfig == null) {
                    Log.e(TAG, "Failed to get VpnConfig from Intent.")
                    updateStatusAndNotify(VpnState.INVALID_CONFIG, "Config not found")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(currentConfig!!)
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Received DISCONNECT action.")
                stopVpn()
                // 状态更新已移至stopVpn内部，以提供更及时的反馈
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed.")
        stopVpn()
        callbacks.kill()
    }

    private fun startVpn(config: VpnConfig) {
        synchronized(stateLock) {
            if (vpnThread != null || currentState == VpnState.CONNECTING || currentState == VpnState.CONNECTED) {
                Log.w(TAG, "VPN is already running, will not start again.")
                return
            }
            stopRequested = false
            coreStarted = false
            currentConfig = config
            updateStatusAndNotify(VpnState.CONNECTING, "Connecting...")
            vpnThread = Thread { runVpn(config) }.apply {
                name = "MyVpnThread"
                start()
            }
        }
    }

    private fun runVpn(config: VpnConfig) {
        try {
            vpnInterface = configureVpn(config)
                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface.")
                    updateStatusAndNotify(VpnState.ERROR, "Failed to establish interface")
                    return
                }
                Log.i(TAG, "VPN interface established. Starting Chimney core...")

                val c = vpncore.Chimney().apply {
                    fd = vpnInterface!!.fd.toLong()
                    user = config.user
                    pass = config.pass
                    mtu = 1500
                    pfun = this@MyVpnService
                    tcpProxyUrl = config.tcpProxyUrl
                    udpProxyUrl = config.udpProxyUrl
                }

                synchronized(stateLock) {
                    if (stopRequested) return
                    coreStarted = true
                }
                updateStatusAndNotify(VpnState.CONNECTED, "Connected")
                Vpncore.startChimney(c)
                Log.i(TAG, "Chimney core started.")

                // vpn.aar returns after starting its native worker.
                // Keep the VPN interface alive until an explicit disconnect.
                while (true) {
                    Thread.sleep(1000)
                    synchronized(stateLock) {
                        if (stopRequested) break
                    }
                }
        } catch (e: Exception) {
            if (!stopRequested && e !is InterruptedException) {
                Log.e(TAG, "VPN thread error", e)
                updateStatusAndNotify(VpnState.ERROR, "VPN error: ${e.message ?: "Unknown error"}")
            }
        } finally {
            vpnInterface?.close()
            vpnInterface = null
            synchronized(stateLock) {
                coreStarted = false
                vpnThread = null
            }
            Log.i(TAG, "VPN thread finished.")
            updateStatusAndNotify(VpnState.STOPPED, "Disconnected")
        }
    }

    private fun stopVpn() {
        val shouldStopCore: Boolean
        synchronized(stateLock) {
            if (vpnThread == null) {
                Log.d(TAG, "stopVpn() called but VPN is not running.")
                if (currentState != VpnState.STOPPED) {
                    updateStatusAndNotify(VpnState.STOPPED, "Disconnected")
                }
                return
            }
            stopRequested = true
            shouldStopCore = coreStarted
            updateStatusAndNotify(VpnState.DISCONNECTING, "Disconnecting...")
        }

        if (shouldStopCore) {
            Vpncore.stopChimney()
        }
        vpnThread?.interrupt()
    }

    private fun configureVpn(config: VpnConfig): ParcelFileDescriptor? {
        return Builder()
            .addAddress("10.8.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(config.dnsAddress)
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .establish()
    }

    override fun protect(p0: Long): Long {
        return if (super.protect(p0.toInt())) 0 else -1
    }

    private fun updateStatusAndNotify(state: VpnState, message: String) {
        currentState = state

        currentMessage = message

        // 通过AIDL回调机制通知所有已绑定的UI
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                callbacks.getBroadcastItem(i).onStatusChanged(state.name, message)
            } catch (e: RemoteException) {
                // The client is dead. RemoteCallbackList will remove it.
            }
        }
        callbacks.finishBroadcast()
        Log.d(TAG, "Notified status: $state, message: $message")
    }
}
