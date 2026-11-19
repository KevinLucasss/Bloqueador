package com.bloqueador.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Aplica o "bloqueio total" quando o app é Device Owner (ou Profile Owner).
 *
 * O que faz:
 * - VPN sempre ativa e sem possibilidade de desligar
 * - Impede desinstalar QUALQUER app (incluindo este)
 * - Bloqueia instalação de APKs de fontes desconhecidas (sideline)
 * - Bloqueia adicionar/remover contas novas
 */
object LockdownManager {

    fun adminComponent(context: Context) =
        ComponentName(context, AdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName) ||
               dpm.isProfileOwnerApp(context.packageName)
    }

    fun applyIfOwner(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        val pkg = context.packageName

        // VPN sempre ativa — ninguém desliga nem troca de VPN
        try {
            dpm.setAlwaysOnVpnPackage(admin, pkg, true)
        } catch (e: Exception) { android.util.Log.w("Lockdown", "alwaysOnVpn: ${e.message}") }

        // Não pode desinstalar apps (inclui este app)
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
        } catch (e: Exception) { android.util.Log.w("Lockdown", "uninstall restriction: ${e.message}") }

        // Não pode instalar APK de fonte externa (sideload)
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        } catch (e: Exception) { android.util.Log.w("Lockdown", "unknown sources: ${e.message}") }

        // Não pode adicionar contas novas (evita criar conta alternativa)
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
        } catch (e: Exception) { android.util.Log.w("Lockdown", "accounts: ${e.message}") }

        android.util.Log.i("Lockdown", "Modo bloqueio total aplicado")
    }

    fun release(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        val pkg = context.packageName
        try { dpm.setAlwaysOnVpnPackage(admin, null, false) } catch (_: Exception) {}
        try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS) } catch (_: Exception) {}
        try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) } catch (_: Exception) {}
        try { dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS) } catch (_: Exception) {}
    }
}
