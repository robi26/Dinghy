package ch.steigis.dinghy

import android.app.Application
import go.Seq

class DinghyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // gomobile's Go runtime needs an application context for the parts of
        // the standard library that reach into Android (temp dirs, certs).
        Seq.setContext(applicationContext)
    }
}
