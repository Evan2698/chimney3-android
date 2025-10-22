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

    // 当前VPN状态，作为此Service进程内的"单一事实来源"
    private var currentStatus = "stopped_init"
    private var currentMessage = "Service initialized"

    // AIDL回调列表，用于管理所有注册的UI客户端
    private val callbacks = RemoteCallbackList<IVpnServiceCallback>()

    // AIDL接口的实现
    private val binder = object : IVpnService.Stub() {
        override fun getStatus(): String {
            // UI调用时，返回当前Service的真实状态
            return currentStatus
        }

        override fun registerCallback(callback: IVpnServiceCallback?) {
            callback?.let {
                callbacks.register(it)
                // [!! 优化 !!] 注册后立即将当前状态回传给这个新的客户端
                // 这样可以避免UI在绑定和收到第一次回调之间存在状态延迟
                try {
                    callback.onStatusChanged(currentStatus, currentMessage)
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
        updateStatusAndNotify("stopped_init", "Service initialized")
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
                    updateStatusAndNotify("stopped_error", "Config not found")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn()
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
        updateStatusAndNotify("destroyed", "Service destroyed")
        callbacks.kill()
    }

    private fun startVpn() {
        if (vpnThread != null) {
            Log.w(TAG, "VPN is already running, will not start again.")
            return
        }

        updateStatusAndNotify("connecting", "Connecting...")

        vpnThread = Thread {
            try {
                vpnInterface = configureVpn()
                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface.")
                    updateStatusAndNotify("stopped_error", "Failed to establish interface")
                    return@Thread
                }
                Log.i(TAG, "VPN interface established. Starting Chimney core...")

                val c = vpncore.Chimney().apply {
                    fd = vpnInterface!!.fd.toLong()
                    user = currentConfig?.user ?: ""
                    pass = currentConfig?.pass ?: ""
                    mtu = 1500
                    pfun = this@MyVpnService
                    tcpProxyUrl = currentConfig!!.tcpProxyUrl
                    udpProxyUrl = currentConfig!!.udpProxyUrl
                }

                // 核心库启动成功后，立即更新状态为 "connected"
                updateStatusAndNotify("connecting", "VPN interface prepared")
                Log.i(TAG, "Chimney core started. Waiting for it to exit...")

                // 此方法会阻塞，直到VPN断开
                Vpncore.startChimney(c)

                // 当 startChimney() 返回时，意味着VPN已停止。
                Log.i(TAG, "Chimney core has been running.")
                // 检查当前状态，如果仍然是connected或disconnecting，说明是核心库主动断开或被我们触发断开
                updateStatusAndNotify("connected", "connected")

                while (!Thread.interrupted()){
                    Thread.sleep(1000)
                }

            } catch (e: Exception) {
                if (e !is InterruptedException) {
                    Log.e(TAG, "VPN thread error", e)
                    updateStatusAndNotify("stopped_error", "VPN thread error: ${e.message}")
                }
            } finally {
                // 清理资源并重置线程变量
                vpnInterface?.close()
                vpnInterface = null
                vpnThread = null
                Log.i(TAG, "VPN thread finished.")
                updateStatusAndNotify("stopped", "Disconnected")
            }
        }.apply {
            name = "MyVpnThread"
            start()
        }
    }

    private fun stopVpn() {
        if (vpnThread == null && currentStatus != "connecting") {
            Log.d(TAG, "stopVpn() called but VPN is not in a running state.")
            // 如果已经是停止状态，可以强制通知一下，确保UI同步
            if (!currentStatus.startsWith("stopped")) {
                updateStatusAndNotify("stopped_user", "Disconnected")
            }
            return
        }

        updateStatusAndNotify("disconnecting", "Disconnecting...")

        // 调用核心库的停止方法，这会让 startChimney() 方法返回
        Vpncore.stopChimney()

        // 中断线程，以防它被其他操作阻塞
        vpnThread?.interrupt()
    }

    private fun configureVpn(): ParcelFileDescriptor? {
        val dns = currentConfig?.dnsAddress ?: "1.1.1.1"
        return Builder()
            .addAddress("10.8.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dns)
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .establish()
    }

    override fun protect(p0: Long): Long {
        return if (super.protect(p0.toInt())) 0 else -1
    }

    private fun updateStatusAndNotify(state: String, message: String) {
        // 优化：避免重复发送完全相同的状态和消息
        if (currentStatus == state && currentMessage == message) return

        currentStatus = state
        currentMessage = message

        // 通过AIDL回调机制通知所有已绑定的UI
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
