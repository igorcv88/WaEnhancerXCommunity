package com.waenhancer.library.ui;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.waenhancer.R;
import com.waenhancer.library.core.LibraryItem;
import com.waenhancer.library.core.MediaLibrary;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Square thumbnail grid. Thumbnails are decoded from private files and never disk-cached. */
public final class LibraryGridAdapter extends RecyclerView.Adapter<LibraryGridAdapter.Holder> {

    public interface Listener {
        void onOpen(int position);

        void onLongPress(int position);
    }

    private final MediaLibrary library;
    private final Listener listener;
    private final List<LibraryItem> items = new ArrayList<>();

    public LibraryGridAdapter(MediaLibrary library, Listener listener) {
        this.library = library;
        this.listener = listener;
    }

    public void submit(List<LibraryItem> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    public List<LibraryItem> items() {
        return items;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout cell = new FrameLayout(parent.getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, widthMeasureSpec); // square cells
            }
        };
        cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = (int) (1 * parent.getResources().getDisplayMetrics().density);
        cell.setPadding(pad, pad, pad, pad);
        ImageView image = new ImageView(parent.getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFF202020);
        cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView badge = new ImageView(parent.getContext());
        badge.setImageResource(R.drawable.ic_play);
        badge.setColorFilter(Color.WHITE);
        int badgeSize = (int) (28 * parent.getResources().getDisplayMetrics().density);
        cell.addView(badge, new FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.CENTER));
        TextView state = new TextView(parent.getContext());
        state.setTextColor(Color.WHITE);
        state.setTextSize(11);
        state.setBackgroundColor(0xAA000000);
        state.setPadding(pad * 4, pad * 2, pad * 4, pad * 2);
        cell.addView(state, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        return new Holder(cell, image, badge, state);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LibraryItem item = items.get(position);
        holder.badge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
        if (!item.isAvailable()) {
            holder.state.setVisibility(View.VISIBLE);
            holder.state.setText(holder.itemView.getContext().getString(R.string.media_library_item_unavailable));
            Glide.with(holder.image).clear(holder.image);
            holder.image.setImageDrawable(null);
        } else {
            holder.state.setVisibility(View.GONE);
            try {
                File file = library.fileOf(item);
                Glide.with(holder.image).load(file)
                        .diskCacheStrategy(DiskCacheStrategy.NONE) // no decoded copies in the cache dir
                        .centerCrop()
                        .into(holder.image);
            } catch (java.io.IOException invalid) {
                holder.image.setImageDrawable(null);
            }
        }
        holder.itemView.setContentDescription(holder.itemView.getContext().getString(
                item.isVideo() ? R.string.media_library_video : R.string.media_library_photo));
        holder.itemView.setOnClickListener(v -> {
            int at = holder.getBindingAdapterPosition();
            if (at != RecyclerView.NO_POSITION) listener.onOpen(at);
        });
        holder.itemView.setOnLongClickListener(v -> {
            int at = holder.getBindingAdapterPosition();
            if (at != RecyclerView.NO_POSITION) listener.onLongPress(at);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ImageView badge;
        final TextView state;

        Holder(View itemView, ImageView image, ImageView badge, TextView state) {
            super(itemView);
            this.image = image;
            this.badge = badge;
            this.state = state;
        }
    }
}
