package app.mytube.data

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

object Network {
    /** A common desktop browser user agent, so requests don't single this app out. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /**
     * YouTube video links are locked to the IP address that requested them. Many VPNs tunnel
     * IPv4 but let IPv6 bypass the tunnel (or the reverse), so fetching the page over one and the
     * video over the other gets a 403. Preferring IPv4 everywhere keeps both on the same address.
     */
    private val ipv4First = Dns { host ->
        val all: List<InetAddress> = Dns.SYSTEM.lookup(host)
        val v4 = all.filterIsInstance<Inet4Address>()
        if (v4.isNotEmpty()) v4 else all
    }

    // No cookie jar: nothing ties one request to the next.
    // Timeouts are generous because VPN hops add latency.
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4First)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
