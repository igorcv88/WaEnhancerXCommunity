package com.waenhancer.services;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.preference.PreferenceManager;

import com.waenhancer.BuildConfig;

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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String key = getPreferenceKey();

        if (isCustomToggle()) {
            performCustomToggle(prefs, key);
        } else {
            boolean current = prefs.getBoolean(key, getDefaultValue());
            prefs.edit().putBoolean(key, !current).apply();
        }

        syncAndRestart();
        updateTileState();
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

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        tile.setState(isTileActive(prefs) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
