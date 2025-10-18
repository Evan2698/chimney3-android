package com.example.chimneyandroid

import android.app.Activity
import android.content.*
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.chimneyandroid.databinding.FragmentVpnBinding

private const val TAG = "VPNFragment"

class VPNFragment : Fragment() {

    private var _binding: FragmentVpnBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataSource: VpnConfigDataSource

    // 用于处理来自 MyVpnService 的状态更新广播
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("state")) {
                "connected" -> {
                    updateVpnStatus(intent.getStringExtra("message") ?: "Connected")
                    binding.connectButton.text = "Disconnect VPN"
                }
                "stopped_error", "stopped_user" -> {
                    updateVpnStatus(intent.getStringExtra("message") ?: "Disconnected")
                    binding.connectButton.text = "Connect VPN"
                }
            }
        }
    }

    // 用于处理VPN权限请求的结果
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

        binding.saveButton.setOnClickListener {
            saveConfigWithValidation()
        }

        binding.connectButton.setOnClickListener {
            if (binding.connectButton.text.toString().contains("Connect")) {
                prepareAndStartVpn()
            } else {
                stopVpnService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册广播接收器
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            vpnStateReceiver,
            IntentFilter(MyVpnService.BROADCAST_VPN_STATE)
        )
    }

    override fun onPause() {
        super.onPause()
        // 注销广播接收器，防止内存泄漏
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(vpnStateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // 避免内存泄漏
    }

    /**
     * 保存配置前进行输入验证
     */
    private fun saveConfigWithValidation() {
        val tcpProxyUrl = binding.tcpProxyUrl.text.toString().trim()
        val udpProxyUrl = binding.udpProxyUrl.text.toString().trim()
        val dnsAddress = binding.dnsAddress.text.toString().trim()
        val user = binding.user.text.toString().trim()
        val pass = binding.pass.text.toString().trim()

        // 用户名和密码为可选，但其他项为必填
        if (tcpProxyUrl.isEmpty() || udpProxyUrl.isEmpty() || dnsAddress.isEmpty()) {
            Toast.makeText(context, "TCP/UDP Proxy URL and DNS Address are required.", Toast.LENGTH_LONG).show()
            return
        }

        val vpnConfig = VpnConfig(
            id = 0,
            tcpProxyUrl = tcpProxyUrl,
            udpProxyUrl = udpProxyUrl,
            dnsAddress = dnsAddress,
            user = user,
            pass = pass
        )
        dataSource.saveVpnConfig(vpnConfig)
        Toast.makeText(context, "Config saved successfully.", Toast.LENGTH_SHORT).show()
    }

    /**
     * 加载并显示已保存的配置字段到UI
     */
    private fun loadVpnConfig() {
        val vpnConfig = dataSource.getVpnConfig()
        vpnConfig?.let {
            binding.tcpProxyUrl.setText(it.tcpProxyUrl)
            binding.udpProxyUrl.setText(it.udpProxyUrl)
            binding.dnsAddress.setText(it.dnsAddress)
            binding.user.setText(it.user)
            binding.pass.setText(it.pass)
        }
    }

    /**
     * 准备并启动VPN，增加了数据库配置检查
     */
    private fun prepareAndStartVpn() {
        val vpnConfig = dataSource.getVpnConfig()
        if (vpnConfig == null) {
            Toast.makeText(context, "Server information is incomplete. Please save the configuration first.", Toast.LENGTH_LONG).show()
            updateVpnStatus("Config not found")
            return
        }
        // 用户名密码为可选，但代理地址和DNS为必填
        if (vpnConfig.tcpProxyUrl.isEmpty() || vpnConfig.udpProxyUrl.isEmpty() || vpnConfig.dnsAddress.isEmpty()) {
            Toast.makeText(context, "Server information is incomplete. Please save the configuration first.", Toast.LENGTH_LONG).show()
            updateVpnStatus("Config not found")
            return
        }

        // 检查VPN权限
        val vpnPrepareIntent = VpnService.prepare(context)
        if (vpnPrepareIntent != null) {
            // 没有权限，启动系统对话框请求权限
            requestVpnPermission.launch(vpnPrepareIntent)
        } else {
            // 已有权限，直接启动服务
            startVpnService()
        }
    }

    /**
     * 发送 "CONNECT" action 的 Intent 来启动 MyVpnService。
     */
    private fun startVpnService() {
        val vpnConfig = dataSource.getVpnConfig() ?: return // 再次检查以防万一
        updateVpnStatus("Connecting...")
        binding.connectButton.text = "Disconnect VPN"

        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra("vpn_config", vpnConfig) // 将配置信息传递给服务
        }
        requireContext().startService(intent)
    }

    /**
     * 发送 "DISCONNECT" action 的 Intent 来停止 MyVpnService。
     */
    private fun stopVpnService() {
        updateVpnStatus("Disconnecting...")
        binding.connectButton.text = "Connect VPN" // 乐观地更新UI

        // 停止服务只需要发送一个简单的action
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        requireContext().startService(intent)
    }

    /**
     * 更新界面上的VPN状态文本
     */
    private fun updateVpnStatus(status: String) {
        binding.vpnStatus.text = "VPN Status: $status"
    }
}
