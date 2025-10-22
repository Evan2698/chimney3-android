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

    // 当前VPN状态
    private var currentStatus = "stopped_init"
    private var currentMessage = "Service initialized"

    // AIDL回调列表，用于管理所有注册的UI客户端
    private val callbacks = RemoteCallbackList<IVpnServiceCallback>()

    // AIDL接口的实现
    private val binder = object : IVpnService.Stub() {
        override fun getStatus(): String {
            // UI调用时，返回当前Service的状态
            return currentStatus
        }

        override fun registerCallback(callback: IVpnServiceCallback?) {
            callback?.let { callbacks.register(it) }
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
        updateStatusAndNotify("stopped_init", "Service initialized")
    }

    // 在onBind中返回正确的IBinder
    override fun onBind(intent: Intent): IBinder? {
        // 当系统绑定VpnService时，必须返回super.onBind()或null
        if (VpnService.SERVICE_INTERFACE == intent.action) {
            return super.onBind(intent)
        }
        // 当我们自己的UI（Fragment）绑定时，返回AIDL接口的实现
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CONNECT -> {
                Log.i(TAG, "Received CONNECT action.")
                currentConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("vpn_config", VpnConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("vpn_config")
                }
                if (currentConfig == null) {
                    Log.e(TAG, "Failed to get VpnConfig from Intent. Stopping service.")
                    updateStatusAndNotify("stopped_error", "Config not found")
                    stopVpn()
                    return START_NOT_STICKY
                }
                Log.i(TAG, "Starting VPN with config: Proxy=${currentConfig?.tcpProxyUrl}, DNS=${currentConfig?.dnsAddress}")
                startVpn()
                START_STICKY
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Received DISCONNECT action.")
                stopVpn()
                updateStatusAndNotify("stopped_user", "Disconnected by user")
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed.")
        stopVpn()
        updateStatusAndNotify("destroyed", "Service destroyed")
        callbacks.kill()
    }

    private fun startVpn() {
        stopVpn()
        updateStatusAndNotify("connecting", "Connecting...")
        vpnThread = Thread {
            try {
                updateStatusAndNotify("connecting", "Connecting...")
                vpnInterface = configureVpn()
                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface.")
                    updateStatusAndNotify("stopped_error", "Failed to establish interface")
                    return@Thread
                }
                val c = vpncore.Chimney().apply {
                    fd = vpnInterface!!.fd.toLong()
                    user = currentConfig?.user ?: ""
                    pass = currentConfig?.pass ?: ""
                    mtu = 1500
                    pfun = this@MyVpnService
                    tcpProxyUrl = currentConfig!!.tcpProxyUrl
                    udpProxyUrl = currentConfig!!.udpProxyUrl
                }

                Vpncore.startChimney(c)

                updateStatusAndNotify("connected", "VPN Connected Successfully")

            } catch (e: Exception) {
                if (e !is InterruptedException) {
                    Log.e(TAG, "VPN thread error", e)
                    updateStatusAndNotify("stopped_error", "VPN thread error: ${e.message}")
                }
                Log.i(TAG, "VPN thread stopped or interrupted.")
            }
        }.apply {
            name = "MyVpnThread"
            start()
        }
    }

    private fun stopVpn() {
        Vpncore.stopChimney()
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close()
        vpnInterface = null
        updateStatusAndNotify("destroyed", "Service destroyed")
        Log.i(TAG, "VPN Service destroyed.")
    }

    private fun configureVpn(): ParcelFileDescriptor? {
        val dns = currentConfig?.dnsAddress ?: "1.1.1.1"
        return Builder()
            .addAddress("10.8.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dns)
            .setSession(getString(R.string.app_name)) // 使用应用名作为会话名
            .setMtu(1500)
            .establish()
    }

    override fun protect(p0: Long): Long {
        return if (super.protect(p0.toInt())) 0 else -1
    }

    // 核心修改：替换 broadcastVpnState 方法
    private fun updateStatusAndNotify(state: String, message: String) {
        currentStatus = state
        currentMessage = message

        // [!! 核心修改 !!] 更新全局状态持有者
        VpnStateHolder.updateStatus(VpnStatus(state, message))

        // AIDL回调机制保持不变，用于实时通知已绑定的UI
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            try {
                callbacks.getBroadcastItem(i).onStatusChanged(state, message)
            } catch (e: RemoteException) {
                // The client is dead. RemoteCallbackList will remove it.
            }
        }
        callbacks.finishBroadcast()
        Log.d(TAG, "Notified status: $state, message: $message")
    }
}
