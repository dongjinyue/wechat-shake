package local.shake;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import java.util.UUID;

/** 每次会话只归这个服务管理。所有按钮和定时器在同一线程更新状态。 */
public final class Panel extends Service {
    final Handler handler = new Handler(Looper.getMainLooper());
    WindowManager wm;
    LinearLayout box;
    TextView status;
    Button action;
    NumberFields fields;
    WindowManager.LayoutParams layout;
    Motion.Run run;
    boolean quick;
    int hz = 2, seconds = 30, cycles;
    long online, output, event, failed;
    String result = "", finishedId = "";
    final java.util.Map<String,String> sources = new java.util.TreeMap<>();
    static long now() { return SystemClock.elapsedRealtime(); }
    final BroadcastReceiver feedback = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            String id = i.getStringExtra("id");
            if (run == null ? finishedId.isEmpty() || !finishedId.equals(id) : !run.id.equals(id)) return;
            // 多个进程可能订阅同一个传感器，采用单路最大值避免重复累加。
            cycles = Math.max(cycles, i.getIntExtra("count",0));
            if (i.getBooleanExtra("ready",false)) online = now(); else failed = now();
            output = Math.max(output,i.getLongExtra("output",0));
            event = Math.max(event,i.getLongExtra("event",0));
            long[] gaps = i.getLongArrayExtra("intervals");
            int[] counts = i.getIntArrayExtra("byType");
            String process = i.getStringExtra("process");
            if (process != null && gaps != null && gaps.length == 2 && counts != null && counts.length == 2) {
                StringBuilder line = new StringBuilder();
                for (int index = 0; index < 2; index++) {
                    if (gaps[index] == 0 && counts[index] == 0) continue;
                    line.append(index == 0 ? " 加速度" : " 线性").append(counts[index])
                        .append("次/").append(gaps[index]).append("ms");
                }
                if (line.length() > 0) sources.put(process, line.toString());
            }
            render();
        }
    };
    final Runnable tick = new Runnable() {
        public void run() {
            if (!Settings.canDrawOverlays(Panel.this) || box == null || !box.isAttachedToWindow()) {
                stopSelf(); return;
            }
            if (run != null) {
                if (now() >= run.end) finishRun("已到时");
                else sendBroadcast(Wire.command(run,false,now()));
            }
            render();
            handler.postDelayed(this, run == null ? 600 : 180);
        }
    };
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("panel","摇动控制",NotificationManager.IMPORTANCE_LOW));
        PendingIntent close = PendingIntent.getService(this,1,new Intent(this,Panel.class).setAction("close"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(1,new Notification.Builder(this,"panel").setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("摇动助手").setContentText("点此停止并关闭悬浮控制")
            .setContentIntent(close).setOngoing(true).build());
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(feedback,new IntentFilter(Wire.ACK),null,null,2);
        else registerReceiver(feedback,new IntentFilter(Wire.ACK));
    }
    public int onStartCommand(Intent i,int flags,int startId) {
        if (i == null || "close".equals(i.getAction()) || !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        int rate = i.getIntExtra("hz", Motion.DEFAULT_RATE);
        int length = i.getIntExtra("seconds", Motion.DEFAULT_SECONDS);
        if (Motion.valid(rate,length)) {
            hz = rate;
            seconds = length;
            if (fields != null) {
                fields.rate.setText(String.valueOf(rate));
                fields.duration.setText(String.valueOf(length));
            }
            quick = i.getBooleanExtra("quick", false);
        }
        if (box == null) {
            try { showPanel(); } catch (RuntimeException ex) { stopSelf(); }
        }
        render();
        return START_NOT_STICKY;
    }
    int dp(int n) { return Math.round(getResources().getDisplayMetrics().density*n); }
    void showPanel() {
        wm = getSystemService(WindowManager.class);
        layout = new WindowManager.LayoutParams(dp(205),-2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.y = dp(100);
        box = new LinearLayout(this);
        box.setFocusableInTouchMode(true);
        box.setOnTouchListener((v,e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) finishEditing();
            return false;
        });
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6),dp(4),dp(6),dp(4));
        box.setBackgroundColor(0x80233348);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(dp(5),dp(6),dp(4),dp(4));
        status.setOnTouchListener(new View.OnTouchListener() {
            float x,y;
            int left,top;
            public boolean onTouch(View v,MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    x=e.getRawX(); y=e.getRawY(); left=layout.x; top=layout.y;
                } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    layout.x=Math.max(0,left+Math.round(e.getRawX()-x));
                    layout.y=Math.max(0,top+Math.round(e.getRawY()-y));
                    wm.updateViewLayout(box,layout);
                }
                return true;
            }
        });
        box.addView(status);
        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.VERTICAL);
        fields = new NumberFields(inputs, hz, seconds, true);
        box.addView(inputs);
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        for (EditText input : new EditText[]{fields.rate, fields.duration}) {
            input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            input.setOnTouchListener((v,e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN &&
                        (layout.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                    layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    wm.updateViewLayout(box, layout);
                    input.requestFocus();
                }
                return false;
            });
            input.setOnClickListener(v -> keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT));
            input.setOnEditorActionListener((v,id,e) -> {
                if (id != EditorInfo.IME_ACTION_DONE) return false;
                if (readParameters()) finishEditing();
                return true;
            });
        }
        LinearLayout row = new LinearLayout(this);
        action = new Button(this);
        action.setOnClickListener(v -> {
            if (run != null) {
                finishEditing();
                finishRun("已停止");
            } else {
                if (!readParameters()) return;
                finishEditing();
                run = new Motion.Run(UUID.randomUUID().toString(),hz,seconds,quick,now());
                cycles=0; online=output=event=failed=0; finishedId=result="";
                sources.clear();
            }
            handler.removeCallbacks(tick);
            tick.run();
        });
        row.addView(action,new LinearLayout.LayoutParams(0,-2,1));
        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(v -> stopSelf());
        row.addView(close,new LinearLayout.LayoutParams(0,-2,1));
        box.addView(row);
        wm.addView(box,layout);
        handler.post(tick);
    }
    boolean readParameters() {
        int rate = fields.readRate();
        if (rate == 0) return false;
        int length = fields.readDuration();
        if (length == 0) return false;
        hz = rate;
        seconds = length;
        getSharedPreferences("config", MODE_PRIVATE).edit()
                .putInt("hz", hz).putInt("seconds", seconds).apply();
        return true;
    }
    // 仅在输入数字期间获取键盘焦点，结束编辑时将按键交回微信。
    void finishEditing() {
        if ((layout.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(box.getWindowToken(), 0);
        box.requestFocus();
        layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wm.updateViewLayout(box, layout);
    }
    void render() {
        if (action == null) return;
        long t=now();
        String message,button;
        if (run == null) {
            message = result.isEmpty() ? "就绪" : result+" · 波形 "+cycles+" 周期";
            message += (quick ? "\n连续斜坡 · 约 " + Motion.rhythm(hz) + " 周期/秒" : "\n平滑");
            button="开始";
        } else {
            if (output>0 && t-output<Motion.LEASE) message="正在输出";
            else if (event>0 && t-event<Motion.LEASE) message="采样不足，可降低频率";
            else if (online>0 && t-online<Motion.LEASE) message="模块在线，等待传感器";
            else if (failed>0 && t-failed<Motion.LEASE) message="当前系统适配失败";
            else message="未收到微信模块回执";
            message+="\n波形 "+cycles+" 周期 · 剩 "+Math.max(0,(run.end-t+999)/1000)+" 秒";
            if (run.quick) message += "\n连续斜坡 · 约 " + Motion.rhythm(run.hz) + " 周期/秒";
            button="停止";
        }
        if (run == null && !result.isEmpty()) {
            for (java.util.Map.Entry<String,String> source : sources.entrySet()) {
                String name = source.getKey();
                if (name.equals(Wire.WECHAT)) name = "主进程";
                else if (name.startsWith(Wire.WECHAT + ":")) name = name.substring(Wire.WECHAT.length()+1);
                message += "\n" + name + source.getValue();
            }
        }
        status.setText(message);
        action.setText(button);
    }
    void finishRun(String label) {
        if (run == null) return;
        sendBroadcast(Wire.command(run,true,now()));
        finishedId=run.id;
        result=label;
        run=null;
    }
    public void onDestroy() {
        finishRun("已关闭");
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(feedback);
        if (box!=null && box.isAttachedToWindow()) wm.removeViewImmediate(box);
        stopForeground(true);
        super.onDestroy();
    }
    public IBinder onBind(Intent i) { return null; }
}
