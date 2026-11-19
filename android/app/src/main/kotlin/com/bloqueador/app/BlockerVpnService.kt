package com.bloqueador.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN local que filtra SOMENTE tráfego de DNS (porta 53).
 *
 * Como só roteamos os IPs dos servidores DNS para dentro do túnel,
 * todo o resto da internet passa direto — nada quebra.
 *
 * - Domínios da blocklist (pornô/adulto) -> resposta NXDOMAIN na hora.
 * - O resto é encaminhado para o CleanBrowsing Family (filtra categorias
 *   adulto/proxy/malware) com fallback para o DNS original da rede.
 */
class BlockerVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.bloqueador.app.START"
        const val ACTION_STOP = "com.bloqueador.app.STOP"
        const val TAG = "BlockerVpn"

        @Volatile var running = false
        @Volatile var lastError: String? = null
        val blockedCount = AtomicLong(0)
        val dnsSeen = AtomicLong(0)
        private val dnsPool = Executors.newFixedThreadPool(8) { r ->
            Thread(r, "dns-fwd").apply { isDaemon = true }
        }
        @Volatile var activeDnsServers = ""

        // DNS públicos mais usados — entram nas rotas para o túnel capturar.
        private val PUBLIC_DNS = listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1",
            "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220",
            "94.140.14.14", "94.140.15.15",
            "84.200.69.80", "84.200.69.81",
            "76.76.2.2", "76.76.10.2",
            "193.110.81.9", "193.110.81.10",
            "77.88.8.8", "77.88.8.1",
            // IPv6
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2620:fe::fe", "2620:fe::9",
            "2001:19f0:500:11::53", "2a0d:2a00:1::",
            "2001:67c:2b0::4", "2001:67c:2b0::6"
        )

        // CleanBrowsing Family: filtra pornografia, conteúdo adulto, proxy/VPN e malware.
        private val UPSTREAMS = listOf(
            InetAddress.getByName("185.228.168.168"),
            InetAddress.getByName("185.228.169.168")
        )

        fun start(context: android.content.Context) {
            val i = Intent(context, BlockerVpnService::class.java).setAction(ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: android.content.Context) {
            val i = Intent(context, BlockerVpnService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        // ---- Blocklist de domínios adultos ----
        private val BLOCKED = hashSetOf(
            // tubos/pornô principais
            "pornhub.com", "pornhub.net", "pornhub.org", "pornhubpremium.com",
            "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
            "tube8.com", "spankbang.com", "eporner.com", "tnaflix.com",
            "porntrex.com", "beeg.com", "drtuber.com", "pornhd.com",
            "motherless.com", "nuvid.com", "sunporno.com", "empflix.com",
            "porndoe.com", "porntn.com", "pornpig.com", "vjav.com",
            "thisvid.com", "heavy-r.com", "pornoxo.com", "hdzog.com",
            "thumbzilla.com", "porndig.com", "txxx.com", "upornia.com",
            "hqporner.com", "fapcat.com", "fapdu.com", "fapello.com",
            "faphouse.com", "fapify.com", "faplife.com", "fapmaster.com",
            "fapzone.com", "fapstart.com", "faponic.com", "fapme.com",
            "onlyfans.com", "onlyfans.io", "fansly.com", "manyvids.com",
            "clips4sale.com", "iwantclips.com", "loyalfans.com",
            "chaturbate.com", "stripchat.com", "cam4.com", "livejasmin.com",
            "bongacams.com", "myfreecams.com", "flirt4free.com", "streamate.com",
            "xcams.com", "imlive.com", "bangbros.com", "realitykings.com",
            "naughtyamerica.com", "digitalplayground.com", "wicked.com",
            "brazzers.com", "mofos.com", "realitykings.com", "teamskeet.com",
            "babes.com", "vixen.com", "tushy.com", "blacked.com",
            "deeper.com", "realityjunkies.com", "sistersex.com", "puremature.com",
            "tubelips.com", "teensverine.com", "eroticax.com", "apex-network.com",
            "tushy.com", "mylf.com", "amateur8.com", "tushy.com",
            "sex.com", "sex.com.com", "youporn.com", "youjizz.com",
            "pornerbros.com", "porndish.com", "porntube.com", "pornotube.com",
            "pornmd.com", "porndoe.com", "pornfree.com", "freeporn.com",
            "porngames.com", "hentai.tv", "hentaipros.com",
            // hentai / mangá adulto
            "hanime.tv", "hanime.cc", "nhentai.net", "nhentai.xxx",
            "e-hentai.org", "exhentai.org", "hitomi.la", "fakku.net",
            "nhentaicomics.com", "hentaihaven.xxx", "hentaicraft.com",
            "multporn.net", "multporn.com", "thothub.to", "thothub.cc",
            "simpcity.su", "leakedzone.com", "fapezone.com", "fapello.com",
            "nudostar.com", "erome.com", "kemono.party", "kemono.su",
            "simpcity.su", "fapello.com", "simply-hentai.com", "hentai2read.com",
            // sites de vazamento / conteúdo chocante
            "bestgore.com", "liveleak.com", "shockshare.com", "worldstarhiphop.com",
            "documentingreality.com", "blogofdeath.com", "theync.com",
            "tubegore.com", "freerealitytube.com", "bloodandshock.com",
            // outros portais adultos BR/mundo
            "sexo.com.br", "sexo.uol.com.br", "cazzo.net", "xvideos.com.br",
            "porn.com", "eroticahd.com", "eroticaflix.com", "eroticaxx.com",
            "dirtyship.com", "dirtyship.com.br", "putaria.com.br", "novinhaerotica.com",
            "cupons.club", "gostosas.com", "gostosa.blog", "kinkyheaven.com",
            "sexlog.com", "fetlife.com", "fetlife.com.br", "redtube.com.br",
            "xhamster.com.br", "spankbang.com.br", "hentaicomics.com",
            "hentaimama.io", "hentairead.com", "hentai.tv", "h-anime.com",
            "h-anime.tv", "hentaialt.com", "tentacleporn.com", "subscribestar.com",
            "fansly.com", "onlyfans.com", "loyalfans.com", "manyvids.com",
            "clips4sale.com", "iwantclips.com", "justfor.fans", "myfanpark.com",
            "fanvue.com", "campsite.bio", "kemono.cr", "kemono.party",
            "coomer.st", "coomer.su", "sankakucomplex.com", "gelbooru.com",
            "danbooru.donmai.us", "rule34.xxx", "rule34.us", "gelbooru.com",
            "safebooru.org", "konachan.com", "yande.re", "animepahe.ru",
            "allporncomic.com", "porncomix.net", "porncomixinfo.com",
            "hentaicn.com", "hentaicomics.org", "readhentai.com", "hentaicraft.com",
            "sekai-project.com", "asmhentai.com", "hentai.gg", "nhentai.com",
            "e621.net", "e926.net", "hypnohub.net", "derpibooru.org",
            "furaffinity.net", "inkbunny.net", "sofurry.com", "sofurry.com",
            "weasyl.com", "furry.network", "furries.online", "kemono.party",
            "kemono.su", "kemono.cr", "coomer.party", "coomer.su"
        )

        private fun isBlockedDomain(domain: String): Boolean {
            val d = domain.lowercase().trimEnd('.')
            if (d.isEmpty()) return false
            if (BLOCKED.contains(d)) return true
            for (b in BLOCKED) {
                if (d.endsWith(".$b")) return true
            }
            return false
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var tunIn: FileInputStream? = null
    @Volatile private var stopFlag = false
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> { startVpn(); return START_STICKY }
        }
        // restart do sistema: tenta religar a VPN
        if (!running) startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        val dnsServers = currentDnsServers()
        activeDnsServers = dnsServers.joinToString(", ")
        // Só roteamos /32 para os servidores DNS (rede atual + públicos).
        // Todo o resto da internet passa direto — nada quebra.
        val b = Builder()
            .setSession("Bloqueador Familiar")
            .setMtu(1500)
            .addAddress("10.88.0.2", 32)
        val routed = LinkedHashSet<String>()
        dnsServers.forEach { routed.add(it) }
        PUBLIC_DNS.forEach { routed.add(it) }
        routed.forEach { ip ->
            try {
                val prefix = if (ip.contains(':')) 128 else 32
                b.addRoute(ip, prefix)
            } catch (e: Exception) { Log.w(TAG, "rota inválida $ip") }
        }

        // Declara os DNS do túnel: o Android passa a mandar as consultas
        // DNS dos apps para estes servidores — que estão roteados para o
        // tun, então caem no nosso filtro.
        val dnsForVpn = (dnsServers + listOf("8.8.8.8", "1.1.1.1")).distinct().take(4)
        dnsForVpn.forEach { ip ->
            try { b.addDnsServer(ip) } catch (e: Exception) { Log.w(TAG, "dns inválido $ip") }
        }

        val pfd = try {
            b.establish()
        } catch (e: Exception) {
            lastError = "establish falhou: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError.orEmpty())
            stopSelf()
            return
        } ?: run {
            lastError = "establish() retornou null (consentimento revogado ou outra VPN ativa?)"
            Log.e(TAG, lastError.orEmpty())
            stopSelf()
            return
        }
        tun = pfd
        tunIn = FileInputStream(pfd.fileDescriptor)
        tunOut = FileOutputStream(pfd.fileDescriptor)
        stopFlag = false
        running = true
        worker = Thread({ packetLoop() }, "vpn-packet-loop").apply { isDaemon = true; start() }
        startForegroundCompat()
        Log.i(TAG, "VPN iniciada — rotas DNS: ${routed.size}")
    }

    private fun currentDnsServers(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val lp = cm.getLinkProperties(network)
            lp?.dnsServers?.forEach { addr ->
                val h = addr.hostAddress ?: return@forEach
                if (h.contains('.') || h.contains(':')) out.add(h)
            }
            // fallback: gateway do WiFi (muitos roteadores fazem DNS proxy no gateway)
            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                val gw = wm.dhcpInfo?.gateway ?: 0
                if (gw != 0) {
                    val ip = "${(gw and 0xFF)}.${(gw shr 8 and 0xFF)}.${(gw shr 16 and 0xFF)}.${(gw shr 24 and 0xFF)}"
                    out.add(ip)
                }
            } catch (_: Exception) {}
        } catch (e: Exception) { Log.w(TAG, "dns servers: ${e.message}") }
        return out.toList()
    }

    private fun stopVpn() {
        stopFlag = true
        running = false
        try { tunIn?.close() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null; tunIn = null; tunOut = null
        worker?.interrupt(); worker = null
        try { if (android.os.Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true) } catch (_: Exception) {}
        stopSelf()
        Log.i(TAG, "VPN parada")
    }


    private fun startForegroundCompat() {
        val channelId = "vpn_channel"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = android.app.NotificationChannel(
                channelId, "Bloqueador Familiar",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NOTIFICATION_SERVICE)
                .let { it as android.app.NotificationManager }
                .createNotificationChannel(ch)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
            .setContentTitle("Bloqueador Familiar ativo")
            .setContentText("Conteúdo adulto bloqueado neste aparelho")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    // ---------------- loop de pacotes ----------------

    private fun packetLoop() {
        val buf = ByteArray(32767)
        while (!stopFlag) {
            val n: Int
            try {
                n = tunIn!!.read(buf)
            } catch (e: Exception) {
                if (!stopFlag) Log.w(TAG, "read: ${e.message}")
                break
            }
            if (n <= 0) continue
            try {
                handlePacket(buf.copyOf(n))
            } catch (e: Exception) {
                Log.w(TAG, "handlePacket: ${e.message}")
            }
        }
    }

    private fun handlePacket(pkt: ByteArray) {
        if (pkt.isEmpty()) return
        when (pkt[0].toInt() ushr 4) {
            4 -> handlePacketV4(pkt)
            6 -> handlePacketV6(pkt)
        }
    }

    private fun handlePacketV4(pkt: ByteArray) {
        if (pkt.size < 20) return
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || pkt.size < ihl) return
        val proto = pkt[9].toInt() and 0xFF
        if (proto != 17) return // só UDP (DNS quase sempre é UDP/53)

        val udpOff = ihl
        if (pkt.size < udpOff + 8) return
        val srcPort = u16(pkt, udpOff)
        val dstPort = u16(pkt, udpOff + 2)
        if (dstPort != 53 && srcPort != 53) return
        dnsSeen.incrementAndGet()

        val dnsOff = udpOff + 8
        val dnsLen = pkt.size - dnsOff
        if (dnsLen < 12) return

        // extrai QNAME do DNS
        val qname = extractQName(pkt, dnsOff) ?: return
        if (isBlockedDomain(qname)) {
            blockedCount.incrementAndGet()
            Log.i(TAG, "BLOQUEADO: $qname")
            val resp = buildNxDomain(pkt, ihl, udpOff, dnsOff, dnsLen)
            tunOut!!.write(resp)
            tunOut!!.flush()
            return
        }

        // encaminha para CleanBrowsing Family
        forwardDns(pkt, ihl, udpOff, dnsOff, dnsLen)
    }


    // ---------- IPv6 ----------

    private fun handlePacketV6(pkt: ByteArray) {
        if (pkt.size < 40) return
        val payloadLen = u16(pkt, 4)
        var nh = pkt[6].toInt() and 0xFF
        val end = minOf(pkt.size, 40 + payloadLen)
        var off = 40
        // pula extension headers simples
        while (nh == 0 || nh == 43 || nh == 60) { // hop-by-hop, routing, dest-opts
            if (off + 2 > end) return
            nh = pkt[off].toInt() and 0xFF
            val extLen = ((pkt[off + 1].toInt() and 0xFF) + 1) * 8
            off += extLen
            if (off > end) return
        }
        if (nh == 44 || nh == 51) return // fragment / AH: descarta
        if (nh != 17) return // só UDP
        if (end < off + 8) return
        val srcPort = u16(pkt, off)
        val dstPort = u16(pkt, off + 2)
        if (dstPort != 53 && srcPort != 53) return
        dnsSeen.incrementAndGet()
        val dnsOff = off + 8
        if (end - dnsOff < 12) return
        val dnsLen = end - dnsOff

        val qname = extractQName(pkt, dnsOff) ?: return
        if (isBlockedDomain(qname)) {
            blockedCount.incrementAndGet()
            Log.i(TAG, "BLOQUEADO(v6): $qname")
            val respDns = pkt.copyOfRange(dnsOff, dnsOff + dnsLen)
            if (respDns.size >= 4) {
                val rd = (respDns[2].toInt() and 0x01) shl 8
                val flags = 0x8000 or rd or 0x0080 or 0x0003
                respDns[2] = ((flags shr 8) and 0xFF).toByte()
                respDns[3] = (flags and 0xFF).toByte()
                respDns[6] = 0; respDns[7] = 0; respDns[8] = 0; respDns[9] = 0
                respDns[10] = 0; respDns[11] = 0
            }
            val reply = buildDnsReplyV6(pkt, off, dnsOff, respDns)
            synchronized(tunOut!!) { tunOut!!.write(reply); tunOut!!.flush() }
            return
        }
        forwardDnsV6(pkt, off, dnsOff, dnsLen)
    }

    private fun forwardDnsV6(pkt: ByteArray, udpOff: Int, dnsOff: Int, dnsLen: Int) {
        val payload = pkt.copyOfRange(dnsOff, dnsOff + dnsLen)
        dnsPool.execute {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                protect(sock)
                sock.soTimeout = 5000
                var respBytes: ByteArray? = null
                for (u in UPSTREAMS) {
                    try {
                        val q = DatagramPacket(payload, payload.size, u, 53)
                        sock.send(q)
                        val buf = ByteArray(4096)
                        val r = DatagramPacket(buf, buf.size)
                        sock.receive(r)
                        respBytes = buf.copyOf(r.length)
                        break
                    } catch (e: Exception) { /* próximo upstream */ }
                }
                if (respBytes == null) return@execute
                val reply = buildDnsReplyV6(pkt, udpOff, dnsOff, respBytes)
                synchronized(tunOut!!) { tunOut!!.write(reply); tunOut!!.flush() }
            } catch (e: Exception) {
                Log.w(TAG, "dns fwd v6: ${e.message}")
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun buildDnsReplyV6(req: ByteArray, udpOff: Int, dnsOff: Int, dnsResp: ByteArray): ByteArray {
        val udpLen = 8 + dnsResp.size
        val out = ByteArray(40 + udpLen)

        // IPv6 header
        System.arraycopy(req, 0, out, 0, 4) // version/tc/flow
        put16(out, 4, udpLen)               // payload length
        out[6] = 17                          // next header = UDP
        out[7] = 64                          // hop limit
        System.arraycopy(req, 24, out, 8, 16)  // src = dst original
        System.arraycopy(req, 8, out, 24, 16)  // dst = src original

        // UDP header
        put16(out, 40, u16(req, udpOff + 2)) // src port = 53
        put16(out, 42, u16(req, udpOff))     // dst port = original
        put16(out, 44, udpLen)
        put16(out, 46, 0)                    // checksum calculado abaixo
        System.arraycopy(dnsResp, 0, out, 48, dnsResp.size)
        udpChecksumV6(out, 40, udpLen)
        return out
    }

    private fun udpChecksumV6(pkt: ByteArray, udpOff: Int, udpLen: Int) {
        var sum = 0L
        fun add(b: Int) { sum += b and 0xFF }
        // pseudo-header: src(16) + dst(16) + len(4) + zeros(3) + nextHeader(1)
        for (i in 8 until 40) add(pkt[i].toInt())
        add((udpLen shr 24) and 0xFF); add((udpLen shr 16) and 0xFF)
        add((udpLen shr 8) and 0xFF); add(udpLen and 0xFF)
        add(0); add(0); add(0); add(17)
        var i = udpOff
        val end = udpOff + udpLen
        while (i + 1 < end) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (pkt[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.inv().toInt() and 0xFFFF
        pkt[udpOff + 6] = ((c shr 8) and 0xFF).toByte()
        pkt[udpOff + 7] = (c and 0xFF).toByte()
    }

    // ---------- DNS ----------

    private fun extractQName(pkt: ByteArray, off: Int): String? {
        var p = off + 12
        val labels = ArrayList<String>()
        while (p < pkt.size) {
            val len = pkt[p].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) break // compression pointer — não seguimos em query
            p++
            if (p + len > pkt.size) return null
            labels.add(String(pkt, p, len, Charsets.US_ASCII))
            p += len
        }
        if (labels.isEmpty()) return null
        return labels.joinToString(".").lowercase()
    }

    private fun forwardDns(pkt: ByteArray, ihl: Int, udpOff: Int, dnsOff: Int, dnsLen: Int) {
        val payload = pkt.copyOfRange(dnsOff, dnsOff + dnsLen)
        dnsPool.execute {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                protect(sock) // tira do túnel — senão loop infinito
                sock.soTimeout = 5000
                var upstream: InetAddress? = null
                var respBytes: ByteArray? = null
                for (u in UPSTREAMS) {
                    try {
                        val q = DatagramPacket(payload, payload.size, u, 53)
                        sock.send(q)
                        val buf = ByteArray(4096)
                        val r = DatagramPacket(buf, buf.size)
                        sock.soTimeout = 4000
                        sock.receive(r)
                        upstream = u
                        respBytes = buf.copyOf(r.length)
                        break
                    } catch (e: Exception) { /* tenta próximo upstream */ }
                }
                if (respBytes == null || upstream == null) return@execute
                val reply = buildDnsReply(pkt, ihl, udpOff, respBytes)
                synchronized(tunOut!!) { tunOut!!.write(reply); tunOut!!.flush() }
            } catch (e: Exception) {
                Log.w(TAG, "dns fwd: ${e.message}")
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
    }

    // Monta pacote IP/UDP de resposta: troca src/dst do request e embute a
    // resposta DNS do upstream.
    private fun buildDnsReply(req: ByteArray, ihl: Int, udpOff: Int, dnsResp: ByteArray): ByteArray {
        val udpLen = 8 + dnsResp.size
        val totalLen = ihl + udpLen
        val out = ByteArray(totalLen)

        // --- IP header (copiado do request, src/dst trocados) ---
        System.arraycopy(req, 0, out, 0, ihl)
        // dst = src original, src = dst original
        System.arraycopy(req, 12, out, 16, 4)
        System.arraycopy(req, 16, out, 12, 4)
        // total length
        out[2] = ((totalLen shr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        // identification: novo
        out[4] = 0; out[5] = 0
        // flags/frag: DF
        out[6] = 0x40; out[7] = 0x00
        // TTL = 64
        out[8] = 64
        // checksum IP = 0 primeiro
        out[10] = 0; out[11] = 0
        ipChecksum(out, ihl)

        // --- UDP header ---
        val srcPort = u16(req, udpOff + 2) // resposta vem "da porta 53"
        val dstPort = u16(req, udpOff)     // para a porta original do app
        put16(out, udpOff, srcPort)
        put16(out, udpOff + 2, dstPort)
        put16(out, udpOff + 4, udpLen)
        put16(out, udpOff + 6, 0) // checksum UDP 0 = não calculado (válido IPv4)

        System.arraycopy(dnsResp, 0, out, udpOff + 8, dnsResp.size)
        return out
    }

    private fun buildNxDomain(req: ByteArray, ihl: Int, udpOff: Int, dnsOff: Int, dnsLen: Int): ByteArray {
        val q = req.copyOfRange(dnsOff, dnsOff + dnsLen)
        // resposta NXDOMAIN: QR=1, RD copiado, RA=1, RCODE=3, sem respostas
        if (q.size >= 4) {
            val rd = (q[2].toInt() and 0x01) shl 8
            val flags = 0x8000 or rd or 0x0080 or 0x0003
            q[2] = ((flags shr 8) and 0xFF).toByte()
            q[3] = (flags and 0xFF).toByte()
            // ANCOUNT=NSCOUNT=ARCOUNT=0 (QDCOUNT preservado)
            q[6] = 0; q[7] = 0; q[8] = 0; q[9] = 0; q[10] = 0; q[11] = 0
        }
        return buildDnsReply(req, ihl, udpOff, q)
    }

    // ---------------- util ----------------

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun ipChecksum(pkt: ByteArray, ihl: Int) {
        var sum = 0L
        var i = 0
        while (i + 1 < ihl) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.inv().toInt() and 0xFFFF
        pkt[10] = ((c shr 8) and 0xFF).toByte()
        pkt[11] = (c and 0xFF).toByte()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
