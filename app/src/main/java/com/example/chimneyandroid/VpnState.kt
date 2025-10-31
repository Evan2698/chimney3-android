package com.example.chimneyandroid

enum class VpnState {
    IDLE,          // 初始或空闲状态
    INITIALIZED,   // 初始化完成
    CONNECTING,    // 正在连接
    CONNECTED,     // 已经连接
    DISCONNECTING, // 正在断开
    STOPPED,       // 已停止（由用户、错误或系统触发）
    INVALID_CONFIG, // 配置无效
    ERROR           // 错误
}