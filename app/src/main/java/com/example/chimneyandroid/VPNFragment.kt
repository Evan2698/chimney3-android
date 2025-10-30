package com.example.chimneyandroid

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.chimneyandroid.databinding.FragmentVpnBinding

private const val TAG = "VPNFragment"

class VPNFragment : Fragment() {

    private var _binding: FragmentVpnBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataSource: VpnConfigDataSource

    // AIDL接口的客户端代理
    private var vpnService: IVpnService? = null
    private var isServiceBound = false

    // ServiceConnection现在是状态同步的关键
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected.")
            // 服务绑定成功，获取AIDL接口的代理
            vpnService = IVpnService.Stub.asInterface(service)
            isServiceBound = true

            try {
                // 关键：注册回调，以便接收后续的实时状态更新。
                // 优化后的Service会在注册后立即回传一次当前状态。
                vpnService?.registerCallback(vpnServiceCallback)
            } catch (e: RemoteException) {
                Log.e(TAG, "onServiceConnected failed while registering callback", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service has unexpectedly disconnected.")
            // 服务意外断开（例如Service进程崩溃）
            isServiceBound = false
            vpnService = null
            // 更新UI到一个明确的断开状态
            updateUiByStatus("disconnected", "Service Disconnected")
        }
    }

    // AIDL回调的实现，用于接收来自Service的通知
    private val vpnServiceCallback = object : IVpnServiceCallback.Stub() {
        override fun onStatusChanged(status: String?, message: String?) {
            // 这个方法是在Binder线程中被调用的，更新UI必须切换到主线程
            activity?.runOnUiThread {
                if (status != null && message != null) {
                    Log.d(TAG, "Callback received: status=$status, message=$message")
                    updateUiByStatus(status, message)
                }
            }
        }
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "VPN permission granted, starting service.")
                startVpnService()
            } else {
                Log.w(TAG, "User declined VPN permission.")
                updateVpnStatus("Permission Denied")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVpnBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataSource = VpnConfigDataSource(requireContext())
        loadVpnConfig()

        binding.saveButton.setOnClickListener { saveConfigWithValidation() }

        binding.connectButton.setOnClickListener {
            if (VpnStateHolder.getStatus() != "connected") {
                prepareAndStartVpn()
            } else {
                stopVpnService()
            }
        }
    }

    // 在 onStart 和 onStop 中正确地管理绑定、解绑和回调注册
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Binding to service...")
        // 绑定到远程服务
        Intent(context, MyVpnService::class.java).also { intent ->
            activity?.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Unbinding from service...")
        if (isServiceBound) {
            try {
                // 关键: 在解绑前，必须先注销回调
                vpnService?.unregisterCallback(vpnServiceCallback)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to unregister callback", e)
            }
            // 解绑服务
            activity?.unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // 统一的UI更新方法
    private fun updateUiByStatus(status: String, message: String) {
        if (_binding == null) return // 避免Fragment View销毁后更新UI
        updateVpnStatus(message)
        VpnStateHolder.updateStatus(VpnStatus(status, message))
        when (status) {
            "connecting", "connected" -> {
                binding.connectButton.text = getString(R.string.disconnect_vpn)
            }
            "disconnecting", "stopped_init", "stopped_user", "stopped_error", "destroyed", "disconnected" -> {
                binding.connectButton.text = getString(R.string.connect_vpn)
            }
        }
    }

    // --- 以下方法无需修改 ---

    private fun saveConfigWithValidation() {
        val tcpProxyUrl = binding.tcpProxyUrl.text.toString().trim()
        val udpProxyUrl = binding.udpProxyUrl.text.toString().trim()
        val dnsAddress = binding.dnsAddress.text.toString().trim()
        val user = binding.user.text.toString().trim()
        val pass = binding.pass.text.toString().trim()

        if (tcpProxyUrl.isEmpty() || udpProxyUrl.isEmpty() || dnsAddress.isEmpty()) {
            Toast.makeText(context, "TCP/UDP Proxy URL and DNS Address are required.", Toast.LENGTH_LONG).show()
            return
        }
        val vpnConfig = VpnConfig(0, tcpProxyUrl, udpProxyUrl, dnsAddress, user, pass)
        dataSource.saveVpnConfig(vpnConfig)
        Toast.makeText(context, "Config saved successfully.", Toast.LENGTH_SHORT).show()
    }

    private fun loadVpnConfig() {
        dataSource.getVpnConfig()?.let {
            binding.tcpProxyUrl.setText(it.tcpProxyUrl)
            binding.udpProxyUrl.setText(it.udpProxyUrl)
            binding.dnsAddress.setText(it.dnsAddress)
            binding.user.setText(it.user)
            binding.pass.setText(it.pass)
        }
    }

    private fun prepareAndStartVpn() {
        val vpnConfig = dataSource.getVpnConfig()
        if (vpnConfig == null || vpnConfig.tcpProxyUrl.isEmpty() || vpnConfig.udpProxyUrl.isEmpty() || vpnConfig.dnsAddress.isEmpty()) {
            Toast.makeText(context, "Server information is incomplete. Please save the configuration first.", Toast.LENGTH_LONG).show()
            updateVpnStatus("Config not found")
            return
        }
        val vpnPrepareIntent = VpnService.prepare(context)
        if (vpnPrepareIntent != null) {
            requestVpnPermission.launch(vpnPrepareIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val vpnConfig = dataSource.getVpnConfig() ?: return
        // 乐观地更新UI，Service的回调会很快覆盖它
        updateUiByStatus("connecting", "Connecting...")

        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra("vpn_config", vpnConfig)
        }
        // 使用 startService 来确保Service在后台持续运行
        requireContext().startService(intent)
    }

    private fun stopVpnService() {
        // 乐观地更新UI
        updateUiByStatus("disconnecting", "Disconnecting...")

        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        requireContext().startService(intent)
    }

    private fun updateVpnStatus(status: String) {
        if (_binding == null) return
        binding.vpnStatus.text = "VPN Status: $status"
    }
}
