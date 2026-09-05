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

        var id = -1L
        db.beginTransaction()
        try {
            db.delete("vpn_config", null, null)
            id = db.insertOrThrow("vpn_config", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
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
