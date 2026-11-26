package com.waenhancer.library.core;

import java.io.IOException;

/** A media library operation that was refused or failed, with a stable machine-readable code. */
public final class LibraryException extends IOException {

    public enum Code {
        /** The policy does not allow this media to be kept (for example received view-once). */
        NOT_AUTHORIZED,
        /** The bytes are not a supported photo or video. */
        UNSUPPORTED_MEDIA,
        /** The file is truncated, inconsistent or cannot be decoded; nothing was stored. */
        INCOMPLETE_MEDIA,
        /** The message matches more than one stored item; nothing is chosen silently. */
        AMBIGUOUS,
        /** The library quota would be exceeded after deduplication. */
        QUOTA_EXCEEDED,
        /** The device does not have enough free space beyond the safety reserve. */
        INSUFFICIENT_STORAGE,
        /** A single item is larger than the per-item limit. */
        TOO_LARGE,
        /** The request metadata is incomplete or malformed. */
        INVALID_REQUEST,
        /** Stored bytes do not match their recorded SHA-256. */
        INTEGRITY,
        /** The requested item does not exist (or belongs to another account). */
        NOT_FOUND,
        /** The catalog was written by a newer schema; it is left untouched. */
        SCHEMA_TOO_NEW,
        /** A backup could not be authenticated, parsed or validated. */
        BACKUP_INVALID,
        /** Any other I/O failure. */
        IO
    }

    public final Code code;

    public LibraryException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public LibraryException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
