package local.shake;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.widget.*;

public final class Controls extends Activity {
    private NumberFields fields;
    private TextView permission;
    private SharedPreferences prefs;
    // 悬浮窗保存后同步主界面，避免下次打开面板时旧值覆盖新值。
    private final SharedPreferences.OnSharedPreferenceChangeListener sync = (p, key) -> {
        if ("hz".equals(key)) fields.rate.setText(String.valueOf(p.getInt(key, Motion.DEFAULT_RATE)));
        if ("seconds".equals(key)) fields.duration.setText(String.valueOf(p.getInt(key, Motion.DEFAULT_SECONDS)));
    };
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private TextView label(LinearLayout box, String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(17);
        v.setPadding(0,dp(12),0,dp(8));
        box.addView(v);
        return v;
    }
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("摇动助手");
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(12),dp(20),dp(20));
        scroll.addView(box);
        setContentView(scroll);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        fields = new NumberFields(box, prefs.getInt("hz", Motion.DEFAULT_RATE),
                prefs.getInt("seconds", Motion.DEFAULT_SECONDS));
        prefs.registerOnSharedPreferenceChangeListener(sync);
        Switch quick = new Switch(this);
        quick.setText("快速变向");
        quick.setChecked(prefs.getBoolean("quick", true));
        box.addView(quick);
        permission = label(box, "");
        Button overlay = new Button(this);
        overlay.setText("管理悬浮窗权限");
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        box.addView(overlay);
        Button open = new Button(this);
        open.setText("打开悬浮控制");
        open.setOnClickListener(v -> {
            int hz = fields.readRate();
            if (hz == 0) return;
            int seconds = fields.readDuration();
            if (seconds == 0) return;
            if (!Settings.canDrawOverlays(this)) { overlay.performClick(); return; }
            prefs.edit().putInt("hz", hz).putInt("seconds", seconds).putBoolean("quick", quick.isChecked()).apply();
            startForegroundService(new Intent(this, Panel.class).putExtra("hz",hz).putExtra("seconds", seconds)
                    .putExtra("quick", quick.isChecked()));
        });
        box.addView(open);
        label(box, "在 LSPosed（模块框架）里启用本模块，作用域选微信，重新打开微信。进入活动页面后点悬浮窗的「开始」。");
    }
    public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(sync);
        super.onDestroy();
    }
    public void onResume() {
        super.onResume();
        permission.setText(Settings.canDrawOverlays(this) ? "悬浮窗权限已开启" : "需要悬浮窗权限才能在微信页面上控制");
    }
}