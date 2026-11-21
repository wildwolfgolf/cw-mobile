package org.cwmobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BG = 0xff101820, PANEL = 0xff182630, TEXT = 0xffe8f2f3;
    private static final int MUTED = 0xffa7bbc5, ACCENT = 0xff78e0cf, RED = 0xffffa7a0;
    private static final String[] RATES = {"4800", "9600", "19200", "38400", "57600", "115200"};
    private static final int[] FILTERS = {0, 100, 200, 300, 500};
    private static final int[][] BANDS = {
        {160,1800000,2000000,1836000}, {80,3500000,4000000,3560000},
        {60,5330500,5405000,5354000}, {40,7000000,7300000,7030000},
        {30,10100000,10150000,10116000}, {20,14000000,14350000,14060000},
        {17,18068000,18168000,18086000}, {15,21000000,21450000,21060000},
        {12,24890000,24990000,24906000}, {10,28000000,29700000,28060000}
    };
    private static final String[][] MACROS = {
        {"CQ","CQ CQ DE {CALL} K"}, {"Ответ","{OTHER} DE {CALL} K"},
        {"Рапорт","{OTHER} DE {CALL} UR RST {RST} K"}, {"73","{OTHER} DE {CALL} TNX QSO 73 <SK>"}
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService radio = Executors.newSingleThreadScheduledExecutor();
    private final StringBuilder frames = new StringBuilder();
    private final Button[] bandButtons = new Button[BANDS.length], macroButtons = new Button[4];
    private final long[] lastFrequency = new long[BANDS.length];
    private final String[][] macros = new String[4][2];
    private final List<Button> rigControls = new ArrayList<>();
    private final Button[] vfoButtons = new Button[3];
    private final BroadcastReceiver detach = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) return;
            UsbDevice removed = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (removed != null && removed.getDeviceId() == deviceId) {
                disconnect("USB-устройство CAT отключено. Проверьте RX на панели трансивера.");
            }
        }
    };

    private SharedPreferences prefs;
    private UsbManager usb;
    private AudioManager audio;
    private EditText frequencyEntry, pitch, rxWpm, sensitivity, txWpm, gain, lead;
    private View gainBox;
    private EditText call, other, rst, message;
    private Spinner alphabet, filter, modes;
    private CheckBox adaptive, arm;
    private TextView frequency, rigStatus, rigReading, txStatus, txMeter, rxStatus, rxText, profileTitle;
    private ProgressBar strength;
    private ScrollView rxScroll;
    private Button connect, startRx, stopRx, demo, send, stopTx, equipment, emergencyStop;
    private AtomicBoolean rxStop, txCancel;
    private volatile int bandwidth, deviceId = -1, generation;
    private volatile boolean ready, txActive, destroyed;
    private boolean connecting, foreground;
    private UsbDevice pendingPermission;
    private int pendingPort, pendingToken;
    private String portKey, rxKey, txKey;
    private volatile RigProfile profile = RigProfile.G90;
    private int address, baud, stopBits, portDevice = -1, rxDevice = -1, txDevice = -1;
    private boolean hardwareFlow;
    private long hz;
    private int meterStep, failures;
    private String modeText = "—", pttText = "—", splitText = "—", sText = "—";
    private String lastSwr = "—";
    // Only the radio executor accesses the open CAT session.
    private UsbSerialPort serial;
    private Rig rig;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("radio", MODE_PRIVATE);
        usb = getSystemService(UsbManager.class);
        audio = getSystemService(AudioManager.class);
        loadProfile(RigProfile.valueOf(prefs.getString("profile", RigProfile.G90.name())));
        for (int i = 0; i < macros.length; i++) {
            for (int j = 0; j < 2; j++) macros[i][j] = prefs.getString("macro" + i + j, MACROS[i][j]);
        }
        build();
        UsbPermissionReceiver.listener = this::onUsbPermission;
        IntentFilter events = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detach, events, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(detach, events);
        radio.scheduleWithFixedDelay(this::poll, 300, 280, TimeUnit.MILLISECONDS);
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override protected void onStop() {
        foreground = false;
        arm.setChecked(false);
        stopTransmit();
        stopReceive();
        save();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        UsbPermissionReceiver.listener = null;
        unregisterReceiver(detach);
        radio.execute(this::closePort);
        radio.shutdown();
        super.onDestroy();
    }

    private void build() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(3), dp(12), dp(3));
        TextView title = label("CW Mobile", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        weight(bar, title);
        equipment = button("Оборудование", this::equipment);
        bar.addView(equipment);
        emergencyStop = button("СТОП TX", this::stopTransmit);
        emergencyStop.setTextColor(RED);
        bar.addView(emergencyStop);
        root.addView(bar);

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        LinearLayout content = column();
        content.setFocusableInTouchMode(true);
        content.setPadding(dp(12), 0, dp(12), dp(12));
        page.addView(content);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout rigPanel = section(content, "Трансивер");
        profileTitle = label(profile.label, 13, MUTED);
        rigPanel.addView(profileTitle);
        frequency = label("— Гц", 32, ACCENT);
        frequency.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rigPanel.addView(frequency);
        rigReading = label("Режим —   •   S —   •   SPLIT —", 14, TEXT);
        rigStatus = label("CAT отключён", 13, MUTED);
        rigPanel.addView(rigReading);
        rigPanel.addView(rigStatus);
        LinearLayout links = row();
        connect = button("Подключить CAT", () -> {
            if (ready || connecting) disconnect("CAT отключён");
            else connectCat();
        });
        weight(links, connect);
        weight(links, button("Телеметрия", this::details));
        rigPanel.addView(links);
        LinearLayout freq = row();
        frequencyEntry = edit("Частота, Гц", "", true, 10);
        weight(freq, frequencyEntry);
        Button setHz = button("Установить", () -> attempt(() -> {
            try { tune(Long.parseLong(value(frequencyEntry).trim())); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Введите частоту в целых герцах."); }
        }));
        rigControls.add(setHz);
        freq.addView(setHz);
        rigPanel.addView(freq);
        for (int start = 0; start < BANDS.length; start += 5) {
            LinearLayout bandRow = row();
            for (int i = start; i < start + 5; i++) {
                final int band = i;
                bandButtons[i] = button(BANDS[i][0] + " м", () -> tune(lastFrequency[band]));
                rigControls.add(bandButtons[i]);
                weight(bandRow, bandButtons[i]);
            }
            rigPanel.addView(bandRow);
        }
        LinearLayout modeRow = row();
        modes = spinner(profile.modes, 0, "Режим трансивера");
        weight(modeRow, modes);
        Button setMode = button("Режим", () -> {
            String selected = (String)modes.getSelectedItem();
            command(link -> {
                link.mode(selected);
                if (!selected.equals(link.mode())) throw new IOException("Трансивер не подтвердил режим.");
            });
        });
        modeRow.addView(setMode);
        rigControls.add(setMode);
        rigPanel.addView(modeRow);
        LinearLayout vfo = row();
        String[] vfoLabels = {"VFO A", "VFO B", "A↔B"};
        for (Rig.Vfo action : Rig.Vfo.values()) {
            Button b = button(vfoLabels[action.ordinal()], () -> command(link -> link.vfo(action)));
            vfoButtons[action.ordinal()] = b;
            weight(vfo, b);
        }
        Button splitButton = button("SPLIT", () -> command(link -> link.split(!link.split())));
        rigControls.add(splitButton);
        weight(vfo, splitButton);
        rigPanel.addView(vfo);

        LinearLayout rx = section(content, "Приём CW");
        rxStatus = label("Приём остановлен", 14, MUTED);
        rx.addView(rxStatus);
        strength = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        strength.setMax(100);
        strength.setProgressTintList(ColorStateList.valueOf(ACCENT));
        rx.addView(strength, new LinearLayout.LayoutParams(-1, dp(12)));
        rxScroll = new ScrollView(this);
        rxText = label("", 16, TEXT);
        rxText.setTypeface(Typeface.MONOSPACE);
        rxText.setTextIsSelectable(true);
        rxText.setPadding(dp(10), dp(6), dp(10), dp(6));
        rxScroll.setBackground(shape(BG, 8));
        rxScroll.addView(rxText);
        rx.addView(rxScroll, new LinearLayout.LayoutParams(-1, dp(115)));
        LinearLayout rxButtons = row();
        startRx = button("Запустить", this::startReceive);
        stopRx = button("Стоп RX", this::stopReceive);
        demo = button("Демо", this::runDemo);
        weight(rxButtons, startRx);
        weight(rxButtons, stopRx);
        weight(rxButtons, demo);
        weight(rxButtons, button("Очистить", () -> rxText.setText("")));
        rx.addView(rxButtons);
        LinearLayout rxParams = row();
        pitch = param(rxParams, "Тон, Гц", "pitch", "700", false);
        rxWpm = param(rxParams, "Скорость RX", "rxWpm", "18", false);
        sensitivity = param(rxParams, "Порог, дБFS", "sensitivity", "-34", true);
        rx.addView(rxParams);
        LinearLayout rxChoices = row();
        alphabet = spinner(new String[]{"LAT", "RUS"}, prefs.getInt("alphabet", 0), "Алфавит");
        filter = spinner(new String[]{"Фильтр выкл.", "100 Гц", "200 Гц", "300 Гц", "500 Гц"},
                prefs.getInt("filter", 2), "Фильтр CW");
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                bandwidth = FILTERS[position];
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { bandwidth = 0; }
        });
        bandwidth = FILTERS[filter.getSelectedItemPosition()];
        weight(rxChoices, alphabet);
        weight(rxChoices, filter);
        rx.addView(rxChoices);
        adaptive = check("Подстройка скорости");
        adaptive.setChecked(prefs.getBoolean("adaptive", true));
        rx.addView(adaptive);

        LinearLayout tx = section(content, "Передача CW");
        txStatus = label(txHint(), 14, MUTED);
        txMeter = label("Мощность —   ·   КСВ —   ·   ALC —", 13, TEXT);
        tx.addView(txStatus);
        tx.addView(txMeter);
        message = edit("Текст для передачи", prefs.getString("message", ""), false, 2000);
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setSingleLine(false);
        message.setMinLines(2);
        tx.addView(message);
        LinearLayout txParams = row();
        txWpm = param(txParams, "Скорость TX", "txWpm", "18", false);
        gain = param(txParams, "Выход, %", "gain", "12", false);
        gainBox = (View)gain.getParent();
        lead = param(txParams, "Пауза, мс", "lead", "250", false);
        tx.addView(txParams);
        arm = check("Разрешить TX");
        arm.setOnCheckedChangeListener((box, enabled) -> {
            if (!enabled) stopTransmit();
            controls();
        });
        tx.addView(arm);
        LinearLayout txButtons = row();
        send = button("Передать", () -> send(message.getText().toString()));
        stopTx = button("СТОП TX", this::stopTransmit);
        stopTx.setTextColor(RED);
        weight(txButtons, send);
        weight(txButtons, stopTx);
        tx.addView(txButtons);

        LinearLayout macro = section(content, "Макросы");
        LinearLayout identity = row();
        call = field(identity, "Мой позывной", "call", "", 32);
        other = field(identity, "Корреспондент", "other", "", 32);
        rst = field(identity, "RST", "rst", "599", 8);
        macro.addView(identity);
        LinearLayout keys = row();
        for (int i = 0; i < macros.length; i++) {
            final int index = i;
            macroButtons[i] = button(macros[i][0], () -> useMacro(index));
            weight(keys, macroButtons[i]);
        }
        macro.addView(keys);
        macro.addView(button("Изменить макросы", this::editMacros));
        setContentView(root);
        controls();
    }

    private void controls() {
        if (send == null) return;
        connect.setText(ready ? "Отключить CAT" : connecting ? "Отменить CAT" : "Подключить CAT");
        connect.setEnabled(!txActive && profile != RigProfile.AUDIO);
        equipment.setEnabled(!txActive && !connecting);
        equipment.setVisibility(txActive ? View.GONE : View.VISIBLE);
        emergencyStop.setVisibility(txActive ? View.VISIBLE : View.GONE);
        for (Button control : rigControls) control.setEnabled(ready && !txActive);
        for (Rig.Vfo action : Rig.Vfo.values()) {
            vfoButtons[action.ordinal()].setEnabled(ready && !txActive && profile.supportsVfo(action));
        }
        gainBox.setVisibility(profile == RigProfile.WOLF2 ? View.GONE : View.VISIBLE);
        arm.setEnabled(ready || txActive);
        send.setEnabled(ready && arm.isChecked() && !txActive);
        stopTx.setEnabled(txActive);
        startRx.setEnabled(rxStop == null);
        demo.setEnabled(rxStop == null);
        stopRx.setEnabled(rxStop != null);
        for (Button key : macroButtons) key.setEnabled(!txActive);
    }

    private void tune(long requested) {
        profile.validateFrequency(requested);
        command(civ -> {
            civ.frequency(requested);
            if (civ.frequency() != requested) throw new IOException("Трансивер не подтвердил частоту.");
        });
    }

    private interface Job { void work(Rig rig) throws IOException; }
    private void command(Job task) {
        if (!ready || txActive) return;
        int token = generation;
        radio.execute(() -> {
            if (rig == null || token != generation || txActive) return;
            try {
                if (rig.transmitting()) throw new IOException("Переведите трансивер в RX перед сменой настроек.");
                task.work(rig);
            } catch (IOException | RuntimeException e) {
                post(() -> { if (token == generation) toast(reason(e)); });
            }
            meterStep = 0;
            poll();
        });
    }

    private static final class CatPort {
        final UsbSerialDriver driver;
        final int index;
        final String key;
        CatPort(UsbSerialDriver driver, int index) {
            this.driver = driver;
            this.index = index;
            UsbDevice device = driver.getDevice();
            key = String.format(Locale.ROOT, "%04X:%04X · порт %d", device.getVendorId(),
                    device.getProductId(), index + 1);
        }
        @Override public String toString() {
            String product = driver.getDevice().getProductName();
            return (product == null ? "USB Serial" : product) + " · " + key;
        }
    }

    private List<CatPort> ports() {
        List<CatPort> result = new ArrayList<>();
        if (usb == null) return result;
        for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(usb)) {
            for (int i = 0; i < driver.getPorts().size(); i++) result.add(new CatPort(driver, i));
        }
        result.sort(Comparator.comparing(CatPort::toString).thenComparingInt(port -> port.driver.getDevice().getDeviceId()));
        return result;
    }

    private void connectCat() {
        List<CatPort> available = ports();
        if (profile == RigProfile.AUDIO) return;
        if (available.isEmpty()) {
            toast("USB-порт CAT не найден. Проверьте OTG и кабель данных.");
            return;
        }
        List<CatPort> matching = new ArrayList<>();
        for (CatPort port : available) {
            if (portKey.isEmpty() || portKey.equals(port.key)) matching.add(port);
        }
        if (matching.size() > 1 && portDevice >= 0) {
            matching.removeIf(port -> port.driver.getDevice().getDeviceId() != portDevice);
        }
        if (matching.size() != 1) {
            equipment();
            return;
        }
        CatPort chosen = matching.get(0);
        UsbDevice device = chosen.driver.getDevice();
        connecting = true;
        deviceId = device.getDeviceId();
        rigStatus.setText("Подключение CAT…");
        controls();
        if (usb.hasPermission(device)) {
            openCat(device, chosen.index);
        } else {
            pendingPermission = device;
            pendingPort = chosen.index;
            pendingToken = ++generation;
            Intent callback = new Intent(this, UsbPermissionReceiver.class).setAction(UsbPermissionReceiver.ACTION)
                    .putExtra(UsbPermissionReceiver.TOKEN, pendingToken);
            int flags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            try {
                usb.requestPermission(device, PendingIntent.getBroadcast(this, pendingToken, callback, flags));
            } catch (RuntimeException error) {
                disconnect(reason(error));
            }
        }
    }

    private void onUsbPermission(UsbDevice device, boolean granted, int token) {
        if (token != pendingToken || pendingPermission == null || device == null || !pendingPermission.equals(device)) return;
        pendingPermission = null;
        if (granted && usb.hasPermission(device)) openCat(device, pendingPort);
        else disconnect("Доступ Android к USB-порту не предоставлен.");
    }

    private void openCat(UsbDevice device, int index) {
        int token = ++generation;
        RigProfile selected = profile;
        int civAddress = address, rate = baud, stops = stopBits;
        boolean flow = hardwareFlow;
        radio.execute(() -> {
            if (token != generation) return;
            closePort();
            try {
                UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
                if (driver == null) throw new IOException("USB-порт больше недоступен.");
                UsbSerialPort port = driver.getPorts().get(index);
                UsbDeviceConnection connection = usb.openDevice(device);
                if (connection == null) throw new IOException("Android не открыл USB-порт.");
                try {
                    port.open(connection);
                } catch (IOException | RuntimeException e) {
                    connection.close();
                    throw e;
                }
                serial = port;
                port.setParameters(rate, 8, stops, UsbSerialPort.PARITY_NONE);
                boolean rts = port.getSupportedControlLines().contains(UsbSerialPort.ControlLine.RTS);
                boolean dtr = port.getSupportedControlLines().contains(UsbSerialPort.ControlLine.DTR);
                if (dtr) port.setDTR(false);
                if (flow) {
                    if (!port.getSupportedFlowControl().contains(UsbSerialPort.FlowControl.RTS_CTS)) {
                        throw new IOException("Этот USB-порт не поддерживает RTS/CTS.");
                    }
                    port.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS);
                } else if (rts) port.setRTS(false);
                rig = selected.create(civAddress, new Rig.Wire() {
                    @Override public void write(byte[] bytes) throws IOException { port.write(bytes, 140); }
                    @Override public int read(byte[] data) throws IOException { return port.read(data, 25); }
                    @Override public void key(boolean down) throws IOException { port.setDTR(down); }
                    @Override public void pttLine(boolean down) throws IOException { port.setRTS(down); }
                    @Override public boolean canKey() { return rts && dtr && !flow; }
                }, MainActivity.this::addFrame);
                rig.identify();
                long actual = rig.frequency();
                failures = meterStep = 0;
                post(() -> {
                    if (token != generation) return;
                    ready = true;
                    connecting = false;
                    rigStatus.setText("CAT подключён · " + rate + " 8N" + stops);
                    showFrequency(actual);
                    txStatus.setText("Передача отключена");
                    controls();
                });
            } catch (IOException | RuntimeException e) {
                closePort();
                post(() -> { if (token == generation) disconnect(reason(e)); });
            }
        });
    }

    private void disconnect(String status) {
        ++generation;
        arm.setChecked(false);
        stopTransmit();
        ready = connecting = false;
        pendingPermission = null;
        deviceId = -1;
        rigStatus.setText(status);
        showFrequency(0);
        modeText = pttText = splitText = sText = "—";
        rigReading.setText("Режим —   •   S —   •   SPLIT —");
        radio.execute(this::closePort);
        controls();
    }

    private void closePort() {
        rig = null;
        if (serial != null) {
            try { serial.close(); } catch (IOException ignored) { }
            serial = null;
        }
    }

    private void poll() {
        if (rig == null || !ready || txActive || destroyed) return;
        int token = generation;
        try {
            long actual = rig.frequency();
            failures = 0;
            post(() -> { if (token == generation) showFrequency(actual); });
        } catch (IOException | RuntimeException error) {
            if (++failures >= 3) {
                closePort();
                post(() -> { if (token == generation) disconnect("Связь CAT потеряна. Проверьте USB-подключение."); });
            }
            return;
        }
        int reading = meterStep++ % 4;
        String value = "—";
        try {
            switch (reading) {
                case 0: value = rig.mode(); break;
                case 1: value = rig.transmitting() ? "TX" : "RX"; break;
                case 2: value = rig.split() ? "вкл." : "выкл."; break;
                default: value = rig.meter(Rig.Meter.S, 140) + (profile == RigProfile.WOLF2 ? " дБм" : "");
            }
        } catch (IOException | RuntimeException ignored) { }
        String display = value;
        post(() -> {
            if (token != generation) return;
            switch (reading) {
                case 0: modeText = display; break;
                case 1: pttText = display; break;
                case 2: splitText = display; break;
                default: sText = display;
            }
            updateReadings();
        });
    }

    private void updateReadings() {
        rigReading.setText(modeText + "   •   " + pttText + "   •   S " + sText + "   •   SPLIT " + splitText);
    }

    private void showFrequency(long actual) {
        if (actual == hz) return;
        hz = actual;
        frequency.setText(hz == 0 ? "— Гц" : String.format(Locale.US, "%,d Гц", hz).replace(',', ' '));
        for (int i = 0; i < BANDS.length; i++) {
            boolean selected = hz >= BANDS[i][1] && hz <= BANDS[i][2];
            if (selected) lastFrequency[i] = hz;
            bandButtons[i].setTextColor(selected ? BG : TEXT);
            bandButtons[i].setBackgroundTintList(ColorStateList.valueOf(selected ? ACCENT : 0xff293b46));
        }
    }

    private void addFrame(String direction, byte[] packet) {
        synchronized (frames) {
            String payload = (packet[0] & 255) == 0xFE ? Civ.hex(packet) :
                    new String(packet, java.nio.charset.StandardCharsets.US_ASCII);
            frames.append(direction).append(' ').append(payload).append('\n');
            if (frames.length() > 14000) {
                int end = frames.indexOf("\n", frames.length() - 12000);
                if (end >= 0) frames.delete(0, end + 1);
            }
        }
    }

    private static String audioKey(AudioDeviceInfo device) {
        String address = Build.VERSION.SDK_INT >= 28 ? device.getAddress() : "";
        return device.getType() + "|" + device.getProductName() + "|" + address;
    }

    private List<AudioDeviceInfo> audioDevices(boolean input) {
        List<AudioDeviceInfo> result = new ArrayList<>();
        for (AudioDeviceInfo device : audio.getDevices(input ? AudioManager.GET_DEVICES_INPUTS :
                AudioManager.GET_DEVICES_OUTPUTS)) {
            if (RadioAudio.usb(device) || (input && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC)) {
                result.add(device);
            }
        }
        return result;
    }

    private AudioDeviceInfo getAudioDevice(boolean input) {
        List<AudioDeviceInfo> devices = audioDevices(input);
        String selected = input ? rxKey : txKey;
        AudioDeviceInfo match = null;
        int matches = 0, selectedId = input ? rxDevice : txDevice;
        for (AudioDeviceInfo device : devices) {
            if (selected.equals(audioKey(device))) {
                if (device.getId() == selectedId) return device;
                match = device;
                matches++;
            }
        }
        if (matches > 0) return matches == 1 ? match : null;
        if (selected.isEmpty()) {
            AudioDeviceInfo candidate = null;
            for (AudioDeviceInfo device : devices) {
                if (!RadioAudio.usb(device)) continue;
                if (candidate != null) return null;
                candidate = device;
            }
            if (candidate != null) return candidate;
            if (input && profile == RigProfile.AUDIO) {
                for (AudioDeviceInfo device : devices) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return device;
                }
            }
        }
        return null;
    }

    private Spinner audioPicker(LinearLayout panel, String label, List<AudioDeviceInfo> devices, String key) {
        String[] options = new String[devices.size() + 1];
        options[0] = "Автоматически · один USB";
        int selected = 0;
        for (int i = 0; i < devices.size(); i++) {
            AudioDeviceInfo device = devices.get(i);
            options[i + 1] = (RadioAudio.usb(device) ? "USB · " : "Микрофон · ") + device.getProductName()
                    + " [" + device.getId() + "]";
            if (key.equals(audioKey(device))) selected = i + 1;
        }
        panel.addView(label(label, 14, MUTED));
        Spinner picker = spinner(options, selected, label);
        panel.addView(picker);
        return picker;
    }

    private String profileKey(RigProfile selected, String key) {
        return selected.name() + "." + key;
    }

    private void loadProfile(RigProfile selected) {
        profile = selected;
        portDevice = rxDevice = txDevice = -1;
        portKey = prefs.getString(profileKey(selected, "cat"), "");
        rxKey = prefs.getString(profileKey(selected, "rx"), "");
        txKey = prefs.getString(profileKey(selected, "tx"), "");
        address = prefs.getInt(profileKey(selected, "address"), selected.address);
        baud = prefs.getInt(profileKey(selected, "baud"), selected.baud);
        stopBits = prefs.getInt(profileKey(selected, "stopBits"), selected.stopBits);
        hardwareFlow = prefs.getBoolean(profileKey(selected, "flow"), false) &&
                selected != RigProfile.WOLF2 && selected != RigProfile.G90 && selected != RigProfile.IC7300;
        for (int i = 0; i < BANDS.length; i++) {
            lastFrequency[i] = prefs.getLong(profileKey(selected, "band" + i), BANDS[i][3]);
        }
    }

    private void equipment() {
        if (txActive || connecting) return;
        List<CatPort> catPorts = ports();
        List<AudioDeviceInfo> inputs = audioDevices(true), outputs = audioDevices(false);
        LinearLayout box = dialogBody();
        box.addView(label("Трансивер", 14, MUTED));
        RigProfile[] profiles = RigProfile.values();
        String[] names = new String[profiles.length];
        for (int i = 0; i < profiles.length; i++) names[i] = profiles[i].label;
        Spinner radioModel = spinner(names, profile.ordinal(), "Трансивер");
        box.addView(radioModel);

        LinearLayout catSettings = column();
        catSettings.addView(label("USB-порт CAT", 14, MUTED));
        String[] ports = new String[catPorts.size() + 1];
        ports[0] = "Автоматически · один порт";
        for (int i = 0; i < catPorts.size(); i++) {
            CatPort item = catPorts.get(i);
            ports[i + 1] = item + " [" + item.driver.getDevice().getDeviceId() + "]";
        }
        Spinner cat = spinner(ports, 0, "Порт CAT");
        catSettings.addView(cat);
        LinearLayout serialOptions = row();
        LinearLayout rateGroup = column(), stopGroup = column();
        rateGroup.addView(label("Скорость, бод", 13, MUTED));
        Spinner rate = spinner(RATES, 2, "Скорость CAT");
        rateGroup.addView(rate);
        stopGroup.addView(label("Формат", 13, MUTED));
        Spinner stops = spinner(new String[]{"8N1", "8N2"}, 0, "Формат CAT");
        stopGroup.addView(stops);
        weight(serialOptions, rateGroup);
        weight(serialOptions, stopGroup);
        catSettings.addView(serialOptions);
        CheckBox flow = check("RTS/CTS для CAT");
        catSettings.addView(flow);
        LinearLayout civOptions = column();
        civOptions.addView(label("Адрес CI-V (hex)", 14, MUTED));
        EditText addr = edit("70", "", false, 2);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        civOptions.addView(addr);
        catSettings.addView(civOptions);
        box.addView(catSettings);

        Spinner rx = audioPicker(box, "Аудиовход RX", inputs, rxKey);
        LinearLayout outputBox = column();
        Spinner tx = audioPicker(outputBox, "Аудиовыход TX", outputs, txKey);
        box.addView(outputBox);
        TextView hint = label("", 14, MUTED);
        box.addView(hint);

        java.util.function.Consumer<RigProfile> display = model -> {
            boolean active = model == profile;
            int index = 0, matches = 0;
            String key = active ? portKey : prefs.getString(profileKey(model, "cat"), "");
            for (int i = 0; i < catPorts.size(); i++) {
                CatPort item = catPorts.get(i);
                if (!key.equals(item.key)) continue;
                matches++;
                if (index == 0 || (active && item.driver.getDevice().getDeviceId() == portDevice)) index = i + 1;
            }
            if (matches > 1 && (!active || portDevice < 0)) index = 0;
            cat.setSelection(index);
            int rateValue = active ? baud : prefs.getInt(profileKey(model, "baud"), model.baud);
            int rateIndex = java.util.Arrays.asList(RATES).indexOf(String.valueOf(rateValue));
            rate.setSelection(Math.max(0, rateIndex));
            stops.setSelection((active ? stopBits : prefs.getInt(profileKey(model, "stopBits"), model.stopBits)) - 1);
            int addrValue = active ? address : prefs.getInt(profileKey(model, "address"), model.address);
            addr.setText(String.format(Locale.ROOT, "%02X", addrValue));
            flow.setChecked(active ? hardwareFlow : prefs.getBoolean(profileKey(model, "flow"), false));
            flow.setVisibility(model == RigProfile.TS590SG || model == RigProfile.FT991A ? View.VISIBLE : View.GONE);
            boolean civ = model.address != 0;
            civOptions.setVisibility(civ ? View.VISIBLE : View.GONE);
            catSettings.setVisibility(model == RigProfile.AUDIO ? View.GONE : View.VISIBLE);
            outputBox.setVisibility(model == RigProfile.AUDIO || model == RigProfile.WOLF2 ? View.GONE : View.VISIBLE);
            selectAudio(rx, inputs, active ? rxKey : prefs.getString(profileKey(model, "rx"), ""), active ? rxDevice : -1);
            selectAudio(tx, outputs, active ? txKey : prefs.getString(profileKey(model, "tx"), ""), active ? txDevice : -1);
            hint.setText(model == RigProfile.WOLF2 ? "CAT Type: TS-2000. Для TX нужны DTR = CW и RTS = PTT."
                    : model == RigProfile.AUDIO ? "Автовыбор: один USB-аудиовход или встроенный микрофон."
                    : "Скорость и формат должны совпадать с настройками CAT трансивера.");
        };
        radioModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                display.accept(profiles[position]);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Оборудование")
                .setView(wrap(box)).setNegativeButton("Отмена", null)
                .setNeutralButton("Обновить", (d, which) -> equipment())
                .setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            RigProfile selected = profiles[radioModel.getSelectedItemPosition()];
            int updated = selected.address;
            if (updated != 0) {
                String text = addr.getText().toString().trim();
                if (!text.matches("(?i)[0-9a-f]{2}")) {
                    addr.setError("Введите две шестнадцатеричные цифры.");
                    return;
                }
                updated = Integer.parseInt(text, 16);
                if (updated < 1 || updated >= 0xE0) {
                    addr.setError("Допустимый адрес 01–DF.");
                    return;
                }
            }
            int i = cat.getSelectedItemPosition(), j = rx.getSelectedItemPosition(), k = tx.getSelectedItemPosition();
            CatPort selectedPort = i <= 0 || selected == RigProfile.AUDIO ? null : catPorts.get(i - 1);
            String catKey = selectedPort == null ? "" : selectedPort.key;
            int device = selectedPort == null ? -1 : selectedPort.driver.getDevice().getDeviceId();
            String inputKey = j <= 0 ? "" : audioKey(inputs.get(j - 1));
            String outputKey = k <= 0 ? "" : audioKey(outputs.get(k - 1));
            int speed = selected == RigProfile.AUDIO ? 0 : Integer.parseInt((String)rate.getSelectedItem());
            int stop = stops.getSelectedItemPosition() + 1;
            boolean useFlow = flow.getVisibility() == View.VISIBLE && flow.isChecked();
            boolean profileChanged = profile != selected;
            boolean catChanged = !portKey.equals(catKey) || (device >= 0 && device != deviceId) ||
                    address != updated || baud != speed || stopBits != stop || hardwareFlow != useFlow;
            int inputId = j <= 0 ? -1 : inputs.get(j - 1).getId();
            int outputId = k <= 0 ? -1 : outputs.get(k - 1).getId();
            if (profileChanged || !rxKey.equals(inputKey) || rxDevice != inputId) stopReceive();
            if (profileChanged || (ready && catChanged)) disconnect("Выбран профиль · " + selected.label);
            if (profileChanged) {
                save();
                loadProfile(selected);
                profileTitle.setText(profile.label);
                setSpinnerOptions(modes, profile.modes);
                synchronized (frames) { frames.setLength(0); }
                lastSwr = "—";
                updateTxMeter(false, null, null, null);
            }
            if (profileChanged || !txKey.equals(outputKey) || txDevice != outputId) arm.setChecked(false);
            address = updated;
            baud = speed;
            stopBits = stop;
            hardwareFlow = useFlow;
            portKey = catKey;
            portDevice = device;
            rxKey = inputKey;
            txKey = outputKey;
            rxDevice = inputId;
            txDevice = outputId;
            txStatus.setText(txHint());
            save();
            controls();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private String txHint() {
        switch (profile) {
            case AUDIO: return "Профиль только для приёма";
            case WOLF2: return "TX через DTR/RTS · режим CW/CWR";
            default: return "TX через CAT PTT и USB-аудио · " + profile.txMode;
        }
    }

    private void selectAudio(Spinner spinner, List<AudioDeviceInfo> devices, String key, int deviceId) {
        int selection = 0, matches = 0;
        for (int i = 0; i < devices.size(); i++) {
            AudioDeviceInfo device = devices.get(i);
            if (!key.equals(audioKey(device))) continue;
            if (device.getId() == deviceId) {
                spinner.setSelection(i + 1);
                return;
            }
            matches++;
            selection = i + 1;
        }
        spinner.setSelection(matches == 1 ? selection : 0);
    }

    private void startReceive() {
        if (rxStop != null) return;
        AudioDeviceInfo input = getAudioDevice(true);
        if (input == null) {
            toast("Выберите аудиовход в разделе «Оборудование».");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            return;
        }
        attempt(() -> {
            int tone = number(pitch, "Тон", 300, 1200);
            int speed = number(rxWpm, "Скорость RX", 6, 40);
            int threshold = number(sensitivity, "Порог", -80, -8);
            boolean russian = alphabet.getSelectedItemPosition() == 1;
            boolean adjust = adaptive.isChecked();
            AtomicBoolean stop = new AtomicBoolean();
            rxStop = stop;
            rxStatus.setText("Открытие аудиовхода…");
            controls();
            new Thread(() -> {
                String status = "Приём остановлен";
                try {
                    RadioAudio.receive(input, stop, tone, speed, russian, adjust, threshold,
                            new RadioAudio.ReceiveListener() {
                        @Override public int bandwidth() { return bandwidth; }
                        @Override public boolean muted() { return txActive; }
                        @Override public void update(String text, double db, double wpm, boolean keyed) {
                            post(() -> {
                                if (rxStop != stop || stop.get()) return;
                                if (txActive) return;
                                if (!text.isEmpty()) appendRx(text);
                                rxStatus.setText(String.format(Locale.ROOT, "%s   %.0f дБFS   ·   %.1f WPM",
                                        keyed ? "ТОН" : "RX", db, wpm));
                                strength.setProgress((int)Math.max(0, Math.min(100, (db + 80) * 1.25)));
                            });
                        }
                    });
                } catch (IOException | RuntimeException error) {
                    if (!stop.get()) status = reason(error);
                }
                finishRx(stop, status);
            }, "CW receive").start();
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 10) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (foreground) startReceive();
        } else toast("Для приёма нужен доступ к аудиовходу.");
    }

    private void runDemo() {
        if (rxStop != null) return;
        attempt(() -> {
            int tone = number(pitch, "Тон", 300, 1200);
            int speed = number(rxWpm, "Скорость RX", 6, 40);
            int threshold = number(sensitivity, "Порог", -80, -8);
            int pass = FILTERS[filter.getSelectedItemPosition()];
            boolean russian = alphabet.getSelectedItemPosition() == 1;
            boolean adjust = adaptive.isChecked();
            AtomicBoolean stop = new AtomicBoolean();
            rxStop = stop;
            rxStatus.setText("Декодирование тестового сигнала…");
            controls();
            new Thread(() -> {
                String phrase = russian ? "ВНИМАНИЕ ТЕСТ 73" : "CQ CQ DE TEST 5NN";
                short[] samples = Morse.waveform(phrase, Morse.RX_RATE, speed, tone, 28);
                Morse.Decoder decoder = new Morse.Decoder(tone, speed, russian, adjust, pass);
                Random noise = new Random(42);
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < samples.length + 4000 && !stop.get(); i++) {
                    double sample = i < samples.length ? samples[i] / 32768.0 : 0;
                    decoder.push(sample + noise.nextGaussian() * 0.025, threshold, result);
                }
                String decoded = result.toString().trim();
                post(() -> {
                    if (rxStop != stop || stop.get()) return;
                    appendRx((rxText.length() == 0 ? "" : "\n") + decoded + "\n");
                });
                finishRx(stop, stop.get() ? "Приём остановлен" :
                        (phrase.equals(decoded) ? "Демо · текст совпал" : "Демо · ожидалось " + phrase));
            }, "CW demo").start();
        });
    }

    private void stopReceive() {
        if (rxStop != null) rxStop.set(true);
    }

    private void finishRx(AtomicBoolean stop, String status) {
        post(() -> {
            if (rxStop != stop) return;
            rxStop = null;
            strength.setProgress(0);
            rxStatus.setText(status);
            controls();
        });
    }

    private void appendRx(String text) {
        String value = rxText.getText().toString() + text;
        rxText.setText(value.substring(Math.max(0, value.length() - 12000)));
        rxScroll.post(() -> rxScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void send(String text) {
        if (!foreground || !ready || !arm.isChecked() || txActive) return;
        RigProfile selected = profile;
        boolean keying = selected == RigProfile.WOLF2;
        AudioDeviceInfo output = keying ? null : getAudioDevice(false);
        if (!keying && output == null) {
            toast("Выберите USB-аудиовыход трансивера.");
            return;
        }
        attempt(() -> {
            int tone = keying ? 0 : number(pitch, "Тон", 300, 1200);
            int speed = number(txWpm, "Скорость TX", 6, 40);
            int level = keying ? 0 : number(gain, "Выход", 1, 100);
            int delay = number(lead, "Пауза", 0, 1500);
            int token = generation;
            AtomicBoolean cancel = new AtomicBoolean();
            txCancel = cancel;
            txActive = true;
            txStatus.setText(keying ? "Проверка CAT и DTR/RTS…" : "Проверка CAT и USB-аудиовыхода…");
            updateTxMeter(false, null, null, null);
            controls();
            radio.execute(() -> {
                String status = "TX завершён · RX подтверждён";
                boolean fault = false;
                try {
                    if (token != generation || rig == null) throw new IOException("CAT отключён.");
                    if (keying) {
                        WolfKeyer.transmit((AsciiRig)rig, text, speed, delay, cancel, () -> post(() -> {
                            if (!cancel.get()) txStatus.setText("TX · ключевание DTR");
                        }));
                    } else {
                        short[] waveform = RadioAudio.transmitWave(text, speed, tone, level);
                        RadioAudio.transmit(rig, output, waveform, delay, cancel, new RadioAudio.TransmitListener() {
                            @Override public void phase(String phase) { post(() -> {
                                if (!cancel.get()) txStatus.setText(phase);
                            }); }
                            @Override public void meters(boolean rf, Integer power, Integer swr, Integer alc) {
                                post(() -> updateTxMeter(rf, power, swr, alc));
                            }
                        });
                    }
                    if (cancel.get()) status = "Передача остановлена";
                } catch (IOException | RuntimeException error) {
                    status = reason(error);
                    fault = true;
                }
                String outcome = status;
                boolean disarm = fault;
                post(() -> {
                    txActive = false;
                    txCancel = null;
                    if (disarm) arm.setChecked(false);
                    txStatus.setText(outcome);
                    controls();
                });
            });
        });
    }

    private void stopTransmit() {
        if (txCancel == null || txCancel.getAndSet(true)) return;
        txStatus.setText("Остановка передачи…");
    }

    private void updateTxMeter(boolean rf, Integer power, Integer swr, Integer alc) {
        if (rf && swr != null) {
            lastSwr = profile.address != 0 ? swrLabel(swr) : swr + " CAT";
        }
        txMeter.setText("Мощность " + (power == null ? "—" : power) + "   ·   КСВ "
                + (rf && swr != null ? lastSwr : lastSwr.equals("—") ? "—" : lastSwr + " посл.")
                + "   ·   ALC " + (alc == null ? "—" : alc));
    }

    private static String swrLabel(int raw) {
        if (raw > 120) return "> 3:1";
        double swr = raw <= 48 ? 1 + raw / 96.0 : raw <= 80 ? 1.5 + (raw - 48) / 64.0 :
                2 + (raw - 80) / 40.0;
        return String.format(Locale.ROOT, "%.2f:1", swr);
    }

    private void useMacro(int index) {
        attempt(() -> {
            String text = Morse.expand(macros[index][1], value(call), value(other), value(rst));
            message.setText(text);
            if (arm.isChecked()) send(text);
        });
    }

    private void editMacros() {
        LinearLayout box = dialogBody();
        EditText[][] fields = new EditText[4][2];
        box.addView(label("{CALL} · мой позывной\n{OTHER} · корреспондент\n{RST} · рапорт", 14, MUTED));
        for (int i = 0; i < fields.length; i++) {
            fields[i][0] = edit("Название", macros[i][0], false, 16);
            fields[i][1] = edit("Текст", macros[i][1], false, 350);
            box.addView(fields[i][0]);
            box.addView(fields[i][1]);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Редактор макросов")
                .setView(wrap(box)).setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                for (EditText[] pair : fields) {
                    if (value(pair[0]).trim().isEmpty()) throw new IllegalArgumentException("Введите название макроса.");
                    Morse.waveform(Morse.expand(value(pair[1]), "TEST", "TEST", "599"), 8000, 18, 700, 12);
                }
            } catch (IllegalArgumentException error) {
                toast(reason(error));
                return;
            }
            for (int i = 0; i < fields.length; i++) {
                macros[i][0] = value(fields[i][0]).trim();
                macros[i][1] = value(fields[i][1]).trim();
                macroButtons[i].setText(macros[i][0]);
            }
            save();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void details() {
        LinearLayout box = dialogBody();
        TextView values = label(rigReading.getText() + "\n" + txMeter.getText(), 14, TEXT);
        box.addView(values);
        box.addView(label("Последние кадры CAT", 14, MUTED));
        TextView log = label("", 12, TEXT);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        synchronized (frames) { log.setText(frames.length() > 0 ? frames.toString() : "—"); }
        box.addView(log);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Телеметрия")
                .setView(wrap(box)).setPositiveButton("Закрыть", null).create();
        dialog.show();
        if (!ready || txActive) return;
        int token = generation;
        radio.execute(() -> {
            if (rig == null || token != generation || txActive) return;
            if (!(rig instanceof Civ) || profile != RigProfile.G90) {
                try {
                    String reading = rig.extraTelemetry();
                    if (!reading.isEmpty()) post(() -> {
                        if (dialog.isShowing() && token == generation) values.append("\n" + reading);
                    });
                } catch (IOException | RuntimeException ignored) { }
                return;
            }
            Civ civ = (Civ)rig;
            String[] names = {"Второй VFO", "Режим второго VFO", "ATT", "PRE", "AGC", "NB", "ATU",
                    "RIT", "XIT", "Напряжение CAT", "Мощность (установка CAT)", "Усиление RF",
                    "Порог SQL", "Код модели", "Тон CW", "Скорость ключа", "Границы диапазона",
                    "Шумоподавитель", "Компрессор", "Блокировка", "Сдвиг RIT", "ID CI-V", "DATA",
                    "Громкость AF", "Уровень NR", "Усиление MIC", "Задержка QSK", "Уровень NB",
                    "Уровень MONI", "Усиление VOX", "ANTI-VOX", "Яркость LCD"};
            int[][] queries = {{0x25,1},{0x26,1},{0x11},{0x16,2},{0x16,0x12},{0x16,0x22},{0x1C,1},
                    {0x21,1},{0x21,2},{0x15,0x15},{0x14,0x0A},{0x14,2},{0x14,3},{0x1D,0x19},{0x14,9},{0x14,0x0C},
                    {0x02},{0x15,1},{0x16,0x44},{0x16,0x50},{0x21,0},{0x19,0},{0x1A,6},
                    {0x14,1},{0x14,6},{0x14,0x0B},{0x14,0x0F},{0x14,0x12},{0x14,0x15},
                    {0x14,0x16},{0x14,0x17},{0x14,0x19}};
            StringBuilder details = new StringBuilder();
            for (int i = 0; i < queries.length && token == generation && !txActive && !destroyed; i++) {
                String value = "—";
                try {
                    byte[] result = civ.query(95, queries[i]);
                    if (i == 0) value = Civ.parseFrequency(result) + " Гц";
                    else if (i == 1) value = civ.describeMode(result);
                    else if (i == 16 && result.length == 11) {
                        value = Civ.parseFrequency(java.util.Arrays.copyOfRange(result, 0, 5)) + "–"
                                + Civ.parseFrequency(java.util.Arrays.copyOfRange(result, 6, 11)) + " Гц";
                    } else if ((i >= 9 && i <= 15 && i != 13) || i == 17 || i >= 23) {
                        value = String.valueOf(Civ.level(result));
                    } else value = Civ.hex(result);
                } catch (IOException | RuntimeException ignored) { }
                details.append('\n').append(names[i]).append(" · ").append(value);
            }
            post(() -> {
                if (!dialog.isShowing() || token != generation) return;
                values.append(details);
                synchronized (frames) { log.setText(frames.toString()); }
            });
        });
    }

    private void save() {
        SharedPreferences.Editor editor = prefs.edit().putString("profile", profile.name())
                .putString(profileKey(profile, "cat"), portKey).putString(profileKey(profile, "rx"), rxKey)
                .putString(profileKey(profile, "tx"), txKey).putInt(profileKey(profile, "address"), address)
                .putInt(profileKey(profile, "baud"), baud).putInt(profileKey(profile, "stopBits"), stopBits)
                .putBoolean(profileKey(profile, "flow"), hardwareFlow).putString("pitch", value(pitch))
                .putString("rxWpm", value(rxWpm)).putString("sensitivity", value(sensitivity))
                .putString("txWpm", value(txWpm)).putString("gain", value(gain)).putString("lead", value(lead))
                .putString("call", value(call)).putString("other", value(other)).putString("rst", value(rst))
                .putString("message", value(message)).putInt("alphabet", alphabet.getSelectedItemPosition())
                .putInt("filter", filter.getSelectedItemPosition()).putBoolean("adaptive", adaptive.isChecked());
        for (int i = 0; i < BANDS.length; i++) editor.putLong(profileKey(profile, "band" + i), lastFrequency[i]);
        for (int i = 0; i < macros.length; i++) {
            for (int j = 0; j < 2; j++) editor.putString("macro" + i + j, macros[i][j]);
        }
        editor.apply();
    }

    private void post(Runnable action) {
        ui.post(() -> { if (!destroyed) action.run(); });
    }

    private void attempt(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException e) { toast(reason(e)); }
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String value(EditText control) { return control.getText().toString(); }

    private static int number(EditText control, String label, int min, int max) {
        try {
            int n = Integer.parseInt(value(control).trim());
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(label + " · допустимо " + min + "–" + max);
    }

    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() { return new LinearLayout(this); }

    private void weight(LinearLayout parent, View child) {
        parent.addView(child, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView label(CharSequence text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private CheckBox check(String text) {
        CheckBox checkbox = new CheckBox(this);
        checkbox.setText(text);
        checkbox.setTextColor(TEXT);
        return checkbox;
    }

    private LinearLayout section(LinearLayout parent, String text) {
        LinearLayout box = column();
        box.setBackground(shape(PANEL, 13));
        box.setPadding(dp(12), dp(9), dp(12), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(9);
        parent.addView(box, params);
        TextView heading = label(text, 17, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(heading);
        return box;
    }

    private EditText edit(String hint, String text, boolean number, int limit) {
        EditText field = new EditText(this);
        field.setTextSize(15);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(MUTED);
        field.setSingleLine();
        field.setInputType(number ? InputType.TYPE_CLASS_NUMBER :
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setHint(hint);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        field.setText(text);
        return field;
    }

    private EditText param(LinearLayout parent, String label, String key, String defaultValue, boolean signed) {
        LinearLayout box = column();
        box.addView(label(label, 12, MUTED));
        EditText field = edit(label, prefs.getString(key, defaultValue), true, 5);
        if (signed) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            field.setText(prefs.getString(key, defaultValue));
        }
        box.addView(field);
        weight(parent, box);
        return field;
    }

    private EditText field(LinearLayout parent, String label, String key, String defaultValue, int limit) {
        LinearLayout box = column();
        box.addView(label(label, 12, MUTED));
        EditText view = edit(label, prefs.getString(key, defaultValue), false, limit);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(view);
        weight(parent, box);
        return view;
    }

    private Spinner spinner(String[] options, int selected, String description) {
        Spinner picker = new Spinner(this);
        setSpinnerOptions(picker, options);
        picker.setSelection(Math.max(0, Math.min(selected, options.length - 1)));
        picker.setContentDescription(description);
        picker.setMinimumHeight(dp(44));
        return picker;
    }

    private void setSpinnerOptions(Spinner picker, String[] options) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
    }

    private LinearLayout dialogBody() {
        LinearLayout body = column();
        body.setPadding(dp(20), dp(10), dp(20), dp(8));
        return body;
    }

    private ScrollView wrap(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        return scroll;
    }
}
