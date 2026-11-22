package com.kooo.evcam.settings;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.EcarxLoader;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Developer options: every permission this package declares, and the shared libraries it can
 * load. Nothing on this page grants or opens a setting.
 */
public class AppInventoryFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Context context = requireContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        build(screen, context);
        PreferenceRows.apply(screen);
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        PreferenceRows.styleList(this, 28, 16);
    }

    private void build(PreferenceScreen screen, Context context) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = packageInfo(pm, context.getPackageName());
        String[] names = info.requestedPermissions == null ? new String[0] : info.requestedPermissions;
        int[] flags = info.requestedPermissionsFlags == null ? new int[0] : info.requestedPermissionsFlags;
        int granted = 0;
        for (int i = 0; i < names.length && i < flags.length; i++) {
            if (AppInventory.granted(flags[i])) {
                granted++;
            }
        }
        Preference head = row(context, context.getString(R.string.inventory_count, names.length, granted),
                context.getString(R.string.inventory_ecarx, EcarxLoader.note()));
        screen.addPreference(head);

        PreferenceCategory permissions = category(context, R.string.inventory_permissions);
        screen.addPreference(permissions);
        if (names.length == 0) {
            permissions.addPreference(row(context, context.getString(R.string.inventory_none), ""));
        }
        Integer[] order = new Integer[names.length];
        for (int i = 0; i < names.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> names[a].compareToIgnoreCase(names[b]));
        for (int index : order) {
            int flag = index < flags.length ? flags[index] : 0;
            boolean held = AppInventory.granted(flag);
            String level = levelOf(pm, names[index]);
            String state = context.getString(held ? R.string.inventory_granted : R.string.inventory_not_granted);
            permissions.addPreference(row(context, names[index], state + " · " + level));
        }

        PreferenceCategory libraries = category(context, R.string.inventory_libraries);
        screen.addPreference(libraries);
        Set<String> shared = sharedNames(pm);
        String[] linked = linkedFiles(info.applicationInfo);
        for (String name : AppInventory.DECLARED_LIBRARIES) {
            boolean present = shared.contains(name) || pathContains(linked, name);
            libraries.addPreference(row(context, name, context.getString(
                    present ? R.string.inventory_lib_present : R.string.inventory_lib_absent)));
        }
        if (linked.length == 0) {
            libraries.addPreference(row(context, context.getString(R.string.inventory_linked_none), ""));
        } else {
            for (String path : linked) {
                libraries.addPreference(row(context, fileName(path),
                        context.getString(R.string.inventory_linked, path)));
            }
        }

        PreferenceCategory nativeLibs = category(context, R.string.inventory_native);
        screen.addPreference(nativeLibs);
        File[] natives = nativeLibs(info.applicationInfo);
        if (natives.length == 0) {
            nativeLibs.addPreference(row(context, context.getString(R.string.inventory_none), ""));
        } else {
            Arrays.sort(natives, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File lib : natives) {
                nativeLibs.addPreference(row(context, lib.getName(), lib.getPath()));
            }
        }
    }

    private static PackageInfo packageInfo(PackageManager pm, String packageName) {
        try {
            return pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_SHARED_LIBRARY_FILES);
        } catch (PackageManager.NameNotFoundException e) {
            return new PackageInfo();
        }
    }

    private String levelOf(PackageManager pm, String name) {
        try {
            PermissionInfo permission = pm.getPermissionInfo(name, 0);
            return AppInventory.protection(permission.getProtection(), permission.getProtectionFlags());
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.inventory_undefined);
        }
    }

    private static Set<String> sharedNames(PackageManager pm) {
        Set<String> names = new HashSet<>();
        String[] shared = pm.getSystemSharedLibraryNames();
        if (shared != null) {
            names.addAll(Arrays.asList(shared));
        }
        return names;
    }

    private static String[] linkedFiles(@Nullable ApplicationInfo applicationInfo) {
        if (applicationInfo == null || applicationInfo.sharedLibraryFiles == null) {
            return new String[0];
        }
        return applicationInfo.sharedLibraryFiles;
    }

    private static boolean pathContains(String[] paths, String name) {
        for (String path : paths) {
            if (path != null && path.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static File[] nativeLibs(@Nullable ApplicationInfo applicationInfo) {
        if (applicationInfo == null || applicationInfo.nativeLibraryDir == null) {
            return new File[0];
        }
        File[] files = new File(applicationInfo.nativeLibraryDir).listFiles();
        return files == null ? new File[0] : files;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private PreferenceCategory category(Context context, int title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        category.setLayoutResource(R.layout.pref_category);
        return category;
    }

    private Preference row(Context context, String title, String summary) {
        Preference preference = new Preference(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        return preference;
    }
}
