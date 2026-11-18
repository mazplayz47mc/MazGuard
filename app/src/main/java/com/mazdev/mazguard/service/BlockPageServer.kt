package com.mazdev.mazguard.service

import android.content.Context
import android.util.Log
import com.mazdev.mazguard.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Tiny HTTP server that serves the block page for filtered domains.
 * Runs on the VPN gateway IP (10.111.222.1:80) so blocked domains
 * resolve here and show a reminder instead of a generic error.
 */
class BlockPageServer(private val context: Context) {

    private val running = AtomicBoolean(false)
    private val serverSocketRef = AtomicReference<ServerSocket?>()
    private val executor = Executors.newSingleThreadExecutor()

    private val blockQuotes = arrayOf(
        BlockQuote(
            arabic = "وَهُوَ مَعَكُمْ أَيْنَ مَا كُنتُمْ",
            english = "And He is with you wherever you are.",
            source = "Quran 57:4 · Al-Hadid"
        ),
        BlockQuote(
            arabic = "إِنَّ اللَّهَ كَانَ عَلَيْكُمْ رَقِيبًا",
            english = "Indeed, Allah is ever Watchful over you.",
            source = "Quran 4:1 · An-Nisa"
        ),
        BlockQuote(
            arabic = "وَاللَّهُ يُحِيطُ بِمَا يَعْمَلُونَ",
            english = "And Allah encompasses what they do.",
            source = "Quran 85:20 · Al-Buruj"
        ),
        BlockQuote(
            arabic = "يَعْلَمُ خَائِنَةَ الْأَعْيُنِ وَمَا تُخْفِي الصُّدُورُ",
            english = "He knows the betrayal of the eyes and what the breasts conceal.",
            source = "Quran 40:19 · Ghafir"
        ),
        BlockQuote(
            arabic = "وَمَا تَكُونُ فِي شَأْنٍ وَمَا تَتْلُو مِنْهُ مِنْ قُرْآنٍ... إِلَّا كُنَّا عَلَيْكُمْ شُهُودًا",
            english = "And you are not in any state nor do you recite any Quran except We are witnesses over you.",
            source = "Quran 10:61 · Yunus"
        ),
        BlockQuote(
            arabic = "فَمَن يَعْمَلْ مِثْقَالَ ذَرَّةٍ خَيْرًا يَرَهُ وَمَن يَعْمَلْ مِثْقَالَ ذَرَّةٍ شَرًّا يَرَهُ",
            english = "So whoever does an atom's weight of good will see it, and whoever does an atom's weight of evil will see it.",
            source = "Quran 99:7-8 · Az-Zalzalah"
        ),
        BlockQuote(
            arabic = "إِنَّ السَّمِيعَ الْبَصِيرَ",
            english = "Indeed, He is the Hearing, the Seeing.",
            source = "Quran 42:11 · Ash-Shura"
        ),
        BlockQuote(
            arabic = "يَوْمَ تَشْهَدُ عَلَيْهِمْ أَلْسِنَتُهُمْ وَأَيْدِيهِمْ وَأَرْجُلُهُم بِمَا كَانُوا يَعْمَلُونَ",
            english = "On the Day their tongues, hands and feet will testify against them as to what they used to do.",
            source = "Quran 24:24 · An-Nur"
        )
    )

