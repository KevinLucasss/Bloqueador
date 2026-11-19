package com.bloqueador.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Detecta quando um app adulto/imoral entra em primeiro plano e
 * força o retorno à tela inicial. Complementa o bloqueio de DNS:
 * mesmo que o app funcione offline, ele não abre.
 */
class AdultAppBlockerService : AccessibilityService() {

    companion object {
        const val TAG = "AdultBlocker"
        const val PREFS = "blocker_prefs"
        const val KEY_APP_BLOCKS = "app_blocked_count"
        const val KEY_USER_BLOCKED = "user_blocked_apps"
        const val KEY_ALERT_TOPIC = "alert_topic"

        // Pacotes conhecidos de apps adultos/imorais/chocantes
        val BLOCKED_APPS = hashSetOf(
            // tubos de vídeo adulto
            "com.pornhub", "com.pornhub.app", "com.pornhub.pornhub",
            "com.xvideos", "com.xnxx", "com.xhamster", "com.redtube",
            "com.youporn", "com.youjizz", "com.tube8", "com.spankbang",
            "com.eporner", "com.tnaflix", "com.porntrex", "com.beeg",
            "com.pornhd", "com.motherless", "com.nuvid", "com.drtuber",
            "com.hdzog", "com.thumbzilla", "com.faphouse",
            // cams e OnlyFans-like
            "com.chaturbate", "com.stripchat", "com.cam4",
            "com.bongacams", "com.myfreecams", "com.flirt4free",
            "com.livejasmin", "com.onlyfans", "com.fansly",
            // estúdios
            "com.bangbros", "com.realitykings", "com.naughtyamerica",
            "com.digitalplayground", "com.wicked", "com.brazzers",
            "com.mofos", "com.teamskeet", "com.blacked",
            // hentai
            "com.hanime", "com.nhentai", "com.hitomi",
            "com.hentaihaven", "com.hentaicraft", "com.thothub",
            "com.sankaku", "com.gelbooru", "com.derpibooru",
            "com.rule34", "com.furaffinity",
            // conteúdo chocante/gore
            "com.bestgore", "com.liveleak", "com.shockshare",
            "com.tubegore", "com.worldstar", "com.freedomain",
            // BR / genéricos
            "com.sexo", "com.putaria", "com.cazzo",
            "com.gostosas", "com.sexlog", "com.fetlife"
        )
    }

    private var prefs: SharedPreferences? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Log.i(TAG, "AdultAppBlocker conectado")
    }

    private var lastUninstallAlert = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        checkUninstallAttempt(event)
        val pkg = event.packageName?.toString() ?: return
        val userBlocked = prefs?.getStringSet(KEY_USER_BLOCKED, emptySet()) ?: emptySet()
        if (BLOCKED_APPS.contains(pkg) || userBlocked.contains(pkg)) {
            val count = (prefs?.getInt(KEY_APP_BLOCKS, 0) ?: 0) + 1
            prefs?.edit()?.putInt(KEY_APP_BLOCKS, count)?.apply()
            Log.i(TAG, "APP BLOQUEADO: $pkg (#$count)")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // Vigia telas de Configurações/instalador: se aparecer a tela de
    // desinstalação DESTE app, alerta o parente e volta pra trás.
    private fun checkUninstallAttempt(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val watchers = setOf(
            "com.android.settings", "com.google.android.packageinstaller",
            "com.android.packageinstaller", "com.miui.securitycenter",
            "com.miui.packageinstaller", "com.samsung.android.lool",
            "com.coloros.safecenter", "com.vivo.appstore", "com.huawei.systemmanager",
            "com.oppo.safe", "com.oneplus.security"
        )
        if (pkg !in watchers) return
        if (System.currentTimeMillis() - lastUninstallAlert < 30_000) return

        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val texts = ArrayList<String>()
        try {
            collectTexts(root, texts, 0)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
        val joined = texts.joinToString(" ").lowercase()
        val mentionsUninstall = joined.contains("desinstalar") ||
                joined.contains("uninstall") || joined.contains("remover app")
        val mentionsUs = joined.contains("bloqueador") || joined.contains(packageName)
        if (mentionsUninstall && mentionsUs) {
            lastUninstallAlert = System.currentTimeMillis()
            android.util.Log.w(TAG, "Tentativa de desinstalação detectada")
            sendNtfyAlert("🚨 Tentativa de DESINSTALAR o Bloqueador Familiar neste aparelho")
            performGlobalAction(GLOBAL_ACTION_BACK)
            android.os.Handler(mainLooper).postDelayed(
                { performGlobalAction(GLOBAL_ACTION_BACK) }, 400
            )
        }
    }

    private fun collectTexts(node: android.view.accessibility.AccessibilityNodeInfo?,
                             out: MutableList<String>, depth: Int) {
        if (node == null || depth > 15) return
        node.text?.let { out.add(it.toString()) }
        node.contentDescription?.let { out.add(it.toString()) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, depth + 1)
        }
    }

    private fun sendNtfyAlert(msg: String) {
        Thread {
            try {
                val topic = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_ALERT_TOPIC, "") ?: ""
                if (topic.isBlank()) return@Thread
                val conn = java.net.URL("https://ntfy.sh/${topic.trim()}")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Title", "Bloqueador Familiar")
                conn.setRequestProperty("Priority", "high")
                conn.setRequestProperty("Tags", "warning")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.write(msg.toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    override fun onInterrupt() {}
}
