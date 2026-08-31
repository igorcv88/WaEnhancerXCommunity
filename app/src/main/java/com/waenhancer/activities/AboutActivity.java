package com.waenhancer.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.waenhancer.R;
import com.waenhancer.activities.base.BaseActivity;
import com.waenhancer.databinding.ActivityAboutBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AboutActivity extends BaseActivity {

    private ActivityAboutBinding binding;
    private ContributorAdapter adapter;
    private List<Contributor> contributorList = new ArrayList<>();

    private static final String API_URL = "https://api.github.com/repos/igorcv88/WaEnhancerXCommunity/contributors";
    private static final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // The Community fork has no Telegram destination.
        binding.btnTelegram.setVisibility(View.GONE);
        binding.btnGithub.setOnClickListener(view -> openUrl("https://github.com/igorcv88/WaEnhancerXCommunity/issues"));

        adapter = new ContributorAdapter();
        binding.rvContributors.setAdapter(adapter);

        fetchContributors();
    }

    private void fetchContributors() {
        android.content.SharedPreferences prefs = getSharedPreferences("github_api_cache", MODE_PRIVATE);
        long lastFetch = prefs.getLong("last_fetch", 0);
        String cachedJson = prefs.getString("contributors_json", null);

        if (cachedJson != null && (System.currentTimeMillis() - lastFetch < 3600000)) {
            parseContributorsAndRefresh(cachedJson);
            return;
        }

        if (binding.expressiveLoadingProgress != null) {
            binding.expressiveLoadingProgress.setVisibility(View.VISIBLE);
        }
        binding.rvContributors.setVisibility(View.GONE);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("User-Agent", "WaEnhancer Community-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull java.io.IOException e) {
                runOnUiThread(() -> {
                    if (cachedJson != null) {
                        parseContributorsAndRefresh(cachedJson);
                    } else {
                        if (binding.expressiveLoadingProgress != null) {
                            binding.expressiveLoadingProgress.setVisibility(View.GONE);
                        }
                        Toast.makeText(AboutActivity.this, "Failed to load contributors", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws java.io.IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    runOnUiThread(() -> {
                        if (cachedJson != null) {
                            parseContributorsAndRefresh(cachedJson);
                        } else {
                            if (binding.expressiveLoadingProgress != null) {
                                binding.expressiveLoadingProgress.setVisibility(View.GONE);
                            }
                            Toast.makeText(AboutActivity.this, "Error fetching contributors", Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
                    return;
                }

                try {
                    String json = response.body().string();
                    prefs.edit()
                            .putLong("last_fetch", System.currentTimeMillis())
                            .putString("contributors_json", json)
                            .apply();

                    runOnUiThread(() -> parseContributorsAndRefresh(json));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void parseContributorsAndRefresh(String json) {
        try {
            if (binding.expressiveLoadingProgress != null) {
                binding.expressiveLoadingProgress.setVisibility(View.GONE);
            }
            binding.rvContributors.setVisibility(View.VISIBLE);

            JSONArray jsonArray = new JSONArray(json);
            List<Contributor> fetchList = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                Contributor c = new Contributor();
                c.login = obj.optString("login");
                c.avatarUrl = obj.optString("avatar_url");
                c.htmlUrl = obj.optString("html_url");
                c.contributions = obj.optInt("contributions", 0);
                fetchList.add(c);
            }

            contributorList.clear();
            contributorList.addAll(fetchList);
            adapter.notifyDataSetChanged();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class Contributor {

        String login;
        String avatarUrl;
        String htmlUrl;
        int contributions;
    }

    private class ContributorAdapter extends RecyclerView.Adapter<ContributorAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contributor, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Contributor c = contributorList.get(position);

            holder.ivAvatar.clearColorFilter();
            com.bumptech.glide.Glide.with(holder.itemView.getContext())
                    .load(new com.bumptech.glide.load.model.GlideUrl(c.avatarUrl,
                            new com.bumptech.glide.load.model.LazyHeaders.Builder()
                                    .addHeader("User-Agent", "WaEnhancer Community-App")
                                    .build()))
                    .placeholder(R.drawable.ic_github)
                    .into(holder.ivAvatar);

            holder.ivAvatar.setOnClickListener(v -> com.waenhancer.ui.helpers.BottomSheetHelper
                    .showUserProfile(AboutActivity.this, c.login, c.avatarUrl, c.htmlUrl, c.contributions));
        }

        @Override
        public int getItemCount() {
            return contributorList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            ImageView ivAvatar;

            ViewHolder(View itemView) {
                super(itemView);
                ivAvatar = itemView.findViewById(R.id.ivAvatar);
            }
        }
    }
}
