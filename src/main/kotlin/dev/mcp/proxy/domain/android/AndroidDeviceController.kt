package dev.mcp.proxy.domain.android

interface AndroidDeviceController {
    fun listDevices(): List<AndroidDevice>
    fun setProxy(udid: String?, proxyHost: String?, proxyPort: Int?): AndroidProxyState
    fun clearProxy(udid: String?): AndroidProxyState
    fun getProxy(udid: String?): AndroidProxyState
}
