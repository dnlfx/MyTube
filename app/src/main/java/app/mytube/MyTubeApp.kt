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
        app.mytube.player.CodecSupport.init(this)
        val locale = Locale.getDefault()
        NewPipe.init(
            OkHttpDownloader(Network.client),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Network.client })) }
            .crossfade(220)
            .build()
}
