package com.ts.launcher;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Guangzhou TV live player doubling as HOME.
 * OK = channel list (广州综合 / 广州新闻). BACK = full-screen app icon grid
 * (this app IS the home, so "exit" means choosing another app).
 * Always starts on 广州综合. Auto-reconnect on error, rotates fallback sources.
 */
public class HomeActivity extends Activity implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnInfoListener,
        SurfaceHolder.Callback {

    private static final String TAG = "GzTv";
    private static final String ACTION_SWITCH = "com.ts.launcher.SWITCH";
    private static final String PREFS = "gztv";
    private static final String PREF_APP_ORDER = "app_order";
    private static final long RETRY_DELAY_MS = 3000;
    private static final int GRID_COLUMNS = 5;

    // Per-channel source list: [0] tried first, then rotates on error.
    private static final String[][] CHANNEL_URLS = {
            { // 广州综合
                    "http://tencentplay.gztv.com/live/zonghes.m3u8?txSecret=c8dbe86d6f86eb1a21999ab479fd5667&txTime=18d3c4690e8",
                    "http://r.jdshipin.com/snhya",
            },
            { // 广州新闻
                    "http://tencentplay.gztv.com/live/xinwen.m3u8?txSecret=d2c2db0b598c02ec1b5623728434d367&txTime=18d57929bd3",
                    "http://php.jdshipin.com:8880/gztv.php?id=xinwen",
            },
    };
    private static final String[] CHANNEL_NAMES = {"广州综合", "广州新闻"};

    private MediaPlayer player;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private TextView status;
    private LinearLayout channelPanel;
    private TextView[] channelItems;
    // Bottom-left "switching soon" countdown (shown 10s before the alarm switch)
    private TextView countdownView;
    private boolean resumed = false;
    private int pendingTarget = -1; // countdown deferred until onResume
    private int countdownLeft;
    private int countdownTarget = -1;
    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (countdownTarget < 0 || countdownView == null) {
                return;
            }
            countdownLeft--;
            if (countdownLeft <= 0) {
                hideCountdown();
                int t = countdownTarget;
                countdownTarget = -1;
                switchToChannel(t);
                return;
            }
            countdownView.setText("即将切换到 "
                    + CHANNEL_NAMES[countdownTarget] + " · " + countdownLeft + " 秒");
            handler.postDelayed(this, 1000);
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int channel = 0; // always default to 广州综合 on open
    private int sourceIndex = 0;
    private boolean surfaceReady = false;
    private boolean prepared = false;
    private boolean activityGone = false;
    private boolean panelVisible = false;

    // Full-screen app icon grid ("exit" destination — we are the home)
    private LinearLayout appPanel;
    private GridView appGridView;
    private TextView appTitle;
    private boolean appPanelVisible = false;
    private boolean grabbed = false; // long-press OK picked up an icon
    private int gridPos = 0; // tracked position in the app grid
    private boolean swallowUp = false; // swallow next OK UP to avoid a click
    private final List<AppEntry> appEntries = new ArrayList<AppEntry>();
    // Receives the daily 18:58 channel switch (testable via ADB broadcast)
    private android.content.BroadcastReceiver switchReceiver;
    // Keeps playback silent while the TV screen is off (standby-safe)
    private boolean screenOn = true;
    private android.content.BroadcastReceiver screenReceiver;

    private static class AppEntry {
        String label;
        String pkg;
        String cls;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        channel = 0; // every open starts on 广州综合
        sourceIndex = 0;
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        screenOn = pm == null || pm.isInteractive();
        int alarmChannel = readChannelExtra(getIntent(), -1);
        if (alarmChannel >= 0 && alarmChannel < CHANNEL_NAMES.length) {
            // Launched by the daily alarm (or a test broadcast)
            channel = alarmChannel;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        surface = new SurfaceView(this);
        setContentView(surface);
        buildStatusView();
        buildChannelPanel();
        buildAppPanel();
        buildCountdownView();
        surface.getHolder().addCallback(this);
        hideSystemUi();
        scheduleChannelAlarm(this, 0);

        switchReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int c = readChannelExtra(intent, 1);
                Log.i(TAG, "switch request ch=" + c);
                switchToChannel(c);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(
                ACTION_SWITCH);
        registerReceiver(switchReceiver, f);

        screenReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    Log.i(TAG, "screen on");
                    screenOn = true;
                    if (player == null && surfaceReady) {
                        startPlayback(); // woke up during standby silence
                    }
                } else {
                    Log.i(TAG, "screen off, stop stream");
                    screenOn = false;
                    releasePlayer(); // stop pulling the stream in standby
                }
            }
        };
        android.content.IntentFilter sf = new android.content.IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, sf);
    }

    /** Read the "channel" extra; accepts int (--ei) and string (-e) forms. */
    private static int readChannelExtra(Intent intent, int def) {
        if (intent == null || !intent.hasExtra("channel")) {
            return def;
        }
        int c = intent.getIntExtra("channel", def);
        if (c == def) {
            try {
                c = Integer.parseInt(intent.getStringExtra("channel"));
            } catch (Exception ignored) {
            }
        }
        return c;
    }

    /** Bottom-left small countdown label, shown 10s before an alarm switch. */
    private void buildCountdownView() {
        countdownView = new TextView(this);
        countdownView.setTextSize(24);
        countdownView.setTextColor(Color.WHITE);
        countdownView.setBackgroundColor(0xB3000000);
        countdownView.setPadding(28, 12, 28, 12);
        countdownView.setVisibility(View.GONE);
        addContentView(countdownView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        countdownView.bringToFront();
    }

    private void startCountdown(int target) {
        if (target < 0 || target >= CHANNEL_NAMES.length || target == channel) {
            return;
        }
        if (!resumed) {
            // onNewIntent fires BEFORE onResume when the system pulls the
            // activity to the front — remember the target and start the
            // countdown once we are actually visible.
            pendingTarget = target;
            Log.i(TAG, "countdown deferred until resume -> "
                    + CHANNEL_NAMES[target]);
            return;
        }
        Log.i(TAG, "countdown start -> " + CHANNEL_NAMES[target]);
        handler.removeCallbacks(countdownTick);
        countdownTarget = target;
        countdownLeft = 11; // first tick 1s later -> shows 10
        countdownView.setText("即将切换到 "
                + CHANNEL_NAMES[target] + " · 10 秒");
        countdownView.setVisibility(View.VISIBLE);
        countdownView.bringToFront();
        handler.postDelayed(countdownTick, 1000);
    }

    private void hideCountdown() {
        handler.removeCallbacks(countdownTick);
        if (countdownView != null) {
            countdownView.setVisibility(View.GONE);
        }
    }

    /** Switch to channel index c if valid and different. */
    private void switchToChannel(int c) {
        hideCountdown();
        countdownTarget = -1;
        if (c < 0 || c >= CHANNEL_NAMES.length || c == channel) {
            return;
        }
        channel = c;
        sourceIndex = 0;
        panelVisible = false;
        appPanelVisible = false;
        channelPanel.setVisibility(View.GONE);
        appPanel.setVisibility(View.GONE);
        hideSystemUi();
        startPlayback();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int c = readChannelExtra(intent, -1);
        if (c >= 0 && c < CHANNEL_NAMES.length) {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isInteractive()) {
                startCountdown(c); // 10s on-screen countdown, then switch
            } else {
                Log.i(TAG, "alarm while screen off, ignored");
            }
        }
        scheduleChannelAlarm(this, 0); // arm tomorrow 18:58
    }

    /**
     * Schedule the daily alarm for 18:57:50 — 10s before the switch, so the
     * on-screen countdown ends exactly at 18:58. delayMs > 0 schedules a
     * one-shot test alarm instead.
     */
    static void scheduleChannelAlarm(android.content.Context ctx, long delayMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long trigger;
        if (delayMs > 0) {
            trigger = System.currentTimeMillis() + delayMs;
        } else {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 18);
            cal.set(Calendar.MINUTE, 57);
            cal.set(Calendar.SECOND, 50);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            trigger = cal.getTimeInMillis();
        }
        Intent i = new Intent(ctx, HomeActivity.class);
        i.putExtra("channel", 1); // 广州新闻
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1001, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        Log.i("GzTv", "alarm scheduled at " + new java.util.Date(trigger));
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LOW_PROFILE);
    }

    private void buildStatusView() {
        status = new TextView(this);
        status.setText("正在连接 " + CHANNEL_NAMES[channel] + " …");
        status.setTextSize(26);
        status.setGravity(Gravity.CENTER);
        addContentView(status, new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT));
        status.bringToFront();
    }

    private void buildChannelPanel() {
        channelPanel = new LinearLayout(this);
        channelPanel.setOrientation(LinearLayout.VERTICAL);
        channelPanel.setGravity(Gravity.CENTER);
        channelPanel.setBackgroundColor(0xCC000000);
        channelItems = new TextView[CHANNEL_NAMES.length];
        for (int i = 0; i < CHANNEL_NAMES.length; i++) {
            TextView item = new TextView(this);
            item.setText(CHANNEL_NAMES[i]);
            item.setTextSize(30);
            item.setPadding(80, 40, 80, 40);
            item.setGravity(Gravity.CENTER);
            channelPanel.addView(item);
            channelItems[i] = item;
        }
        addContentView(channelPanel, new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT));
        channelPanel.setVisibility(View.GONE);
        channelPanel.bringToFront();
    }

    private void buildAppPanel() {
        appPanel = new LinearLayout(this);
        appPanel.setOrientation(LinearLayout.VERTICAL);
        appPanel.setBackgroundColor(0xF2000000);
        appPanel.setPadding(90, 70, 90, 70);

        appTitle = new TextView(this);
        appTitle.setText("应  用");
        appTitle.setTextSize(32);
        appTitle.setTextColor(Color.rgb(255, 200, 0));
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setGravity(Gravity.CENTER);
        appTitle.setPadding(0, 0, 0, 36);
        appPanel.addView(appTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        appGridView = new GridView(this);
        appGridView.setNumColumns(5);
        appGridView.setHorizontalSpacing(30);
        appGridView.setVerticalSpacing(40);
        appGridView.setGravity(Gravity.CENTER);
        appGridView.setFocusable(true);
        appGridView.setFocusableInTouchMode(true);
        appGridView.setAdapter(new AppAdapter());
        appGridView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                launchApp(position);
            }
        });
        appPanel.addView(appGridView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        addContentView(appPanel, new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT));
        appPanel.setVisibility(View.GONE);
        appPanel.bringToFront();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> raw = pm.queryIntentActivities(main, 0);
        List<AppEntry> entries = new ArrayList<AppEntry>();
        for (ResolveInfo info : raw) {
            if (getPackageName().equals(info.activityInfo.packageName)) {
                continue; // skip ourselves
            }
            AppEntry e = new AppEntry();
            e.label = info.loadLabel(pm).toString();
            e.pkg = info.activityInfo.packageName;
            e.cls = info.activityInfo.name;
            e.icon = info.loadIcon(pm);
            entries.add(e);
        }
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        applySavedOrder(entries);
        appEntries.clear();
        appEntries.addAll(entries);
        Log.i(TAG, "apps=" + appEntries.size());
    }

    /** Reorder entries so apps saved by the user come first, in saved order. */
    private void applySavedOrder(List<AppEntry> entries) {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_APP_ORDER, "");
        if (saved.length() == 0) {
            return;
        }
        final List<String> order = new ArrayList<String>();
        for (String p : saved.split("\\|")) {
            if (p.length() > 0) {
                order.add(p);
            }
        }
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int ia = order.indexOf(a.pkg);
                int ib = order.indexOf(b.pkg);
                if (ia < 0) {
                    ia = Integer.MAX_VALUE;
                }
                if (ib < 0) {
                    ib = Integer.MAX_VALUE;
                }
                if (ia != ib) {
                    return ia - ib;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (AppEntry e : appEntries) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(e.pkg);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(PREF_APP_ORDER, sb.toString()).apply();
        Log.i(TAG, "order saved n=" + appEntries.size());
    }

    /** Swap the focused app with a neighbor in the grid (sort mode). */
    private void moveApp(int from, int to) {
        if (from < 0 || from >= appEntries.size()
                || to < 0 || to >= appEntries.size()) {
            return;
        }
        Collections.swap(appEntries, from, to);
        gridPos = to;
        ((AppAdapter) appGridView.getAdapter()).notifyDataSetChanged();
        appGridView.setSelection(to);
        saveOrder();
    }

    private void launchApp(int position) {
        if (position < 0 || position >= appEntries.size()) {
            return;
        }
        AppEntry e = appEntries.get(position);
        Log.i(TAG, "launch " + e.label);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(e.pkg, e.cls));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            toggleAppPanel(); // close panel behind the launched app
        } catch (Exception ex) {
            Log.e(TAG, "launch failed", ex);
        }
    }

    private void toggleAppPanel() {
        appPanelVisible = !appPanelVisible;
        if (appPanelVisible) {
            loadApps(); // rescan every open so newly installed apps show up
            grabbed = false;
            gridPos = 0;
            refreshAppTitle();
            ((AppAdapter) appGridView.getAdapter()).notifyDataSetChanged();
            appPanel.setVisibility(View.VISIBLE);
            appGridView.requestFocus();
            appGridView.setSelection(0);
        } else {
            appPanel.setVisibility(View.GONE);
            hideSystemUi();
        }
        Log.i(TAG, "appPanel=" + appPanelVisible + " grabbed=" + grabbed);
    }

    private void setGrabbed(boolean on) {
        if (on) {
            int sel = appGridView.getSelectedItemPosition();
            if (sel >= 0 && sel < appEntries.size()) {
                gridPos = sel; // sync with focused icon
            }
        }
        grabbed = on;
        refreshAppTitle();
        ((AppAdapter) appGridView.getAdapter()).notifyDataSetChanged();
        Log.i(TAG, "grabbed=" + grabbed + " pos=" + gridPos);
    }

    private void refreshAppTitle() {
        if (grabbed) {
            appTitle.setText("移动模式：方向键移动 · 确认键放下");
            appTitle.setTextColor(Color.rgb(80, 220, 100));
        } else {
            appTitle.setText("应  用");
            appTitle.setTextColor(Color.rgb(255, 200, 0));
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return appEntries.size();
        }

        @Override
        public Object getItem(int position) {
            return appEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry e = appEntries.get(position);
            LinearLayout cell = (convertView instanceof LinearLayout)
                    ? (LinearLayout) convertView : null;
            if (cell == null) {
                cell = new LinearLayout(HomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);

                ImageView icon = new ImageView(HomeActivity.this);
                icon.setLayoutParams(new LinearLayout.LayoutParams(150, 150));
                icon.setId(1);
                icon.setPadding(6, 6, 6, 6);
                icon.setClipToOutline(true);
                icon.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        int r = view.getWidth() / 5; // rounded corners
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                    }
                });
                cell.addView(icon);

                TextView label = new TextView(HomeActivity.this);
                label.setTextSize(20);
                label.setTextColor(Color.WHITE);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(1);
                label.setPadding(8, 18, 8, 0);
                label.setId(2);
                cell.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            ImageView icon = (ImageView) cell.findViewById(1);
            TextView label = (TextView) cell.findViewById(2);
            icon.setImageDrawable(e.icon);
            label.setText(e.label);
            if (grabbed && position == gridPos) {
                label.setTextColor(Color.rgb(255, 200, 0)); // held icon
                label.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                label.setTextColor(Color.WHITE);
                label.setTypeface(Typeface.DEFAULT);
            }
            return cell;
        }
    }

    private void refreshChannelPanel() {
        for (int i = 0; i < channelItems.length; i++) {
            if (i == channel) {
                channelItems[i].setTextColor(Color.rgb(255, 200, 0));
                channelItems[i].setTypeface(Typeface.DEFAULT_BOLD);
                channelItems[i].setText("▶ " + CHANNEL_NAMES[i]);
            } else {
                channelItems[i].setTextColor(Color.WHITE);
                channelItems[i].setTypeface(Typeface.DEFAULT);
                channelItems[i].setText("   " + CHANNEL_NAMES[i]);
            }
        }
    }

    private void togglePanel() {
        panelVisible = !panelVisible;
        if (panelVisible) {
            refreshChannelPanel();
            channelPanel.setVisibility(View.VISIBLE);
        } else {
            channelPanel.setVisibility(View.GONE);
            hideSystemUi();
        }
    }

    private void switchChannel(int idx) {
        if (idx < 0 || idx >= CHANNEL_NAMES.length) {
            return;
        }
        channel = idx;
        sourceIndex = 0;
        panelVisible = false;
        channelPanel.setVisibility(View.GONE);
        hideSystemUi();
        startPlayback();
    }

    private void startPlayback() {
        if (!surfaceReady) {
            return;
        }
        releasePlayer();
        prepared = false;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText("正在连接 " + CHANNEL_NAMES[channel] + " …");
                status.setVisibility(View.VISIBLE);
            }
        });
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(this, Uri.parse(CHANNEL_URLS[channel][sourceIndex]));
            if (holder != null) {
                player.setDisplay(holder);
            }
            player.setOnPreparedListener(this);
            player.setOnErrorListener(this);
            player.setOnInfoListener(this);
            player.setScreenOnWhilePlaying(true);
            player.prepareAsync();
            Log.i(TAG, "prepareAsync ch=" + CHANNEL_NAMES[channel] + " source#" + sourceIndex);
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "setDataSource failed", e);
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!activityGone) {
                    startPlayback();
                }
            }
        }, RETRY_DELAY_MS);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        status.setVisibility(View.GONE);
        mp.start();
        Log.i(TAG, "playing " + CHANNEL_NAMES[channel]);
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "onError what=" + what + " extra=" + extra + " source#" + sourceIndex);
        prepared = false;
        sourceIndex = (sourceIndex + 1) % CHANNEL_URLS[channel].length;
        scheduleRetry();
        return true;
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
            status.setText("缓冲中 …");
            status.setVisibility(View.VISIBLE);
        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
            status.setVisibility(View.GONE);
        }
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        surfaceReady = true;
        if (!screenOn) {
            return; // standby: stay silent until the screen wakes
        }
        // Fresh start on every (re)creation — the player may hold a stale
        // surface after returning from another app (black screen + audio bug).
        startPlayback();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        // Surface is gone; the player cannot render to it anymore.
        // Release fully so surfaceCreated can restart cleanly.
        surfaceReady = false;
        releasePlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (pendingTarget >= 0) {
            int t = pendingTarget;
            pendingTarget = -1;
            startCountdown(t); // now visible — show the countdown
        }
        hideSystemUi();
        // Only resume here if the surface is still valid; otherwise
        // surfaceCreated() will kick off a fresh playback.
        if (surfaceReady) {
            if (player != null && prepared) {
                player.start();
            } else if (player == null) {
                startPlayback();
            }
        }
    }

    @Override
    protected void onDestroy() {
        activityGone = true;
        handler.removeCallbacksAndMessages(null);
        if (switchReceiver != null) {
            try {
                unregisterReceiver(switchReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Intercept at dispatch level: the GridView consumes dpad keys
        // before Activity.onKeyDown ever sees them.
        if (appPanelVisible) {
            int kc = event.getKeyCode();
            // Swallow the UP of a long-press / drop so the GridView
            // does not fire an item click right after.
            if (swallowUp && event.getAction() == KeyEvent.ACTION_UP
                    && (kc == KeyEvent.KEYCODE_DPAD_CENTER
                    || kc == KeyEvent.KEYCODE_ENTER)) {
                swallowUp = false;
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return super.dispatchKeyEvent(event);
            }
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER
                    || kc == KeyEvent.KEYCODE_ENTER) {
                if (grabbed) {
                    if (event.getRepeatCount() > 0) {
                        return true; // still the same long-press hold
                    }
                    swallowUp = true;
                    setGrabbed(false); // drop the icon here
                    return true;
                }
                if (event.getRepeatCount() >= 3) {
                    swallowUp = true;
                    setGrabbed(true); // long-press OK picks up the icon
                    return true;
                }
                // short press falls through → GridView launches the app
            } else if (grabbed) {
                switch (kc) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        moveApp(gridPos, gridPos - GRID_COLUMNS);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        moveApp(gridPos, gridPos + GRID_COLUMNS);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        moveApp(gridPos, (gridPos % GRID_COLUMNS == 0) ? gridPos : gridPos - 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        moveApp(gridPos, ((gridPos + 1) % GRID_COLUMNS == 0) ? gridPos : gridPos + 1);
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_MENU:
                        setGrabbed(false);
                        return true;
                }
            } else {
                switch (kc) {
                    case KeyEvent.KEYCODE_BACK:
                        toggleAppPanel();
                        return true;
                    case KeyEvent.KEYCODE_MENU:
                        setGrabbed(true);
                        return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (panelVisible) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    channel = (channel + CHANNEL_NAMES.length - 1) % CHANNEL_NAMES.length;
                    refreshChannelPanel();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    channel = (channel + 1) % CHANNEL_NAMES.length;
                    refreshChannelPanel();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    switchChannel(channel);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    togglePanel();
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                toggleAppPanel(); // this IS the home: "exit" = app grid
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
