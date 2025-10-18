package com.example.chimneyandroid

import android.content.ContentValues
import android.content.Context
import android.provider.BaseColumns

class VpnConfigDataSource(context: Context) {

    private val dbHelper = VpnConfigDbHelper(context)

    fun saveVpnConfig(vpnConfig: VpnConfig): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("tcp_proxy_url", vpnConfig.tcpProxyUrl)
            put("udp_proxy_url", vpnConfig.udpProxyUrl)
            put("dns_address", vpnConfig.dnsAddress)
            put("user", vpnConfig.user)
            put("pass", vpnConfig.pass)
        }

        val rowsAffected = db.update("vpn_config", values, null, null)

        return if (rowsAffected > 0) {
            getVpnConfig()?.id ?: -1L
        } else {
            db.insert("vpn_config", null, values)
        }
    }

    fun getVpnConfig(): VpnConfig? {
        val db = dbHelper.readableDatabase
        db.query("vpn_config", null, null, null, null, null, null, "1")
            ?.use { cursor ->
                return if (cursor.moveToFirst()) {
                    VpnConfig(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID)),
                        tcpProxyUrl = cursor.getString(cursor.getColumnIndexOrThrow("tcp_proxy_url")),
                        udpProxyUrl = cursor.getString(cursor.getColumnIndexOrThrow("udp_proxy_url")),
                        dnsAddress = cursor.getString(cursor.getColumnIndexOrThrow("dns_address")),
                        user = cursor.getString(cursor.getColumnIndexOrThrow("user")),
                        pass = cursor.getString(cursor.getColumnIndexOrThrow("pass"))
                    )
                } else {
                    null
                }
            }
        return null
    }
}
