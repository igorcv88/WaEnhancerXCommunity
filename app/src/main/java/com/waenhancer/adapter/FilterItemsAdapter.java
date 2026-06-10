package com.waenhancer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.waenhancer.R;

import java.util.List;

public class FilterItemsAdapter extends RecyclerView.Adapter<FilterItemsAdapter.FilterViewHolder> {

    public interface OnFilterDeleteListener {
        void onDelete(int position);
    }

    private final List<String> filters;
    private final OnFilterDeleteListener deleteListener;

    public FilterItemsAdapter(List<String> filters, OnFilterDeleteListener deleteListener) {
        this.filters = filters;
        this.deleteListener = deleteListener;
    }

    @NonNull
    @Override
    public FilterViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_filter_id, parent, false);
        return new FilterViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FilterViewHolder holder, int position) {
        String filterId = filters.get(position);
        holder.bind(filterId, position, deleteListener);
    }

    @Override
    public int getItemCount() {
        return filters.size();
    }

    static class FilterViewHolder extends RecyclerView.ViewHolder {
        private final TextView filterText;
        private final ImageButton deleteBtn;

        FilterViewHolder(@NonNull View itemView) {
            super(itemView);
            filterText = itemView.findViewById(R.id.filter_id_text);
            deleteBtn = itemView.findViewById(R.id.btn_delete_filter);
        }

        void bind(String filterId, int position, OnFilterDeleteListener listener) {
            filterText.setText(filterId);
            deleteBtn.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDelete(position);
                }
            });
        }
    }
}
