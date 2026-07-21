package io.github.diet103.lector

import android.app.Application
import io.github.diet103.lector.app.AppContainer

class LectorApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
