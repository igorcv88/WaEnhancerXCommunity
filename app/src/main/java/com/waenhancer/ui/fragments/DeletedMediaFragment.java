package com.waenhancer.ui.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.waenhancer.xposed.core.db.DelMessageStore;
import com.waenhancer.xposed.core.db.DeletedMediaRecord;

/** Metadata-only list; opening bytes remains through the UID-authorised provider. */
public final class DeletedMediaFragment extends Fragment {
    @Nullable @Override public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        TextView list = new TextView(requireContext()); int padding=(int)(16*getResources().getDisplayMetrics().density); list.setPadding(padding,padding,padding,padding);
        StringBuilder text = new StringBuilder();
        for (DeletedMediaRecord media : DelMessageStore.getInstance(requireContext()).getDeletedMedia()) {
            text.append(media.mimeType).append(" • ").append(media.sizeBytes).append(" bytes\n").append(media.storageId).append("\n\n");
        }
        list.setText(text.length() == 0 ? "No preserved media found" : text.toString());
        return list;
    }
}
