package com.totootao.iptv;

import android.graphics.Typeface;
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
 * 左侧悬浮电视剧列表。针对电视遥控器做了焦点高亮处理。
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {

    public interface OnClick {
        void onClick(Channel channel);
    }

    /** 列表项获得焦点时通知外部（用于让悬浮面板亮起） */
    public interface FocusSink {
        void onItemFocused();
    }

    private final List<Channel> data = new ArrayList<>();
    private String activeName;
    private OnClick onClick;
    private FocusSink focusSink;
    private RecyclerView rv;

    public void setOnClick(OnClick c) {
        this.onClick = c;
    }

    public void setFocusSink(FocusSink s) {
        this.focusSink = s;
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

    /** 让列表滚到指定频道并居中 */
    public void scrollTo(Channel ch) {
        if (rv == null || ch == null) return;
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i) == ch) {
                final int idx = i;
                rv.post(() -> {
                    if (rv.getLayoutManager() != null) {
                        rv.getLayoutManager().scrollToPosition(idx);
                        rv.getLayoutManager().smoothScrollToPosition(rv, null, idx);
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
        boolean active = !TextUtils.isEmpty(activeName) && activeName.equals(c.name);
        boolean focused = h.itemView.hasFocus();

        h.order.setText(String.format(Locale.US, "%02d", position + 1));
        h.name.setText(c.name);
        h.meta.setText(String.format(Locale.US, "%d 集 · %s",
                c.episodes.size(), Channel.TimeUtil.humanDuration(c.totalMs)));

        h.itemView.setSelected(active);

        // 左指示条：选中或聚焦时显示
        h.accent.setVisibility((active || focused) ? View.VISIBLE : View.GONE);
        // 序号在选中时染色
        h.order.setTextColor(h.itemView.getContext().getColor(
                active ? R.color.accent : R.color.text_secondary));
        // 标题颜色与字重
        h.name.setTextColor(h.itemView.getContext().getColor(
                active ? R.color.channel_active_text : R.color.channel_text));
        h.name.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        // 直播徽标
        h.badge.setVisibility(active ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClick(c);
        });
        h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            h.accent.setVisibility((hasFocus || v.isSelected()) ? View.VISIBLE : View.GONE);
            v.animate().scaleX(hasFocus ? 1.03f : 1f)
                    .scaleY(hasFocus ? 1.03f : 1f)
                    .setDuration(120).start();
            if (hasFocus && focusSink != null) focusSink.onItemFocused();
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView order, name, meta, badge;
        final View accent;

        Holder(@NonNull View itemView) {
            super(itemView);
            order = itemView.findViewById(R.id.item_order);
            name = itemView.findViewById(R.id.item_name);
            meta = itemView.findViewById(R.id.item_meta);
            badge = itemView.findViewById(R.id.item_badge);
            accent = itemView.findViewById(R.id.item_accent);
        }
    }
}
