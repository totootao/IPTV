package com.totootao.iptv;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 左侧电视剧列表。针对电视遥控器做了焦点高亮处理。
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {

    public interface OnClick {
        void onClick(Channel channel);
    }

    private final List<Channel> data = new ArrayList<>();
    private String activeName;
    private OnClick onClick;
    private RecyclerView rv;

    public void setOnClick(OnClick c) {
        this.onClick = c;
    }

    public void setData(List<Channel> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    public void setActive(Channel ch) {
        activeName = ch == null ? null : ch.name;
        notifyDataSetChanged();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.rv = recyclerView;
    }

    /** 让列表滚到指定频道 */
    public void scrollTo(Channel ch) {
        if (rv == null || ch == null) return;
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i) == ch) {
                final int idx = i;
                rv.post(() -> {
                    if (rv.getLayoutManager() != null) {
                        rv.getLayoutManager().scrollToPosition(idx);
                    }
                });
                return;
            }
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Channel c = data.get(position);
        h.order.setText(String.format(Locale.US, "%02d", position + 1));
        h.name.setText(c.name);
        h.meta.setText(String.format(Locale.US, "%d 集 · %s",
                c.episodes.size(), Channel.TimeUtil.humanDuration(c.totalMs)));

        boolean active = !TextUtils.isEmpty(activeName) && activeName.equals(c.name);
        h.itemView.setSelected(active);
        h.badge.setVisibility(active ? View.VISIBLE : View.GONE);
        h.name.setTextColor(h.itemView.getContext().getColor(
                active ? R.color.channel_active_text : R.color.channel_text));

        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(c);
        });
        h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.02f : 1f)
                    .scaleY(hasFocus ? 1.02f : 1f)
                    .setDuration(120).start();
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView order, name, meta, badge;

        Holder(@NonNull View itemView) {
            super(itemView);
            order = itemView.findViewById(R.id.item_order);
            name = itemView.findViewById(R.id.item_name);
            meta = itemView.findViewById(R.id.item_meta);
            badge = itemView.findViewById(R.id.item_badge);
        }
    }
}
