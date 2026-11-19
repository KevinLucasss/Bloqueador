package com.bloqueador.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.net.VpnService
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.security.MessageDigest

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.bloqueador.app/vpn"
    private var pendingVpnResult: MethodChannel.Result? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockdownManager.applyIfOwner(this)
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        prefs = getSharedPreferences("blocker_prefs", Context.MODE_PRIVATE)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "prepareVpn" -> prepareVpn(result)
                    "stopVpn" -> {
                        BlockerVpnService.stop(this)
                        result.success(true)
                    }
                    "isRunning" -> result.success(BlockerVpnService.running)
                    "blockedCount" -> result.success(BlockerVpnService.blockedCount.get())
                    "hasPin" -> result.success(prefs.contains("pin_hash"))
                    "setPin" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        if (pin.length < 4) { result.success(false); return@setMethodCallHandler }
                        prefs.edit().putString("pin_hash", sha256(pin)).apply()
                        result.success(true)
                    }
                    "checkPin" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        result.success(prefs.getString("pin_hash", null) == sha256(pin))
                    }
                    "clearPin" -> {
                        prefs.edit().remove("pin_hash").apply()
                        result.success(true)
                    }
                    "isDeviceOwner" -> result.success(LockdownManager.isDeviceOwner(this))
                    "applyLockdown" -> {
                        LockdownManager.applyIfOwner(this)
                        result.success(LockdownManager.isDeviceOwner(this))
                    }
                    "releaseLockdown" -> {
                        LockdownManager.release(this)
                        result.success(true)
                    }
                    "isAccessibilityEnabled" -> result.success(isAccessibilityEnabled())
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(true)
                    }
                    "openUnknownAppsSettings" -> {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                        } else {
                            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        }
                        result.success(true)
                    }
                    "openPlayStore" -> {
                        val i = packageManager.getLaunchIntentForPackage("com.android.vending")
                        if (i != null) startActivity(i)
                        result.success(i != null)
                    }
                    "openVpnSettings" -> {
                        try {
                            startActivity(Intent("android.settings.VPN_SETTINGS"))
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }
                        result.success(true)
                    }
                    "vpnError" -> result.success(BlockerVpnService.lastError)
                    "dnsSeen" -> result.success(BlockerVpnService.dnsSeen.get())
                    "activeDns" -> result.success(BlockerVpnService.activeDnsServers)
                    "stats" -> result.success(mapOf(
                        "running" to BlockerVpnService.running,
                        "blocked" to BlockerVpnService.blockedCount.get(),
                        "dnsSeen" to BlockerVpnService.dnsSeen.get(),
                        "appBlocked" to prefs.getInt(AdultAppBlockerService.KEY_APP_BLOCKS, 0),
                        "accOn" to isAccessibilityEnabled(),
                        "error" to (BlockerVpnService.lastError ?: ""),
                        "activeDns" to BlockerVpnService.activeDnsServers
                    ))
                    "appBlockedCount" -> result.success(
                        prefs.getInt(AdultAppBlockerService.KEY_APP_BLOCKS, 0).toLong()
                    )
                    else -> result.notImplemented()
                }
            }
    }

    private fun prepareVpn(result: MethodChannel.Result) {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            BlockerVpnService.start(this)
            result.success("started")
        } else {
            pendingVpnResult = result
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VPN)
            result.success("consent")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            val r = pendingVpnResult
            pendingVpnResult = null
            if (resultCode == RESULT_OK) {
                BlockerVpnService.start(this)
                r?.success("started")
            } else {
                r?.success("denied")
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "$packageName/${AdultAppBlockerService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object { private const val REQ_VPN = 2471 }
}
