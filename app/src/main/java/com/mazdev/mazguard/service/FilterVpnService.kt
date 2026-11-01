package com.mazdev.mazguard.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mazdev.mazguard.MazGuardApp
import com.mazdev.mazguard.R
import com.mazdev.mazguard.core.AppPrefs
import com.mazdev.mazguard.core.BlockLog
import com.mazdev.mazguard.core.DohBlocklist
import com.mazdev.mazguard.core.FilterEngine
import com.mazdev.mazguard.core.SafeSearch
import com.mazdev.mazguard.dns.DnsCodec
import com.mazdev.mazguard.dns.DnsProxy
import com.mazdev.mazguard.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNS-filtering VPN.
 *
 * Only the DNS server address that we hand to the system is routed into the
 * tunnel. Everything else keeps using the physical network at full speed, so
 * throughput, latency and battery are effectively unchanged. No traffic is
 * inspected, decrypted or proxied.
 */
class FilterVpnService : VpnService() {

    private lateinit var prefs: AppPrefs
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ThreadPoolExecutor? = null
    private var output: OutputStream? = null
    private var proxy: DnsProxy? = null

    private val stopping = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var generation = 0
    private var revokeRetries = 0

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = AppPrefs.get(this)
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()

        if (!prefs.guardEnabled) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        // An explicit start also rebuilds the tunnel (used when a setting that
        // affects the tunnel changes, such as the DoH blocklist toggle).
        if (running) teardown()
        ensureTunnel()
        return START_STICKY
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, MazGuardApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureTunnel() {
        var fd = establishTunnel(includeIpv6 = true)
        if (fd == null) fd = establishTunnel(includeIpv6 = false)
        if (fd == null) return

        stopping.set(false)
        tunnel = fd
        output = FileOutputStream(fd.fileDescriptor)
        proxy = DnsProxy(
            protectSocket = { socket: DatagramSocket -> protect(socket) },
            upstreamProvider = { upstreamServers() }
        )
        pool = ThreadPoolExecutor(
            2, 8, 30L, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(256),
            ThreadPoolExecutor.DiscardPolicy()
        )
        running = true
        revokeRetries = 0
        val gen = ++generation

        worker = Thread({ readLoop(fd, gen) }, "mazguard-dns").apply {
            isDaemon = true
            start()
        }
    }

