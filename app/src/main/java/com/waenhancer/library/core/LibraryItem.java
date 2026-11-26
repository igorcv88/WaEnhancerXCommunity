package com.waenhancer.library.core;

import java.util.Map;

/** Catalog metadata for one kept media item. The bytes live in {@link BlobStore} under {@link #sha256}. */
public final class LibraryItem {
    public final long id;
    public final String uuid;
    public final long accountId;
    public final String packageName;
    public final String ownJid;
    public final String chatJid;
    public final String chatAltJid;
    public final String messageKey;
    public final boolean fromMe;
    public final String senderJid;
    public final String mediaKind;
    public final String mimeType;
    public final String sha256;
    public final long sizeBytes;
    public final long messageTimestamp;
    public final long importedAt;
    public final String basis;
    public final String caption;
    public final long trashedAt;
    public final String blobState;

    LibraryItem(Map<String, Object> row) {
        id = Sql.Rows.getLong(row, "id");
        uuid = Sql.Rows.getString(row, "uuid");
        accountId = Sql.Rows.getLong(row, "account_id");
        packageName = Sql.Rows.getString(row, "package_name");
        ownJid = Sql.Rows.getString(row, "own_jid");
        chatJid = Sql.Rows.getString(row, "chat_jid");
        chatAltJid = Sql.Rows.getString(row, "chat_alt_jid");
        messageKey = Sql.Rows.getString(row, "message_key");
        fromMe = Sql.Rows.getLong(row, "from_me") != 0;
        senderJid = Sql.Rows.getString(row, "sender_jid");
        mediaKind = Sql.Rows.getString(row, "media_kind");
        mimeType = Sql.Rows.getString(row, "mime_type");
        sha256 = Sql.Rows.getString(row, "sha256");
        sizeBytes = Sql.Rows.getLong(row, "size_bytes");
        messageTimestamp = Sql.Rows.getLong(row, "message_timestamp");
        importedAt = Sql.Rows.getLong(row, "imported_at");
        basis = Sql.Rows.getString(row, "basis");
        caption = Sql.Rows.getString(row, "caption");
        trashedAt = Sql.Rows.getLong(row, "trashed_at");
        blobState = Sql.Rows.getString(row, "state");
    }

    public boolean isTrashed() {
        return trashedAt > 0;
    }

    public boolean isAvailable() {
        return "ok".equals(blobState);
    }

    public boolean isVideo() {
        return MediaSniffer.KIND_VIDEO.equals(mediaKind);
    }
}
