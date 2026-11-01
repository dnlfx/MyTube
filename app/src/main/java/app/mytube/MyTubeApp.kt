package app.mytube

import android.app.Application
import app.mytube.data.History
import app.mytube.data.Network
import app.mytube.data.OkHttpDownloader
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import java.util.Locale
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class MyTubeApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        History.init(this)
        app.mytube.data.Subscriptions.init(this)
        app.mytube.data.SearchHistory.init(this)
        app.mytube.data.WatchLater.init(this)
        app.mytube.data.ShortsHistory.init(this)
        app.mytube.player.CodecSupport.init(this)
        val locale = Locale.getDefault()
        NewPipe.init(
            OkHttpDownloader(Network.client),
            // Always ask YouTube for English text: view counts and dates are parsed from it, and
            // that parsing is most reliable in English. Results still follow the device's country.
            Localization("en", "US"),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Network.client })) }
            .crossfade(220)
            .build()
}
