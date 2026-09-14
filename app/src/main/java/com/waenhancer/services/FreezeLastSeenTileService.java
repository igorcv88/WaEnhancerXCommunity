package com.waenhancer.services;

public class FreezeLastSeenTileService extends BaseTileService {
    @Override
    protected String getPreferenceKey() {
        return "freezelastseen";
    }

    @Override
    protected boolean getDefaultValue() {
        return false;
    }
}
