package com.waenhancer.library.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.waenhancer.R;
import com.waenhancer.activities.base.BaseActivity;
import com.waenhancer.library.android.GalleryAccess;
import com.waenhancer.library.android.MediaLibraryRuntime;
import com.waenhancer.library.core.LibraryBackup;
import com.waenhancer.library.core.LibraryException;
import com.waenhancer.library.core.LibraryItem;
import com.waenhancer.library.core.MediaLibrary;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The private media library.
 *
 * <p>Two entry points share this class. Opened from the module's settings it shows every
 * account's items and offers management (quota, verification, trash, encrypted backup). Opened
 * from a WhatsApp conversation through the exported {@code ChatMediaGallery} alias, it requires a
 * single-use token from {@link GalleryAccess} and shows only that conversation of that account;
 * without a valid token it closes immediately.</p>
 */
public final class MediaLibraryActivity extends BaseActivity {
    public static final String EXTRA_TOKEN = "token";
    static final String CHAT_ALIAS = "com.waenhancer.library.ui.ChatMediaGallery";
    private static final int REQUEST_EXPORT = 41;
    private static final int REQUEST_RESTORE = 42;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaLibrary library;
    private LibraryGridAdapter adapter;
    private TextView summary;
    private TextView empty;
    private GalleryAccess.Grant grant;
    private boolean showingTrash;
    private boolean openedRequested;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean chatEntry = getComponentName() != null && CHAT_ALIAS.equals(getComponentName().getClassName());
        if (chatEntry) {
            grant = GalleryAccess.redeem(getIntent().getStringExtra(EXTRA_TOKEN), System.currentTimeMillis());
            if (grant == null) {
                finish();
                return;
            }
        }
        try {
            library = MediaLibraryRuntime.get(this);
        } catch (Exception failure) {
            Toast.makeText(this, R.string.media_library_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        com.google.android.material.appbar.MaterialToolbar toolbar = null;
        if (getSupportActionBar() == null) {
            toolbar = new com.google.android.material.appbar.MaterialToolbar(this);
            root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        summary = new TextView(this);
        summary.setPadding(pad, pad, pad, pad / 2);
        root.addView(summary);
        empty = new TextView(this);
        empty.setPadding(pad, pad, pad, pad);
        empty.setText(R.string.media_library_empty);
        root.addView(empty);
        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new LibraryGridAdapter(library, new LibraryGridAdapter.Listener() {
            @Override
            public void onOpen(int position) {
                openViewer(position);
            }

            @Override
            public void onLongPress(int position) {
                itemActions(adapter.items().get(position));
            }
        });
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        if (toolbar != null) setSupportActionBar(toolbar);
        setTitle(grant != null && grant.title != null && !grant.title.isEmpty()
                ? getString(R.string.media_library_chat_title, grant.title) : getString(R.string.media_library_title));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (library != null) reload();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void reload() {
        boolean trash = showingTrash;
        worker.execute(() -> {
            List<LibraryItem> items = grant == null ? library.listAll(trash)
                    : library.listChat(grant.packageName, grant.ownJid,
                    Arrays.asList(grant.chatJid, grant.chatAltJid), trash);
            long used = library.usedBytes();
            long quota = library.quotaBytes();
            main.post(() -> {
                if (isFinishing()) return;
                adapter.submit(items);
                openRequestedItem();
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                String label = getString(showingTrash ? R.string.media_library_trash_count : R.string.media_library_count,
                        items.size());
                summary.setText(grant == null ? label + " · " + getString(R.string.media_library_usage,
                        Formatter.formatShortFileSize(this, used), Formatter.formatShortFileSize(this, quota)) : label);
            });
        });
    }

    /** A tap on an inline copy in WhatsApp opens that item directly, once. */
    private void openRequestedItem() {
        if (grant == null || grant.openUuid == null || openedRequested) return;
        openedRequested = true;
        for (int i = 0; i < adapter.items().size(); i++) {
            if (grant.openUuid.equals(adapter.items().get(i).uuid)) {
                openViewer(i);
                return;
            }
        }
    }

    private void openViewer(int position) {
        ArrayList<String> ids = new ArrayList<>();
        for (LibraryItem item : adapter.items()) ids.add(item.uuid);
        Intent intent = new Intent(this, LibraryViewerActivity.class);
        intent.putStringArrayListExtra(LibraryViewerActivity.EXTRA_IDS, ids);
        intent.putExtra(LibraryViewerActivity.EXTRA_INDEX, position);
        startActivity(intent);
    }

    private void itemActions(LibraryItem item) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!item.isTrashed()) {
            labels.add(getString(R.string.media_library_move_to_trash));
            actions.add(() -> mutate(() -> library.trash(item.uuid), R.string.media_library_moved_to_trash));
        } else {
            labels.add(getString(R.string.media_library_restore));
            actions.add(() -> mutate(() -> library.restoreFromTrash(item.uuid), R.string.media_library_restored));
            labels.add(getString(R.string.media_library_delete_permanently));
            actions.add(() -> new AlertDialog.Builder(this)
                    .setMessage(R.string.media_library_delete_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.media_library_delete_permanently,
                            (d, w) -> mutate(() -> library.purge(item.uuid), R.string.media_library_deleted))
                    .show());
        }
        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private interface Mutation {
        boolean run() throws Exception;
    }

    private void mutate(Mutation mutation, int successMessage) {
        worker.execute(() -> {
            boolean ok;
            try {
                ok = mutation.run();
            } catch (Exception failure) {
                ok = false;
            }
            boolean done = ok;
            main.post(() -> {
                Toast.makeText(this, done ? successMessage : R.string.media_library_action_failed, Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.media_library_show_trash).setCheckable(true);
        if (grant == null) {
            menu.add(0, 2, 0, R.string.media_library_verify);
            menu.add(0, 3, 0, R.string.media_library_quota);
            menu.add(0, 4, 0, R.string.media_library_export);
            menu.add(0, 5, 0, R.string.media_library_restore_backup);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case 1:
                showingTrash = !showingTrash;
                item.setChecked(showingTrash);
                reload();
                return true;
            case 2:
                verify();
                return true;
            case 3:
                chooseQuota();
                return true;
            case 4:
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, "waenhancer-media-library.waexlib"), REQUEST_EXPORT);
                return true;
            case 5:
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_RESTORE);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void verify() {
        Toast.makeText(this, R.string.media_library_verifying, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            String message;
            try {
                MediaLibrary.ReconcileReport report = library.reconcile(true);
                message = getString(R.string.media_library_verify_result, report.verified,
                        report.markedMissing, report.markedCorrupt, report.orphanBlobsRemoved + report.stagingRemoved);
            } catch (Exception failure) {
                message = getString(R.string.media_library_action_failed);
            }
            String text = message;
            main.post(() -> {
                new AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show();
                reload();
            });
        });
    }

    private void chooseQuota() {
        long[] sizes = { 1, 2, 4, 8, 16, 32, 64, 128, 256 };
        String[] labels = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) labels[i] = Formatter.formatShortFileSize(this, sizes[i] * MediaLibrary.GIB);
        new AlertDialog.Builder(this)
                .setTitle(R.string.media_library_quota)
                .setItems(labels, (d, which) -> mutate(() -> {
                    library.setQuotaBytes(sizes[which] * MediaLibrary.GIB);
                    return true;
                }, R.string.media_library_quota_saved))
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        askPassword(requestCode == REQUEST_EXPORT, password -> {
            if (requestCode == REQUEST_EXPORT) runExport(uri, password);
            else if (requestCode == REQUEST_RESTORE) runRestore(uri, password);
        });
    }

    private interface PasswordCallback {
        void accept(char[] password);
    }

    private void askPassword(boolean confirm, PasswordCallback callback) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText first = new EditText(this);
        first.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        first.setHint(R.string.media_library_password);
        box.addView(first);
        EditText second = new EditText(this);
        second.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        second.setHint(R.string.media_library_password_repeat);
        if (confirm) box.addView(second);
        new AlertDialog.Builder(this)
                .setTitle(confirm ? R.string.media_library_export : R.string.media_library_restore_backup)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String a = first.getText().toString();
                    if (a.length() < 8 || (confirm && !a.equals(second.getText().toString()))) {
                        Toast.makeText(this, R.string.media_library_password_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    callback.accept(a.toCharArray());
                })
                .show();
    }

    private void runExport(Uri uri, char[] password) {
        Toast.makeText(this, R.string.media_library_working, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            String message;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("No output");
                LibraryBackup.ExportReport report = new LibraryBackup(library).export(out, password);
                message = getString(R.string.media_library_export_done, report.items,
                        Formatter.formatShortFileSize(this, report.bytes), report.skippedUnavailable);
            } catch (Exception failure) {
                message = describe(failure);
            } finally {
                Arrays.fill(password, '\0');
            }
            show(message);
        });
    }

    private void runRestore(Uri uri, char[] password) {
        Toast.makeText(this, R.string.media_library_working, Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            String message;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("No input");
                LibraryBackup.RestoreReport report = new LibraryBackup(library).restore(new java.io.BufferedInputStream(in), password);
                message = getString(R.string.media_library_restore_done, report.itemsRestored, report.itemsAlreadyPresent);
            } catch (Exception failure) {
                message = describe(failure);
            } finally {
                Arrays.fill(password, '\0');
            }
            show(message);
        });
    }

    private String describe(Exception failure) {
        if (failure instanceof LibraryException) {
            switch (((LibraryException) failure).code) {
                case BACKUP_INVALID: return getString(R.string.media_library_error_backup);
                case QUOTA_EXCEEDED: return getString(R.string.media_library_error_quota);
                case INSUFFICIENT_STORAGE: return getString(R.string.media_library_error_storage);
                default: break;
            }
        }
        return getString(R.string.media_library_action_failed);
    }

    private void show(String message) {
        main.post(() -> {
            if (isFinishing()) return;
            new AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
            reload();
        });
    }
}
