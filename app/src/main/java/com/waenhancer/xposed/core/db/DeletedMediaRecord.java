package com.waenhancer.xposed.core.db;

/** Metadata only. The media bytes always live below the module's private files directory. */
public final class DeletedMediaRecord {
    public final long id;
    public final long messageId;
    public final String storageId;
    public final String sha256;
    public final String mimeType;
    public final long sizeBytes;

    public DeletedMediaRecord(long id, long messageId, String storageId, String sha256, String mimeType, long sizeBytes) {
        this.id = id;
        this.messageId = messageId;
        this.storageId = storageId;
        this.sha256 = sha256;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }
}
