package com.example.chimneyandroid

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns

class VpnConfigDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val sqlCreateTable = """
            CREATE TABLE vpn_config (
                ${BaseColumns._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                tcp_proxy_url TEXT NOT NULL,
                udp_proxy_url TEXT NOT NULL,
                dns_address TEXT NOT NULL,
                user TEXT NOT NULL,
                pass TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(sqlCreateTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS vpn_config")
        onCreate(db)
    }

    companion object {
        // [!! 重要 !!] 数据库版本号必须增加，例如从 3 变为 4
        const val DATABASE_VERSION = 4
        const val DATABASE_NAME = "VpnConfig.db"
    }
}