    private fun establishTunnel(includeIpv6: Boolean): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)

        if (prefs.blockDoh) {
            for (ip in DohBlocklist.IPV4) runCatching { builder.addRoute(ip, 32) }
            if (includeIpv6) {
                for (ip in DohBlocklist.IPV6) runCatching { builder.addRoute(ip, 128) }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.setMetered(false) }
        }
        runCatching { builder.setUnderlyingNetworks(null) }
        builder.setBlocking(true)

        return try {
            builder.establish()
        } catch (t: Throwable) {
            null
        }
    }

    private fun upstreamServers(): List<String> {
        val configured = systemDnsServers()
        return if (configured.isNotEmpty()) configured else FALLBACK_DNS
    }

    /** Reads the resolvers of the underlying (non-VPN) network. */
    private fun systemDnsServers(): List<String> {
        val result = ArrayList<String>(2)
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return result
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (address in lp.dnsServers) {
                    val host = address.hostAddress ?: continue
                    if (address is java.net.Inet4Address && !result.contains(host)) {
                        result.add(host)
                        if (result.size >= 2) return result
                    }
                }
            }
        } catch (t: Throwable) {
            // fall through to fallbacks
        }
        return result
    }

    private fun readLoop(fd: ParcelFileDescriptor, gen: Int) {
        val input = try {
            FileInputStream(fd.fileDescriptor)
        } catch (t: Throwable) {
            if (gen == generation) running = false
            return
        }
        // Fail closed: do not answer anything until the blocklist is loaded.
        FilterEngine.awaitReady(8000)
        val buffer = ByteArray(65535)
        while (!stopping.get()) {
            val length = try {
                input.read(buffer)
            } catch (t: Throwable) {
                break
            }
            if (length < 0) break
            if (length == 0) continue
            try {
                handlePacket(buffer, length)
            } catch (t: Throwable) {
                // Never let one malformed packet kill the tunnel.
            }
        }
        if (gen == generation) running = false
    }

    private fun handlePacket(buf: ByteArray, length: Int) {
        if (length < 28) return
        if (((buf[0].toInt() shr 4) and 0x0F) != 4) return
        val headerLength = (buf[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + 8) return
        if ((buf[9].toInt() and 0xFF) != 17) return // UDP only

        val sourcePort = DnsCodec.readU16(buf, headerLength)
        val destPort = DnsCodec.readU16(buf, headerLength + 2)
        if (destPort != 53) return

        val udpLength = DnsCodec.readU16(buf, headerLength + 4)
        var dnsLength = if (udpLength in 8..(length - headerLength)) {
            udpLength - 8
        } else {
            length - headerLength - 8
        }
        if (dnsLength < 12) return
        if (headerLength + 8 + dnsLength > length) dnsLength = length - headerLength - 8

        val query = buf.copyOfRange(headerLength + 8, headerLength + 8 + dnsLength)
        val question = DnsCodec.parseQuestion(query, query.size) ?: return
        val clientIp = buf.copyOfRange(16, 20) // original destination is our tunnel address
        val sourceAddress = buf.copyOfRange(12, 16)

        val host = question.name
        if (host.isNotEmpty() && FilterEngine.isBlocked(host)) {
            BlockLog.record(host)
            val reply = if (question.type == DnsCodec.TYPE_A) {
                DnsCodec.buildAnswer(query, question.endOffset, listOf(ZERO_IP))
            } else {
                DnsCodec.buildEmpty(query, question.endOffset, 0)
            }
            send(reply, sourceAddress, clientIp, sourcePort, 53)
            return
        }

        val safeTarget = if (FilterEngine.safeSearch && question.qclass == DnsCodec.CLASS_IN) {
            SafeSearch.target(host)
        } else {
            null
        }

        val executor = pool ?: return
        runCatching {
            executor.execute {
                try {
                    if (safeTarget != null) {
                        val addresses = proxy?.resolveIps(safeTarget) ?: emptyList()
                        val reply = if (question.type == DnsCodec.TYPE_A && addresses.isNotEmpty()) {
                            DnsCodec.buildAnswer(query, question.endOffset, addresses)
                        } else {
                            DnsCodec.buildEmpty(query, question.endOffset, 0)
                        }
                        send(reply, sourceAddress, clientIp, sourcePort, 53)
                        return@execute
                    }

                    val reply = proxy?.resolve(query)
                    if (reply != null) {
                        send(reply, sourceAddress, clientIp, sourcePort, 53)
                    } else {
                        // SERVFAIL so clients fail fast instead of hanging.
                        send(DnsCodec.buildEmpty(query, question.endOffset, 2), sourceAddress, clientIp, sourcePort, 53)
                    }
                } catch (t: Throwable) {
                    // ignore
                }
            }
        }
    }

    /**
     * Wraps a DNS payload in IPv4/UDP and writes it back to the tunnel.
     * serverIp is our synthetic resolver, clientIp is the app that asked.
     */
    private fun send(
        payload: ByteArray,
        clientIp: ByteArray,
        serverIp: ByteArray,
        clientPort: Int,
        serverPort: Int
    ) {
        val total = 20 + 8 + payload.size
        val packet = ByteArray(total)

        packet[0] = 0x45
        DnsCodec.writeU16(packet, 2, total)
        DnsCodec.writeU16(packet, 4, 0)
        DnsCodec.writeU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        System.arraycopy(serverIp, 0, packet, 12, 4)
        System.arraycopy(clientIp, 0, packet, 16, 4)
        DnsCodec.writeU16(packet, 10, ipChecksum(packet, 0, 20))

        DnsCodec.writeU16(packet, 20, serverPort)
        DnsCodec.writeU16(packet, 22, clientPort)
        DnsCodec.writeU16(packet, 24, 8 + payload.size)
        DnsCodec.writeU16(packet, 26, 0)
        System.arraycopy(payload, 0, packet, 28, payload.size)

        val stream = output ?: return
        synchronized(writeLock) {
            runCatching {
                stream.write(packet)
                stream.flush()
            }
        }
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun teardown() {
        stopping.set(true)
        running = false
        runCatching { pool?.shutdownNow() }
        pool = null
        runCatching { tunnel?.close() }
        tunnel = null
        runCatching { output?.close() }
        output = null
        proxy = null
        BlockLog.flush()
    }

    override fun onRevoke() {
        running = false
        teardown()
        if (prefs.guardEnabled) {
            notifyDisabled()
            restoreAfterRevoke()
        }
        super.onRevoke()
    }

    private fun restoreAfterRevoke() {
        if (revokeRetries >= 3) return
        revokeRetries++
        Handler(Looper.getMainLooper()).postDelayed({
            if (!running && prefs.guardEnabled) {
                runCatching { start(applicationContext) }
            }
        }, 2500L * revokeRetries)
    }

    private fun notifyDisabled() {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, MazGuardApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield_off)
            .setContentTitle(getString(R.string.notif_off_title))
            .setContentText(getString(R.string.notif_off_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_OFF_ID, notification)
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mazdev.mazguard.action.START"
        const val ACTION_STOP = "com.mazdev.mazguard.action.STOP"

        private const val NOTIF_ID = 1001
        private const val NOTIF_OFF_ID = 1002

        private const val VPN_ADDRESS = "10.111.222.2"
        private const val VPN_DNS = "10.111.222.1"

        private val ZERO_IP = byteArrayOf(0, 0, 0, 0)
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FilterVpnService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FilterVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
