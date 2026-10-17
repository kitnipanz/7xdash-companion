package com.kooo.evcam.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;
import com.kooo.evcam.settings.speaker.SpeakerBench;

import java.util.ArrayList;
import java.util.List;

public class SpeakerProbeFragment extends Fragment {

    private SpeakerBench bench;
    private SpeakerBench.PlayState shown = SpeakerBench.PlayState.Idle.instance();
    private LinearLayout list;
    private TextView carNote;
    private TextView carDetail;
    private final List<Row> rows = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_speaker_probe, container, false);
        list = root.findViewById(R.id.speaker_probe_list);
        carNote = root.findViewById(R.id.speaker_probe_car_note);
        carDetail = root.findViewById(R.id.speaker_probe_car_detail);
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        bench = SpeakerBench.open(requireContext());
        bench.setListener(this::render);
        show(bench.list());
    }

    @Override
    public void onStop() {
        if (bench != null) {
            bench.close();
            bench = null;
        }
        super.onStop();
    }

    private void render(SpeakerBench.PlayState next) {
        shown = next;
        if (!isAdded() || list == null) {
            return;
        }
        paint();
    }

    private void show(SpeakerBench.Snapshot snapshot) {
        if (list == null) {
            return;
        }
        carNote.setText(carText(snapshot.carAudio));
        if (snapshot.carAudioDetail.isEmpty()) {
            carDetail.setVisibility(View.GONE);
            carDetail.setText("");
        } else {
            carDetail.setVisibility(View.VISIBLE);
            carDetail.setText(snapshot.carAudioDetail);
        }
        list.removeAllViews();
        rows.clear();
        LayoutInflater inflater = LayoutInflater.from(list.getContext());
        for (SpeakerBench.OutputBinding binding : snapshot.routes()) {
            View row = inflater.inflate(R.layout.item_speaker_probe_row, list, false);
            SpeakerBench.Report report = binding.report;
            ((TextView) row.findViewById(R.id.speaker_probe_facts)).setText(facts(report));
            ((TextView) row.findViewById(R.id.speaker_probe_meta)).setText(meta(report));
            row.setOnClickListener(v -> {
                if (bench != null) {
                    bench.play(binding.id);
                }
            });
            list.addView(row);
            rows.add(new Row(binding.id,
                    row.findViewById(R.id.speaker_probe_status),
                    row.findViewById(R.id.speaker_probe_detail)));
        }
        paint();
    }

    private void paint() {
        for (Row row : rows) {
            boolean here = same(row.id);
            if (!here) {
                row.status.setVisibility(View.GONE);
                row.detail.setVisibility(View.GONE);
                continue;
            }
            String status = statusText();
            if (status.isEmpty()) {
                row.status.setVisibility(View.GONE);
            } else {
                row.status.setVisibility(View.VISIBLE);
                row.status.setText(status);
            }
            String detail = detailText();
            if (detail.isEmpty()) {
                row.detail.setVisibility(View.GONE);
            } else {
                row.detail.setVisibility(View.VISIBLE);
                row.detail.setText(detail);
            }
        }
    }

    private boolean same(SpeakerBench.BindingId id) {
        if (shown instanceof SpeakerBench.PlayState.Sounding) {
            return id.equals(((SpeakerBench.PlayState.Sounding) shown).id);
        }
        if (shown instanceof SpeakerBench.PlayState.Completed) {
            return id.equals(((SpeakerBench.PlayState.Completed) shown).id);
        }
        if (shown instanceof SpeakerBench.PlayState.Failed) {
            return id.equals(((SpeakerBench.PlayState.Failed) shown).id);
        }
        return false;
    }

    private String statusText() {
        if (shown instanceof SpeakerBench.PlayState.Sounding) {
            return getString(R.string.speaker_probe_sounding);
        }
        if (shown instanceof SpeakerBench.PlayState.Completed) {
            return getString(R.string.speaker_probe_completed);
        }
        if (shown instanceof SpeakerBench.PlayState.Failed) {
            return getString(failText(((SpeakerBench.PlayState.Failed) shown).reason));
        }
        return "";
    }

    private String detailText() {
        if (shown instanceof SpeakerBench.PlayState.Failed) {
            return ((SpeakerBench.PlayState.Failed) shown).detail;
        }
        return "";
    }

    private String facts(SpeakerBench.Report report) {
        int kind = report.mechanism == SpeakerBench.Mechanism.DEVICE
                ? R.string.speaker_probe_kind_device
                : R.string.speaker_probe_kind_zone;
        return getString(kind) + " " + report.platformId;
    }

    private static String meta(SpeakerBench.Report report) {
        return report.androidType + " · " + report.address + " · " + report.productName
                + " · " + report.channelCounts + " · " + report.zone + " · " + report.usage;
    }

    private static int carText(SpeakerBench.CarAudioNote note) {
        switch (note) {
            case LISTED:
                return R.string.speaker_probe_car_listed;
            case SERVICE_UNAVAILABLE:
                return R.string.speaker_probe_car_unavailable;
            case QUERY_FAILED:
                return R.string.speaker_probe_car_failed;
            case CLASS_MISSING:
            default:
                return R.string.speaker_probe_car_missing;
        }
    }

    private static int failText(SpeakerBench.Fail reason) {
        switch (reason) {
            case DISAPPEARED:
                return R.string.speaker_probe_fail_disappeared;
            case NOT_PCM:
                return R.string.speaker_probe_fail_pcm;
            case TRACK_INIT:
                return R.string.speaker_probe_fail_track;
            case WRITE:
                return R.string.speaker_probe_fail_write;
            case PREFERRED_DEVICE:
                return R.string.speaker_probe_fail_preferred;
            case ZONE_DENIED:
                return R.string.speaker_probe_fail_zone_denied;
            case ZONE_FAILED:
                return R.string.speaker_probe_fail_zone;
            case ROUTE_MISMATCH:
                return R.string.speaker_probe_fail_routed;
            case UNKNOWN_ROUTE:
            default:
                return R.string.speaker_probe_fail_unknown;
        }
    }

    private static final class Row {
        final SpeakerBench.BindingId id;
        final TextView status;
        final TextView detail;

        Row(SpeakerBench.BindingId id, TextView status, TextView detail) {
            this.id = id;
            this.status = status;
            this.detail = detail;
        }
    }
}
