package com.kooo.evcam.telemetry;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.share.PhoneShare;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.Notice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Experimental: every signal the car interface offers, live. Each id is read the way that answers
 * (car function, sensor event or sensor value), all of them about once a second (slower when a
 * pass takes long, so the car interface is never kept busy), and the ones that push are also
 * watched by callback. A signal that changes moves to the top and lights up.
 *
 * <p>Search filters by name or id; Changed shows only signals that moved since the screen opened;
 * Pause freezes the list (the log goes on); Mark writes a line in the log ("pressed the button
 * now"). Save writes the log to the USB drive (and the app's own folder); Phone sends it to the
 * phone.</p>
 *
 * <p>Tapping a car function row sends a value back to the car: only in P and standing still
 * ({@link SignalBoard#sendBlock}), after a confirmation, and every send goes to the black box.
 * Sent is not done: the row reads the value back.</p>
 */
public class CarSignalsActivity extends AppCompatActivity {

    private static final String TAG = "CarSignals";
    private static final String TELEMETRY_USER = "car-signals";
    private static final String DIR = "car-signals";
    private static final String USB_DIR = "7xDash/signals";
    private static final int KEEP_FILES = 5;
    static final long MIN_PASS_MS = 1_000L;
    /** A row that changed this recently is lit. */
    private static final long LIT_MS = 3_000L;

    private final HandlerThread thread = new HandlerThread("car-signals");
    private Handler work;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private TextView status;
    private MaterialButton changedButton;
    private MaterialButton pauseButton;
    private final Adapter adapter = new Adapter();
    private int dp;

    private volatile String query = "";
    private volatile boolean changedOnly;
    private volatile boolean running;
    private boolean paused;
    private int marks;

    // Worker thread only
    private CarAmbientLab.Reader reader;
    private SignalBoard board;
    /** Every int constant, for the named values a function takes. */
    private List<CarAmbientLab.Constant> constants = new ArrayList<>();
    private String scanStatus = "";
    private long lastPassMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_signals);
        dp = Math.round(getResources().getDisplayMetrics().density);
        thread.start();
        work = new Handler(thread.getLooper());
        status = findViewById(R.id.cs_status);
        changedButton = findViewById(R.id.cs_changed);
        pauseButton = findViewById(R.id.cs_pause);
        RecyclerView rows = findViewById(R.id.cs_rows);
        rows.setLayoutManager(new LinearLayoutManager(this));
        rows.setItemAnimator(null);
        rows.setAdapter(adapter);
        findViewById(R.id.cs_close).setOnClickListener(v -> finish());
        EditText search = findViewById(R.id.cs_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                refreshSoon();
            }
        });
        changedButton.setOnClickListener(v -> {
            changedOnly = !changedOnly;
            changedButton.setText(changedOnly ? R.string.car_signals_show_all : R.string.car_signals_changed_only);
            refreshSoon();
        });
        pauseButton.setOnClickListener(v -> {
            paused = !paused;
            pauseButton.setText(paused ? R.string.car_signals_resume : R.string.car_signals_pause);
            refreshSoon();
        });
        findViewById(R.id.cs_mark).setOnClickListener(v -> {
            String note = getString(R.string.car_signals_mark_line, ++marks);
            long wall = System.currentTimeMillis();
            work.post(() -> {
                if (board != null) {
                    board.mark(wall, note);
                }
            });
            Notice.makeText(this, note, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.cs_save).setOnClickListener(v -> save(false));
        findViewById(R.id.cs_phone).setOnClickListener(v -> save(true));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Telemetry.get().acquire(this, TELEMETRY_USER);
        running = true;
        work.post(this::pass);
    }

    @Override
    protected void onStop() {
        running = false;
        work.removeCallbacksAndMessages(null);
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        work.post(() -> {
            if (reader != null) {
                reader.unwatch();
            }
        });
        thread.quitSafely();
        super.onDestroy();
    }

    // ================================================================= worker

    /** Opens the car interface and reads every id once. False when there is no car interface. */
    private boolean begin() {
        CarAmbientLab.Scan scan = CarAmbientLab.scan(getApplicationContext());
        reader = scan.carReady ? CarAmbientLab.Reader.open(getApplicationContext()) : null;
        if (reader == null || scan.functions.isEmpty()) {
            String line = getString(R.string.car_signals_no_car, scan.status);
            main.post(() -> status.setText(line));
            return false;
        }
        constants = scan.all;
        scanStatus = scan.status;
        SignalBoard b = new SignalBoard();
        for (CarAmbientLab.Constant k : scan.functions) {
            for (int w = 0; w < 3; w++) {
                String v = reader.read(w, k.value);
                if (SignalRecorder.usable(v)) {
                    b.add(new SignalBoard.Entry(k.value, k.name, k.owner, w, v));
                    break;
                }
            }
        }
        board = b;
        BlackBox.note("Car signals screen: " + b.size() + " of " + scan.functions.size() + " readable");
        watchPushed();
        return true;
    }

    /** Asks the car to push the ids it may only push (blind spot, doors, turn signal and so on). */
    private void watchPushed() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < board.size(); i++) {
            SignalBoard.Entry e = board.get(i);
            if (e.sendable() && SignalRecorder.pushedCandidate(e.name) && !ids.contains(e.id)) {
                ids.add(e.id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        int[] list = new int[ids.size()];
        for (int i = 0; i < list.length; i++) {
            list[i] = ids.get(i);
        }
        String result = reader.watch(list, new CarAmbientLab.Reader.Pushed() {
            @Override
            public void value(int id, int zone, Object value) {
                long now = SystemClock.elapsedRealtime();
                long wall = System.currentTimeMillis();
                String text = String.valueOf(value);
                work.post(() -> {
                    if (board != null && board.pushed(id, zone, text, now, wall, situation())) {
                        show();
                    }
                });
            }

            @Override
            public void other(String method, Object[] args) {
                long wall = System.currentTimeMillis();
                String text = method + " " + Arrays.toString(args);
                work.post(() -> {
                    if (board != null) {
                        board.mark(wall, "callback " + text);
                    }
                });
            }
        });
        AppLog.i(TAG, "callback " + result);
    }

    private void pass() {
        if (!running) {
            return;
        }
        if (board == null && !begin()) {
            return;
        }
        long started = SystemClock.uptimeMillis();
        long now = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        String situation = situation();
        for (int i = 0; i < board.size(); i++) {
            SignalBoard.Entry e = board.get(i);
            String v = reader.read(e.way, e.id);
            if (v != null) {
                board.update(i, v, now, wall, situation);
            }
        }
        lastPassMs = SystemClock.uptimeMillis() - started;
        show();
        work.postDelayed(this::pass, Math.max(MIN_PASS_MS, 2 * lastPassMs));
    }

    private static String situation() {
        return BlindSpotLog.context(Telemetry.get().latest());
    }

    /** Sends what the screen should show now. Worker thread. */
    private void show() {
        if (board == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        List<Shown> list = new ArrayList<>();
        for (SignalBoard.Entry e : board.rows(query, changedOnly)) {
            list.add(new Shown(e, now));
        }
        String line = getString(R.string.car_signals_status, board.size(), board.totalChanges(), lastPassMs);
        main.post(() -> {
            if (!running) {
                return;
            }
            status.setText(paused ? getString(R.string.car_signals_paused, line) : line);
            if (!paused) {
                adapter.set(list);
            }
        });
    }

    private void refreshSoon() {
        work.post(this::show);
    }

    // ================================================================= send

    private void askSend(Shown row) {
        if (!row.sendable) {
            Notice.makeText(this, R.string.car_signals_read_only, Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24 * dp, 8 * dp, 24 * dp, 0);
        TextView about = new TextView(this);
        about.setTextAppearance(R.style.TextAppearance_Cam_Body);
        about.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        about.setText(getString(R.string.car_signals_send_about, row.hex, row.value));
        box.addView(about);
        EditText input = CamDialogs.input(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(R.string.car_signals_send_hint);
        Integer now = SignalBoard.parseValue(row.value);
        if (now != null) {
            input.setText(String.valueOf(now));
        }
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = 12 * dp;
        box.addView(input, ip);
        ChipGroup chips = new ChipGroup(this);
        chips.setChipSpacingHorizontal(8 * dp);
        chips.setChipSpacingVertical(8 * dp);
        Integer before = SignalBoard.parseValue(row.before);
        if (before != null) {
            chips.addView(chip(getString(R.string.car_signals_send_before, before), v -> input.setText(String.valueOf(before))));
        }
        for (CarAmbientLab.Constant option : row.options) {
            chips.addView(chip(option.name + " = " + option.value, v -> input.setText(String.valueOf(option.value))));
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = 12 * dp;
        if (chips.getChildCount() > 0) {
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.addView(chips);
            if (chips.getChildCount() > 6) {
                cp.height = 220 * dp;
            }
            box.addView(scroll, cp);
        }
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(row.name)
                .setView(box)
                .setPositiveButton(R.string.car_signals_send, (d, w) -> {
                    Integer value = SignalBoard.parseValue(input.getText().toString());
                    if (value == null) {
                        Notice.makeText(this, R.string.car_signals_send_not_number, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    send(row.id, value);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void send(int id, int value) {
        VehicleState s = Telemetry.get().latest();
        String blocked = SignalBoard.sendBlock(s == null ? null : s.gear, s == null ? null : s.speedKmh);
        if (blocked != null) {
            BlackBox.note("Car signals screen: send " + String.format(Locale.US, "0x%08X", id) + " = " + value
                    + " refused, " + blocked);
            Notice.makeText(this, getString(R.string.car_signals_send_parked, blocked), Toast.LENGTH_LONG).show();
            return;
        }
        work.post(() -> {
            SignalBoard.Entry e = find(id);
            if (e == null || reader == null) {
                return;
            }
            String failed = CarAmbientLab.write(getApplicationContext(), id, value);
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String reads = reader.read(e.way, id);
            String result = (failed == null ? "sent" : failed) + " · reads " + reads;
            long wall = System.currentTimeMillis();
            board.sent(wall, e, value, result);
            if (reads != null) {
                for (int i = 0; i < board.size(); i++) {
                    if (board.get(i) == e) {
                        board.update(i, reads, SystemClock.elapsedRealtime(), wall, situation());
                    }
                }
            }
            String line = "Car signals screen: " + e.name + " (" + e.hex() + ") = " + value + " → " + result;
            BlackBox.note(line);
            AppLog.i(TAG, line);
            String shown = failed == null
                    ? getString(R.string.car_signals_sent, e.name, value, reads)
                    : getString(R.string.car_signals_send_failed, e.name, failed);
            main.post(() -> Notice.makeText(this, shown, Toast.LENGTH_LONG).show());
            show();
        });
    }

    private SignalBoard.Entry find(int id) {
        if (board == null) {
            return null;
        }
        for (int i = 0; i < board.size(); i++) {
            if (board.get(i).id == id) {
                return board.get(i);
            }
        }
        return null;
    }

    // ================================================================= save

    /** Writes the log to the app's folder and the USB drive; then sends it to the phone when asked. */
    private void save(boolean toPhone) {
        work.post(() -> {
            if (board == null) {
                main.post(() -> Notice.makeText(this, R.string.car_signals_nothing, Toast.LENGTH_SHORT).show());
                return;
            }
            String header = "7xDash " + BuildConfig.VERSION_NAME + " live car signals, saved "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\nCar interface: " + scanStatus + "\nCar now: " + situation();
            String text = board.export(header);
            String name = "live-" + stamp.format(new Date()) + ".txt";
            File dir = new File(getFilesDir(), DIR);
            File own = new File(dir, name);
            boolean ok = (dir.isDirectory() || dir.mkdirs()) && write(own, text);
            trim(dir);
            File usb = null;
            File root = StorageHelper.getExternalSdCardRoot(getApplicationContext());
            if (root != null && root.isDirectory()) {
                File usbDir = new File(root, USB_DIR);
                File target = new File(usbDir, name);
                if ((usbDir.isDirectory() || usbDir.mkdirs()) && write(target, text)) {
                    usb = target;
                }
            }
            File saved = ok ? own : null;
            String where = usb != null ? USB_DIR + "/" + name : null;
            main.post(() -> {
                if (saved == null && where == null) {
                    Notice.makeText(this, R.string.car_signals_save_failed, Toast.LENGTH_LONG).show();
                } else if (toPhone && saved != null) {
                    PhoneShare.show(this, saved);
                } else {
                    Notice.makeText(this, where != null ? getString(R.string.car_signals_saved_usb, where)
                            : getString(R.string.car_signals_saved_own), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static boolean write(File file, String text) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(text);
            return true;
        } catch (IOException e) {
            AppLog.w(TAG, "cannot write " + file + ": " + e.getMessage());
            return false;
        }
    }

    /** Keeps the newest {@link #KEEP_FILES} in the app's folder. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP_FILES) {
            return;
        }
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = KEEP_FILES; i < files.length; i++) {
            if (!files[i].delete()) {
                AppLog.w(TAG, "cannot delete " + files[i]);
            }
        }
    }

    // ================================================================= list

    /** One row as shown: a copy, so the worker can go on changing the board. */
    private final class Shown {
        final int id;
        final String name;
        final String hex;
        final String detail;
        final String value;
        final String before;
        final long changedAgoMs;
        final int changes;
        final boolean sendable;
        final List<CarAmbientLab.Constant> options;

        Shown(SignalBoard.Entry e, long now) {
            id = e.id;
            name = e.name;
            hex = e.hex();
            detail = e.hex() + "  ·  " + e.owner + "  ·  " + CarAmbientLab.Reader.name(e.way);
            value = e.value;
            before = e.before;
            changedAgoMs = e.changedAt == 0L ? -1L : now - e.changedAt;
            changes = e.changes;
            sendable = e.sendable();
            options = sendable ? optionsFor(e) : new ArrayList<>();
        }
    }

    private final Map<Integer, List<CarAmbientLab.Constant>> optionCache = new HashMap<>();

    /** Named values that likely go with this function, such as {@code SEAT_HEAT_LEVEL_1}. Worker thread. */
    private List<CarAmbientLab.Constant> optionsFor(SignalBoard.Entry e) {
        List<CarAmbientLab.Constant> cached = optionCache.get(e.id);
        if (cached == null) {
            CarAmbientLab.Constant k = null;
            for (CarAmbientLab.Constant c : constants) {
                if (c.value == e.id && c.name.equals(e.name) && c.owner.equals(e.owner)) {
                    k = c;
                    break;
                }
            }
            Map<String, CarAmbientLab.Constant> unique = new LinkedHashMap<>();
            if (k != null) {
                for (CarAmbientLab.Constant o : CarAmbientLab.optionsFor(k, constants)) {
                    unique.putIfAbsent(o.name, o);
                }
            }
            cached = new ArrayList<>(unique.values());
            optionCache.put(e.id, cached);
        }
        return cached;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private List<Shown> rows = new ArrayList<>();

        void set(List<Shown> list) {
            rows = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(newRow());
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Shown s = rows.get(position);
            h.name.setText(s.name);
            h.detail.setText(s.detail);
            h.value.setText(s.value);
            h.history.setText(s.changedAgoMs < 0 ? getString(R.string.car_signals_never)
                    : getString(R.string.car_signals_history, s.before, s.changes, s.changedAgoMs / 1000));
            boolean lit = s.changedAgoMs >= 0 && s.changedAgoMs < LIT_MS;
            h.card.setBackgroundResource(lit ? R.drawable.bg_pref_row_primary : R.drawable.bg_pref_row_static);
            h.send.setVisibility(s.sendable ? View.VISIBLE : View.INVISIBLE);
            h.send.setOnClickListener(v -> askSend(s));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView name;
        final TextView detail;
        final TextView value;
        final TextView history;
        final MaterialButton send;

        Holder(LinearLayout card) {
            super(card);
            this.card = card;
            LinearLayout texts = (LinearLayout) card.getChildAt(0);
            name = (TextView) texts.getChildAt(0);
            detail = (TextView) texts.getChildAt(1);
            history = (TextView) texts.getChildAt(2);
            value = (TextView) card.getChildAt(1);
            send = (MaterialButton) card.getChildAt(2);
        }
    }

    private LinearLayout newRow() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(20 * dp, 10 * dp, 16 * dp, 10 * dp);
        RecyclerView.LayoutParams p = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = 8 * dp;
        card.setLayoutParams(p);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(R.style.TextAppearance_Cam_Body, R.color.text_primary));
        texts.addView(text(R.style.TextAppearance_Cam_Caption, R.color.text_tertiary));
        texts.addView(text(R.style.TextAppearance_Cam_Caption, R.color.text_secondary));
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(R.style.TextAppearance_Cam_Title, R.color.text_primary);
        value.setMaxLines(2);
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.setMarginStart(16 * dp);
        value.setMaxWidth(420 * dp);
        card.addView(value, vp);
        MaterialButton send = new MaterialButton(
                new android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall), null,
                com.google.android.material.R.attr.materialButtonStyle);
        send.setText(R.string.car_signals_send);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 52 * dp);
        sp.setMarginStart(16 * dp);
        card.addView(send, sp);
        return card;
    }

    private TextView text(int style, int color) {
        TextView t = new TextView(this);
        t.setTextAppearance(style);
        t.setTextColor(ContextCompat.getColor(this, color));
        return t;
    }

    private TextView chip(String label, View.OnClickListener tap) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        view.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        view.setBackgroundResource(R.drawable.bg_automation_chip);
        view.setPadding(18 * dp, 0, 18 * dp, 0);
        view.setMinHeight(46 * dp);
        view.setOnClickListener(tap);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 46 * dp));
        return view;
    }
}
