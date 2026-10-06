package dev.khanlabs.lanshare

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.TransferService

/** Quick Settings tile: turns "Receive files" on or off from the notification shade, without opening the app. */
class ReceiveTile : TileService() {
    override fun onStartListening() = paint()

    override fun onClick() {
        val on = !Engine.receiving
        Engine.setReceiving(on)
        if (on) TransferService.start(this) else TransferService.stop(this)
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val on = Engine.receiving
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Receive files"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
