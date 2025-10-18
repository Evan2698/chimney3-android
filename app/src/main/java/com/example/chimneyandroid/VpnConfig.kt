package com.example.chimneyandroid

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VpnConfig(
    val id: Long,
    val tcpProxyUrl: String,
    val udpProxyUrl: String,
    val dnsAddress: String,
    val user: String, // [!! 新增 !!]
    val pass: String  // [!! 新增 !!]
) : Parcelable