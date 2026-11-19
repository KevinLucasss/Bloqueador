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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (BLOCKED_APPS.contains(pkg)) {
            val count = (prefs?.getInt(KEY_APP_BLOCKS, 0) ?: 0) + 1
            prefs?.edit()?.putInt(KEY_APP_BLOCKS, count)?.apply()
            Log.i(TAG, "APP BLOQUEADO: $pkg (#$count)")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onInterrupt() {}
}
