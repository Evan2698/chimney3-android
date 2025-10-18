package com.example.chimneyandroid


import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import vpncore.Vpncore

private const val TAG = "MyVpnService"

class MyVpnService : VpnService(), vpncore.Protect {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null


    private var currentConfig: VpnConfig? = null

    companion object {
        const val ACTION_CONNECT = "com.example.chimneyandroid.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.chimneyandroid.ACTION_DISCONNECT"

        const val BROADCAST_VPN_STATE = "com.example.chimneyandroid.VPN_STATE"
    }

    // VpnService被创建时调用
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created.")
    }

    // 每次通过 startService() 调用时执行
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
                    broadcastVpnState("stopped_error", "Config not found")
                    stopVpn() // 停止服务
                    return START_NOT_STICKY
                }
                Log.i(TAG, "Starting VPN with config: Proxy=${currentConfig?.tcpProxyUrl}, DNS=${currentConfig?.dnsAddress}")
                startVpn()
                START_STICKY // 如果服务被系统杀死，系统会尝试重启它
            }
            ACTION_DISCONNECT -> {
                Log.i(TAG, "Received DISCONNECT action.")
                stopVpn()
                broadcastVpnState("stopped_user", "Disconnected by user")
                START_NOT_STICKY // 服务停止后不应自动重启
            }
            else -> START_NOT_STICKY
        }
    }

    // VpnService被销毁时调用
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed.")
        stopVpn()
    }

    /**
     * 启动VPN隧道
     */
    private fun startVpn() {
        // 如果已有VPN线程在运行，先停止它
        stopVpn()

        // 启动一个新的线程来处理网络流量，避免阻塞主线程
        vpnThread = Thread {
            try {
                // 1. 配置并建立VPN隧道
                vpnInterface = configureVpn()

                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface.")
                    return@Thread
                }


                var c = vpncore.Chimney()
                c.fd = (vpnInterface!!.fd).toLong()
                c.fd = vpnInterface!!.fd.toLong()
                c.user = currentConfig?.user ?: ""
                c.pass = currentConfig?.pass ?: ""
                c.mtu = 1500
                c.pfun = this
                c.tcpProxyUrl = currentConfig!!.tcpProxyUrl
                c.udpProxyUrl = currentConfig!!.udpProxyUrl
                Vpncore.startChimney(c)

                Log.i(TAG, "VPN interface established.")
                // [!! 关键修改 !!] 在接口建立成功后，立即发送连接成功的广播
                broadcastVpnState("connected", "VPN Connected Successfully")

            } catch (e: Exception) {
                if (e !is InterruptedException) {
                    Log.e(TAG, "VPN thread error", e)
                }
            } finally {
                Log.i(TAG, "VPN thread stopped.")
            }
        }.apply {
            name = "MyVpnThread"
            start() // 启动线程
        }
    }



    /**
     * 停止VPN隧道
     */
    private fun stopVpn() {
        Vpncore.stopChimney()
        vpnThread?.interrupt()
        vpnThread = null
        vpnInterface?.close() // 关闭接口会中断正在进行的读写操作
        vpnInterface = null
    }

    /**
     * 配置并建立VPN隧道
     * @return 返回一个 ParcelFileDescriptor，代表建立的隧道接口
     */
    private fun configureVpn(): ParcelFileDescriptor? {

        var dns = "1.1.1.1"
        if (currentConfig?.dnsAddress != null) {
            dns = currentConfig?.dnsAddress!!
        }
        return Builder()
            // 1. 给VPN接口分配一个虚拟IP地址
            .addAddress("10.8.0.2", 24)
            // 2. 将所有流量 (0.0.0.0/0) 都路由到这个VPN接口
            .addRoute("0.0.0.0", 0)
            // 3. 设置DNS服务器，防止DNS泄漏

            .addDnsServer(dns)
            // 4. 设置会话名称，会显示在系统的VPN设置页面
            .setSession("MyVpnSession")

            .setMtu(1500)
            // 5. 建立隧道
            .establish()
    }

    override fun protect(p0: Long): Long {
        super<VpnService>.protect(p0.toInt())
        return p0
    }

    private fun broadcastVpnState(state: String, message: String) {
        val intent = Intent(BROADCAST_VPN_STATE).apply {
            putExtra("state", state)
            putExtra("message", message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
