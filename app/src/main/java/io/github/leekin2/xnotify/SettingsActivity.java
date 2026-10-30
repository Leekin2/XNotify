package io.github.leekin2.xnotify;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import io.github.libxposed.service.XposedService;

/**
 * Opened from LSPosed: one switch per feature. The values live in LSPosed's remote preferences,
 * which the hooks read when System UI or Interconnectivity starts.
 */
public final class SettingsActivity extends Activity {
    static final String GROUP = "settings";
    /** Phone → tablet notifications (Interconnectivity, tablet pop-ups). */
    static final String FORWARD = "forward";
    /** Xiaomi reply box and buttons for every notification (System UI). */
    static final String REPLY = "reply";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * dp);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, pad, pad, pad);

        XposedService service = ScopeApp.service;
        SharedPreferences prefs = service == null ? null : service.getRemotePreferences(GROUP);
        if (prefs == null) {
            list.addView(text(getString(R.string.settings_no_service), 14));
            // LSPosed binds a moment after the process starts; reopen once it has.
            list.postDelayed(() -> { if (ScopeApp.service != null && !isFinishing()) recreate(); }, 1_000);
        }
        list.addView(toggle(prefs, FORWARD, R.string.settings_forward, R.string.settings_forward_summary));
        list.addView(toggle(prefs, REPLY, R.string.settings_reply, R.string.settings_reply_summary));
        list.addView(text(getString(R.string.settings_restart), 13));

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(list);
        setContentView(scroll);
    }

    private LinearLayout toggle(SharedPreferences prefs, String key, int title, int summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, Math.round(20 * getResources().getDisplayMetrics().density));
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        toggle.setChecked(prefs == null || prefs.getBoolean(key, true));
        toggle.setEnabled(prefs != null);
        toggle.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        row.addView(toggle);
        TextView hint = text(getString(summary), 13);
        hint.setAlpha(0.7f);
        row.addView(hint);
        return row;
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }
}
