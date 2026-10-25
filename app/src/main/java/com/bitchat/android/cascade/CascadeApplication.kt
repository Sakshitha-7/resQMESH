package com.bitchat.android.cascade

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CascadeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()
        com.bitchat.android.ui.debug.DebugPreferenceManager.init(this)
        com.bitchat.android.ui.theme.ThemePreferenceManager.init(this)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        com.bitchat.android.service.MeshServicePreferences.init(this)
        com.bitchat.android.wifiaware.WifiAwareController.initialize(this,false)
        // Do not initialize upstream Nostr, Tor, location channels, or chat services.
    }
}
