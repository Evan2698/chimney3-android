package com.example.chimneyandroid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnStatus(val status: String, val message: String)
// 创建一个单例对象来持有全局状态
object VpnStateHolder {
    // 使用MutableStateFlow，它可以在值改变时通知观察者
    private val _vpnStatus = MutableStateFlow(VpnStatus("stopped_init", "Initializing..."))

    // 对外暴露一个只读的StateFlow
    val vpnStatus = _vpnStatus.asStateFlow()

    // 提供一个更新状态的方法
    fun updateStatus(newStatus: VpnStatus) {
        _vpnStatus.value = newStatus
    }
}