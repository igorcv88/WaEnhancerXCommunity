package com.waenhancer.services;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.waenhancer.BuildConfig;
import com.waenhancer.config.PreferenceStores;

public abstract class BaseTileService extends TileService {

    protected abstract String getPreferenceKey();
    protected abstract boolean getDefaultValue();

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        String key = getPreferenceKey();
        SharedPreferences prefs = store();

        if (isCustomToggle()) {
            performCustomToggle(prefs, key);
        } else {
            boolean current = prefs.getBoolean(key, getDefaultValue());
            prefs.edit().putBoolean(key, !current).apply();
        }

        syncAndRestart();
        updateTileState();
    }

    /**
     * The file the schema assigns this tile's key to. A tile must toggle the same copy the
     * settings screen edits and, for hook-read keys, the copy HookProvider serves to WhatsApp.
     */
    private SharedPreferences store() {
        return PreferenceStores.storeFor(this, getPreferenceKey());
    }

    protected boolean isCustomToggle() {
        return false;
    }

    protected void performCustomToggle(SharedPreferences prefs, String key) {
        // Override for custom toggle behavior.
    }

    protected boolean isTileActive(SharedPreferences prefs) {
        return prefs.getBoolean(getPreferenceKey(), getDefaultValue());
    }

    protected void syncAndRestart() {
        try {
            getContentResolver().notifyChange(
                    Uri.parse("content://" + BuildConfig.APPLICATION_ID
                            + ".hookprovider/preferences"),
                    null);
        } catch (RuntimeException ignored) {
        }

        restartPackage("com.whatsapp");
        restartPackage("com.whatsapp.w4b");
    }

    private void restartPackage(String packageName) {
        try {
            Intent intent = new Intent(BuildConfig.APPLICATION_ID + ".WHATSAPP.RESTART");
            intent.putExtra("PKG", packageName);
            sendBroadcast(intent);
        } catch (RuntimeException ignored) {
        }
    }

    protected void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        SharedPreferences prefs = store();
        tile.setState(isTileActive(prefs) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
