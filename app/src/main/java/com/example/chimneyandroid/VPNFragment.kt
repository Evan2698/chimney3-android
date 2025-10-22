package com.example.chimneyandroid

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.chimneyandroid.databinding.FragmentVpnBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "VPNFragment"

class VPNFragment : Fragment() {

    private var _binding: FragmentVpnBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataSource: VpnConfigDataSource

    // AIDL接口的客户端代理
    private var vpnService: IVpnService? = null
    private var isServiceBound = false

    // 核心修改: 使用 ServiceConnection 来处理与远程Service的连接
    // ServiceConnection 现在变得更简单，主要用于检查Service是否在线
    // 我们不再需要在这里注册/注销回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected. UI is driven by StateFlow.")
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected.")
            isServiceBound = false
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
            if (binding.connectButton.text.toString().contains("Connect")) {
                prepareAndStartVpn()
            } else {
                stopVpnService()
            }
        }
        // [!! 核心修改 !!] 在这里开始观察全局状态
        observeVpnState()
    }

    private fun observeVpnState() {
        // 使用 viewLifecycleOwner.lifecycleScope 来确保协程在视图销毁时自动取消
        viewLifecycleOwner.lifecycleScope.launch {
            VpnStateHolder.vpnStatus.collectLatest { vpnStatus ->
                // 每当全局状态更新，这里就会被调用
                updateUiByStatus(vpnStatus.status, vpnStatus.message)
                Log.d(TAG, vpnStatus.status + vpnStatus.message)
            }
        }
    }

    // 核心修改: 使用 onStart 和 onStop 来管理Service的绑定/解绑
    override fun onStart() {
        super.onStart()
        // 绑定到远程服务
        Intent(context, MyVpnService::class.java).also { intent ->
            activity?.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            activity?.unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // 新增一个统一的UI更新方法
    private fun updateUiByStatus(status: String, message: String) {
        if (_binding == null) return // 避免Fragment View销毁后更新UI
        updateVpnStatus(message)
        when (status) {
            "connecting", "connected" -> {
                binding.connectButton.text = getString(R.string.disconnect_vpn)
            }
            "stopped_init", "stopped_user", "stopped_error", "destroyed", "disconnected" -> {
                binding.connectButton.text = getString(R.string.connect_vpn)
            }
        }
    }

    // --- 以下方法无需重大修改 ---

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
        updateUiByStatus("connecting", "Connecting...")

        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra("vpn_config", vpnConfig)
        }
        requireContext().startService(intent)
    }

    private fun stopVpnService() {
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
