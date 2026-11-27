package com.waenhancer.library.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * First-run and incremental import of view-once media WhatsApp still holds.
 *
 * <p>The first time the library runs for an account, every view-once photo and video whose file
 * WhatsApp still has is imported, oldest first, in pages; afterwards each run only looks at
 * messages newer than the stored watermark, so everything that arrives later is kept too. Like a
 * database migration, it is resumable: the watermark only advances over messages that were
 * settled (imported, already present, without a file anymore, refused, or ambiguous); the first
 * transient failure stops the run so the message is retried next time, and nothing is ever
 * imported twice (imports are idempotent). If WhatsApp's database was recreated (reinstall,
 * restore, migration) and its row ids restarted below the watermark, the watermark is reset and
 * the scan starts again.</p>
 */
public final class InitialImport {
    private InitialImport() { }

    /** One view-once message from WhatsApp's database. */
    public static final class Candidate {
        public final long rowId;
        public final String chatJid;
        public final String chatAltJid;
        public final String messageKey;
        public final boolean fromMe;
        public final long timestamp;
        /** WhatsApp's file for it, {@code null} when it no longer has one. */
        public final File file;

        public Candidate(long rowId, String chatJid, String chatAltJid, String messageKey, boolean fromMe,
                         long timestamp, File file) {
            this.rowId = rowId;
            this.chatJid = chatJid;
            this.chatAltJid = chatAltJid;
            this.messageKey = messageKey;
            this.fromMe = fromMe;
            this.timestamp = timestamp;
            this.file = file;
        }
    }

    public interface Candidates {
        /** Up to {@code limit} candidates with rowId &gt; {@code afterRowId}, ascending. */
        List<Candidate> page(long afterRowId, int limit) throws IOException;

        /** Highest message row id WhatsApp currently has (any type). */
        long maxRowId() throws IOException;
    }

    public enum Settled { IMPORTED, ALREADY_PRESENT, REFUSED, AMBIGUOUS }

    public interface Importer {
        /** Imports one candidate; throws on a transient failure (retried next run). */
        Settled importOne(Candidate candidate) throws IOException;
    }

    public interface Progress {
        MediaLibrary.SweepState load() throws IOException;

        void save(MediaLibrary.SweepState state) throws IOException;
    }

    public static final class Report {
        public int scanned;
        public int imported;
        public int alreadyPresent;
        public int withoutFile;
        public int refused;
        public int ambiguous;
        public int failed;
        public boolean watermarkReset;
        public boolean initialRun;
        public boolean completed;
        public long watermark;
    }

    public static Report run(Candidates candidates, Importer importer, Progress progress, int pageSize,
                             BooleanSupplier cancelled) throws IOException {
        Report report = new Report();
        MediaLibrary.SweepState state = progress.load();
        long watermark = state.watermark;
        long imported = state.imported;
        report.initialRun = !state.initialDone;
        if (watermark > 0 && candidates.maxRowId() < watermark) {
            watermark = 0;
            report.watermarkReset = true;
        }
        boolean stopped = false;
        while (!stopped) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                stopped = true;
                break;
            }
            List<Candidate> page = candidates.page(watermark, pageSize);
            if (page.isEmpty()) break;
            for (Candidate candidate : page) {
                if (candidate.rowId <= watermark) continue; // defensive: never go backwards
                report.scanned++;
                if (candidate.file == null || !candidate.file.isFile() || candidate.file.length() == 0) {
                    report.withoutFile++;
                } else {
                    try {
                        switch (importer.importOne(candidate)) {
                            case IMPORTED: report.imported++; imported++; break;
                            case ALREADY_PRESENT: report.alreadyPresent++; break;
                            case REFUSED: report.refused++; break;
                            case AMBIGUOUS: report.ambiguous++; break;
                        }
                    } catch (IOException | RuntimeException transientFailure) {
                        report.failed++;
                        stopped = true; // retry from this message next run
                        break;
                    }
                }
                watermark = candidate.rowId;
            }
            progress.save(new MediaLibrary.SweepState(watermark, state.initialDone, imported));
        }
        boolean done = !stopped || state.initialDone;
        report.completed = !stopped;
        progress.save(new MediaLibrary.SweepState(watermark, done, imported));
        report.watermark = watermark;
        return report;
    }
}
