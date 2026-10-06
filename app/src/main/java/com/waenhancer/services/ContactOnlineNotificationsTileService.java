package com.waenhancer.services;

public class ContactOnlineNotificationsTileService extends BaseTileService {
    @Override
    protected String getPreferenceKey() {
        return "showonline";
    }

    @Override
    protected boolean getDefaultValue() {
        return false;
    }
}
