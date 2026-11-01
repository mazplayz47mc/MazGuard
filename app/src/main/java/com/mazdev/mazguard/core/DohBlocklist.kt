package com.mazdev.mazguard.core

/**
 * Public filter lists have well-known addresses. Sending those /32 and /128
 * routes into the tunnel and dropping the packets forces apps that hardcode an
 * encrypted DNS resolver to fall back to the system resolver (which is filtered).
 *
 * This costs nothing: no traffic is proxied, only these specific endpoints are
 * black-holed.
 */
object DohBlocklist {

    val IPV4: List<String> = listOf(
        "1.1.1.1", "1.0.0.1",                       // Cloudflare
        "8.8.8.8", "8.8.4.4",                       // Google
        "9.9.9.9", "149.112.112.112",               // Quad9
        "208.67.222.222", "208.67.220.220",         // OpenDNS
        "94.140.14.14", "94.140.15.15",             // AdGuard
        "94.140.14.140", "94.140.14.141",           // AdGuard family
        "76.76.2.0", "76.76.10.0",                  // ControlD
        "185.228.168.9", "185.228.169.9",           // CleanBrowsing
        "156.154.70.1", "156.154.71.1",             // Neustar
        "64.6.64.6", "64.6.65.6",                   // Verisign
        "77.88.8.8", "77.88.8.1",                   // Yandex
        "84.200.69.80", "84.200.70.40",             // DNS.WATCH
        "146.185.167.43", "5.9.164.112",            // Digitalcourage
        "176.103.130.130", "176.103.130.131"        // AdGuard DNS
    )

    val IPV6: List<String> = listOf(
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2620:fe::fe", "2620:fe::9",
        "2620:119:35::35", "2620:119:53::53",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
        "2a02:6b8::feed:0ff", "2a02:6b8:0:1::feed:0ff",
        "2001:1608:10:25::1c04:b12f", "2001:1608:10:25::9249:d69b"
    )
}
