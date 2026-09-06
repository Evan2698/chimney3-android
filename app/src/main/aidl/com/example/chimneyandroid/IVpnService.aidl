//aidl
package com.example.chimneyandroid;

import com.example.chimneyandroid.IVpnServiceCallback;

interface IVpnService {
    String getStatus();
    void disconnect();
    void registerCallback(IVpnServiceCallback callback);
    void unregisterCallback(IVpnServiceCallback callback);
}
