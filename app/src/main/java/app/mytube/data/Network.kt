package app.mytube.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object Network {
    /** A common desktop browser user agent, so requests don't single this app out. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // No cookie jar: nothing ties one request to the next.
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
