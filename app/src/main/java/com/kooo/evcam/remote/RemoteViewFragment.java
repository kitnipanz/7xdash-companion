package com.kooo.evcam.remote;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;
import com.kooo.evcam.share.QrCode;

/**
 * Drawer page for 7xDash remote watch: QR, link state, and whether a phone is watching.
 */
public class RemoteViewFragment extends Fragment {

    private TextView status;
    private TextView email;
    private TextView url;
    private ImageView qr;
    private View refresh;
    private String shownUrl = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_remote_view, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        status = view.findViewById(R.id.remote_status);
        email = view.findViewById(R.id.remote_email);
        url = view.findViewById(R.id.remote_url);
        qr = view.findViewById(R.id.remote_qr);
        refresh = view.findViewById(R.id.btn_remote_refresh_code);
        refresh.setOnClickListener(v -> {
            if (getActivity() instanceof com.kooo.evcam.MainActivity) {
                ((com.kooo.evcam.MainActivity) getActivity()).refreshRemoteCode();
            }
        });
        view.findViewById(R.id.btn_remote_back).setOnClickListener(v -> {
            if (getActivity() instanceof com.kooo.evcam.MainActivity) {
                ((com.kooo.evcam.MainActivity) getActivity()).goToRecordingInterface();
            }
        });
    }

    public void show(CarLink.Snapshot snap) {
        if (status == null || snap == null || !isAdded()) {
            return;
        }
        if (snap.watching) {
            status.setText(getString(R.string.remote_watching, snap.source));
            status.setTextColor(requireContext().getColor(R.color.recording_text));
        } else if (snap.url != null && !snap.url.isEmpty()) {
            status.setText(R.string.remote_idle);
            status.setTextColor(requireContext().getColor(R.color.text_secondary));
        } else {
            status.setText(snap.state == null || snap.state.isEmpty()
                    ? getString(R.string.remote_offline) : snap.state);
            status.setTextColor(requireContext().getColor(R.color.text_secondary));
        }
        if (snap.email == null || snap.email.isEmpty()) {
            email.setText("");
        } else {
            email.setText(getString(R.string.remote_email, snap.email));
        }
        url.setText(snap.url == null ? "" : snap.url);
        // Only an unlinked car shows a QR worth refreshing.
        boolean unlinked = snap.email == null || snap.email.isEmpty();
        refresh.setVisibility(unlinked && snap.url != null && !snap.url.isEmpty()
                ? View.VISIBLE : View.GONE);
        if (snap.url != null && !snap.url.equals(shownUrl)) {
            shownUrl = snap.url;
            qr.setImageBitmap(shownUrl.isEmpty() ? null : QrCode.encode(shownUrl, 560));
        }
    }
}
