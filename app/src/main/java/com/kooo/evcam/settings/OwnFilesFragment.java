package com.kooo.evcam.settings;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.EcarxLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Developer options: the folders this process can already list, plus one file the user picks.
 * Other apps' directories are not roots and are not opened.
 */
public class OwnFilesFragment extends Fragment {

    private static final String STATE_DIR = "own_files_dir";

    private LinearLayout list;
    private TextView banner;
    private File current;
    private String picked = "";
    private OnBackPressedCallback back;

    private final ActivityResultLauncher<String[]> pickFile = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onPicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_own_files, container, false);
        list = root.findViewById(R.id.own_files_list);
        banner = root.findViewById(R.id.own_files_banner);
        if (savedInstanceState != null) {
            String path = savedInstanceState.getString(STATE_DIR);
            picked = savedInstanceState.getString("own_files_picked", "");
            if (path != null) {
                current = new File(path);
            }
        }
        back = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                current = parentInside(current);
                fill();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        fill();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (current != null) {
            outState.putString(STATE_DIR, current.getPath());
        }
        outState.putString("own_files_picked", picked);
    }

    private void fill() {
        if (list == null || banner == null || getContext() == null) {
            return;
        }
        String status = getString(R.string.own_files_banner, EcarxLoader.note());
        if (!picked.isEmpty()) {
            status = status + "\n" + picked;
        }
        banner.setText(status);
        list.removeAllViews();
        List<OwnFiles.Root> roots = OwnFiles.roots(
                requireContext().getDataDir(),
                requireContext().getExternalFilesDir(null));
        List<File> dirs = OwnFiles.directories(roots);
        if (current != null && !OwnFiles.inside(current, dirs)) {
            current = null;
        }
        if (back != null) {
            back.setEnabled(current != null);
        }
        addRow(getString(R.string.own_files_pick), getString(R.string.own_files_pick_summary),
                v -> pickFile.launch(new String[]{"*/*"}));
        if (current == null) {
            if (roots.isEmpty()) {
                addNote(getString(R.string.own_files_empty));
                return;
            }
            for (OwnFiles.Root root : roots) {
                int title = root.kind == OwnFiles.Kind.PRIVATE
                        ? R.string.own_files_private : R.string.own_files_external;
                addRow(getString(title), root.dir.getPath() + "\n" + OwnFiles.permission(root.dir),
                        v -> open(root.dir, dirs));
            }
            return;
        }
        addRow(getString(R.string.own_files_up), current.getPath(), v -> {
            current = parentInside(current);
            fill();
        });
        OwnFiles.Listing listing = OwnFiles.list(current, dirs);
        if (!listing.readable) {
            addNote(getString(R.string.own_files_unreadable));
            return;
        }
        if (listing.entries.isEmpty()) {
            addNote(getString(R.string.own_files_empty));
            return;
        }
        for (OwnFiles.Entry entry : listing.entries) {
            String perm = OwnFiles.permission(entry.file);
            String summary = entry.directory
                    ? getString(R.string.own_files_dir, perm)
                    : getString(R.string.own_files_file,
                            Formatter.formatShortFileSize(requireContext(), entry.bytes), perm);
            if (!entry.inside) {
                summary = getString(R.string.own_files_outside) + "\n" + perm;
            } else if (isApk(entry)) {
                summary = summary + "\n" + getString(R.string.own_files_tap_install);
            }
            addRow(entry.name, summary, v -> onEntry(entry, dirs));
        }
    }

    private void onEntry(OwnFiles.Entry entry, List<File> roots) {
        if (!entry.inside) {
            if (getContext() != null) {
                com.kooo.evcam.ui.Notice.makeText(getContext(), R.string.own_files_outside, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (entry.directory) {
            open(entry.file, roots);
            return;
        }
        if (isApk(entry)) {
            chooseInstaller(entry.file);
        }
    }

    /** Exported activities that already handle an apk. The user picks one. */
    private void chooseInstaller(File apk) {
        if (getContext() == null) {
            return;
        }
        if (!apk.isFile() || apk.length() == 0) {
            com.kooo.evcam.ui.Notice.makeText(getContext(), R.string.upd_apk_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", apk);
        } catch (IllegalArgumentException e) {
            com.kooo.evcam.ui.Notice.makeText(getContext(), R.string.upd_cannot_open_apk, Toast.LENGTH_LONG).show();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, "application/vnd.android.package-archive");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PackageManager pm = requireContext().getPackageManager();
        List<ResolveInfo> handlers = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(view, 0)) {
            if (info.activityInfo != null && info.activityInfo.exported) {
                handlers.add(info);
            }
        }
        if (handlers.isEmpty()) {
            com.kooo.evcam.ui.Notice.makeText(getContext(), R.string.upd_no_installer, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[handlers.size()];
        for (int i = 0; i < handlers.size(); i++) {
            CharSequence label = handlers.get(i).loadLabel(pm);
            labels[i] = label == null || label.length() == 0
                    ? handlers.get(i).activityInfo.packageName
                    : label.toString();
        }
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.own_files_install_title)
                .setItems(labels, (d, which) -> startInstaller(view, uri, handlers.get(which))));
    }

    private void startInstaller(Intent view, Uri uri, ResolveInfo info) {
        if (getContext() == null || info.activityInfo == null) {
            return;
        }
        Intent chosen = new Intent(view);
        chosen.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
        chosen.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            requireContext().grantUriPermission(info.activityInfo.packageName, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chosen);
        } catch (SecurityException e) {
            com.kooo.evcam.ui.Notice.makeText(requireContext(), R.string.upd_no_installer, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isApk(OwnFiles.Entry entry) {
        return !entry.directory && entry.name.toLowerCase(java.util.Locale.US).endsWith(".apk");
    }

    private void open(File dir, List<File> roots) {
        if (!OwnFiles.inside(dir, roots)) {
            if (getContext() != null) {
                com.kooo.evcam.ui.Notice.makeText(getContext(), R.string.own_files_outside, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        current = dir;
        fill();
    }

    @Nullable
    private File parentInside(File dir) {
        if (dir == null) {
            return null;
        }
        File parent = dir.getParentFile();
        if (parent == null || getContext() == null) {
            return null;
        }
        List<File> roots = OwnFiles.directories(OwnFiles.roots(
                requireContext().getDataDir(),
                requireContext().getExternalFilesDir(null)));
        return OwnFiles.inside(parent, roots) ? parent : null;
    }

    private void onPicked(@Nullable Uri uri) {
        if (uri == null || getContext() == null) {
            return;
        }
        String name = uri.toString();
        long bytes = -1L;
        try (Cursor cursor = requireContext().getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameCol >= 0 && !cursor.isNull(nameCol)) {
                    name = cursor.getString(nameCol);
                }
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) {
                    bytes = cursor.getLong(sizeCol);
                }
            }
        }
        String size = bytes >= 0
                ? Formatter.formatShortFileSize(requireContext(), bytes)
                : getString(R.string.own_files_size_unknown);
        picked = getString(R.string.own_files_picked, name, size);
        fill();
    }

    private void addRow(String title, String summary, View.OnClickListener click) {
        View item = LayoutInflater.from(list.getContext()).inflate(R.layout.pref_row_nav, list, false);
        TextView titleView = item.findViewById(android.R.id.title);
        TextView summaryView = item.findViewById(android.R.id.summary);
        titleView.setText(title);
        summaryView.setText(summary);
        item.setOnClickListener(click);
        list.addView(item);
    }

    private void addNote(String text) {
        TextView empty = new TextView(list.getContext());
        empty.setText(text);
        empty.setTextColor(list.getContext().getColor(R.color.pref_summary));
        list.addView(empty);
    }
}
