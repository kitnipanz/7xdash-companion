package com.kooo.evcam.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.ScreenProbe;

import java.util.List;

/**
 * 系统 → 屏幕（试验性）：列出打得开的屏，点一行就在那块屏上写字。
 */
public class ScreenProbeFragment extends Fragment {

    private LinearLayout list;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_screen_probe, container, false);
        list = root.findViewById(R.id.screen_probe_list);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        fill();
    }

    private void fill() {
        if (list == null || getContext() == null) {
            return;
        }
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(list.getContext());
        List<ScreenProbe.Row> rows = ScreenProbe.list(requireContext());
        if (rows.isEmpty()) {
            TextView empty = new TextView(list.getContext());
            empty.setText(R.string.screen_probe_empty);
            empty.setTextColor(list.getContext().getColor(R.color.pref_summary));
            list.addView(empty);
            return;
        }
        for (ScreenProbe.Row row : rows) {
            View item = inflater.inflate(R.layout.pref_row_nav, list, false);
            TextView title = item.findViewById(android.R.id.title);
            TextView summary = item.findViewById(android.R.id.summary);
            title.setText(titleOf(row));
            summary.setText(summaryOf(row));
            item.setOnClickListener(v -> onRow(row));
            list.addView(item);
        }
    }

    private void onRow(ScreenProbe.Row row) {
        if (getContext() == null) {
            return;
        }
        if (row.closed) {
            Toast.makeText(getContext(), R.string.screen_probe_closed, Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = ScreenProbe.toggle(requireContext(), row.id);
        if (!ok) {
            String failure = ScreenProbe.failure(row.id);
            Toast.makeText(getContext(), getString(R.string.screen_probe_failed, failure),
                    Toast.LENGTH_LONG).show();
        }
        fill();
    }

    private String titleOf(ScreenProbe.Row row) {
        if (row.closed) {
            return getString(R.string.screen_probe_closed_title, row.id);
        }
        String name = row.name.isEmpty() ? "—" : row.name;
        if (row.main) {
            return getString(R.string.screen_probe_main_title, row.id, name);
        }
        return getString(R.string.screen_probe_title_row, row.id, name);
    }

    private String summaryOf(ScreenProbe.Row row) {
        if (row.closed) {
            return getString(R.string.screen_probe_closed);
        }
        String detail = row.width + "×" + row.height + " · " + row.state + " · " + row.flags;
        if (!row.uniqueId.isEmpty()) {
            detail = detail + " · " + row.uniqueId;
        }
        if (ScreenProbe.showing() == row.id) {
            return getString(R.string.screen_probe_showing, ScreenProbe.method()) + "\n" + detail;
        }
        String failure = ScreenProbe.failure(row.id);
        if (!failure.isEmpty()) {
            return getString(R.string.screen_probe_failed, failure) + "\n" + detail;
        }
        return detail;
    }
}
