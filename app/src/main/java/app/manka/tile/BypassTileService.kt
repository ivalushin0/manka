package app.manka.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.manka.MankaApp
import app.manka.core.Profiles
import kotlinx.coroutines.launch

/** Quick settings tile: turns DPI bypass on/off. */
class BypassTileService : TileService() {

    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        val app = MankaApp.of(this)
        app.prefs.enabled = !app.prefs.enabled
        render()
        app.appScope.launch { app.applier.apply() }
    }

    private fun render() {
        val tile = qsTile ?: return
        val app = MankaApp.of(this)
        tile.state = if (app.prefs.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = app.prefs.engine(Profiles.currentType(this)).title
        tile.updateTile()
    }
}
