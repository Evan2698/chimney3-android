//aidl
package com.example.chimneyandroid;

oneway interface IVpnServiceCallback {
    void onStatusChanged(String status, String message);
}