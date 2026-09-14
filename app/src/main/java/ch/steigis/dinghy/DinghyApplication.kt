package ch.steigis.dinghy

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import go.Seq

class DinghyApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // gomobile's Go runtime needs an application context for the parts of
        // the standard library that reach into Android (temp dirs, certs).
        Seq.setContext(applicationContext)
    }

    /**
     * Coil 3 ships no network fetcher by default, and every image this app
     * previews comes from the engine's localhost streaming server over HTTP,
     * so one has to be registered or nothing loads.
     *
     * Built here rather than per screen so the decoded-bitmap cache is shared:
     * the same thumbnail is otherwise re-fetched, and a fetch here means
     * pulling blocks from a peer.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}
