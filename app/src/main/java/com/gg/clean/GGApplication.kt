package com.gg.clean

import android.app.Application
import android.util.Log
import com.gg.core.root.RootAccess
import com.gg.core.settings.Settings

class GGApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)

        val rootOk = RootAccess.init()
        Log.i(
            "GGClean",
            "Root available: $rootOk  (su=${RootAccess.get().getSuPath()})  " +
                "defaultType=${Settings.defaultValueType}"
        )
    }
}