    data class BlockQuote(
        val arabic: String,
        val english: String,
        val source: String
    )

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            try {
                val socket = ServerSocket(80, 50, InetAddress.getByName("10.111.222.1"))
                socket.setSoTimeout(1000)
                serverSocketRef.set(socket)
                Log.i("Hisn", "Block page server started on 10.111.222.1:80")
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        executor.execute { handleClient(client) }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Expected, loop continues
                    } catch (e: Throwable) {
                        if (running.get()) Log.w("Hisn", "Block server accept error", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e("Hisn", "Failed to start block page server", e)
                running.set(false)
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocketRef.getAndSet(null)?.close()
        } catch (e: Throwable) {
            Log.w("Hisn", "Error stopping block page server", e)
        }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val input = BufferedReader(InputStreamReader(s.getInputStream()))
            val output = s.getOutputStream()

            // Read request (we only need the first line)
            var requestLine = input.readLine() ?: return
            while (input.readLine().isNullOrEmpty() != true) {
                // Consume headers
            }

            // Extract Host header if present
            var host = "this site"
            var line: String?
            val requestBuilder = java.lang.StringBuilder(requestLine)
            while (input.ready()) {
                line = input.readLine()
                if (line.isNullOrEmpty()) break
                requestBuilder.append("\n").append(line)
                if (line.startsWith("Host:") || line.startsWith("host:")) {
                    host = line.substring(line.indexOf(':') + 1).trim()
                }
            }

            // Pick a random quote
            val quote = blockQuotes.random()

            val html = buildBlockPage(quote, host)
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${html.toByteArray().size}\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                    "\r\n" +
                    html
            output.write(response.toByteArray())
            output.flush()
        }
    }

    private fun buildBlockPage(quote: BlockQuote, host: String): String {
        val appName = context.getString(R.string.app_name)
        return """
<!DOCTYPE html>
<html dir="rtl" lang="ar">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>$appName - Blocked</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: "Segoe UI", Tahoma, Arial, sans-serif;
            background: linear-gradient(135deg, #050f0b 0%, #0a1e15 50%, #0d2b1f 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
            color: #e8efeb;
            direction: rtl;
        }
        .container {
            background: rgba(13, 31, 21, 0.95);
            border: 1px solid #1e3a2e;
            border-radius: 20px;
            padding: 40px 30px;
            max-width: 90%;
            width: 100%;
            max-width: 420px;
            text-align: center;
            box-shadow: 0 20px 60px rgba(0,0,0,0.5), inset 0 1px 0 rgba(232,204,122,0.1);
        }
        .shield {
            width: 80px;
            height: 80px;
            margin: 0 auto 24px;
            background: radial-gradient(circle at 50% 40%, #17402f 0%, #0a1e15 100%);
            border: 2px solid #e8cc7a;
            border-radius: 50% 50% 50% 50% / 60% 60% 40% 40%;
            display: flex;
            align-items: center;
            justify-content: center;
            position: relative;
        }
        .shield::before {
            content: "";
            position: absolute;
            width: 40px;
            height: 40px;
            background: #e8cc7a;
            clip-path: polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%);
        }
        .title {
            font-size: 22px;
            font-weight: 700;
            color: #e8cc7a;
            margin-bottom: 8px;
            letter-spacing: 0.5px;
        }
        .subtitle {
            font-size: 14px;
            color: #8fa39b;
            margin-bottom: 28px;
            line-height: 1.5;
        }
        .divider {
            width: 60px;
            height: 2px;
            background: linear-gradient(90deg, transparent, #e8cc7a, transparent);
            margin: 0 auto 24px;
        }
        .arabic {
            font-family: "Traditional Arabic", "Segoe UI", Tahoma, sans-serif;
            font-size: 20px;
            font-weight: 700;
            color: #e8cc7a;
            line-height: 1.7;
            margin-bottom: 16px;
            text-shadow: 0 2px 8px rgba(0,0,0,0.5);
        }
        .english {
            font-size: 15px;
            color: #e8efeb;
            line-height: 1.6;
            margin-bottom: 12px;
        }
        .source {
            font-size: 11px;
            color: #5a6e65;
            text-transform: uppercase;
            letter-spacing: 1px;
            font-weight: 600;
        }
        .host {
            margin-top: 24px;
            padding-top: 20px;
            border-top: 1px solid #1e3a2e;
            font-size: 13px;
            color: #8fa39b;
        }
        .host strong {
            color: #e8cc7a;
        }
        .footer {
            margin-top: 28px;
            font-size: 11px;
            color: #5a6e65;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="shield"></div>
        <div class="title">$appName</div>
        <div class="subtitle">This content has been blocked for your protection</div>
        <div class="divider"></div>
        <div class="arabic">${quote.arabic}</div>
        <div class="english">${quote.english}</div>
        <div class="source">${quote.source}</div>
        <div class="host">Blocked domain: <strong>$host</strong></div>
        <div class="footer">$appName • Guard your gaze. Protect your heart.</div>
    </div>
</body>
</html>
""".trimIndent()
    }
}