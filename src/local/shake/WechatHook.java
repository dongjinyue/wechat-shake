package local.shake;

import android.app.Application;
import android.content.*;
import android.hardware.Sensor;
import android.os.*;
import android.util.SparseArray;
import java.util.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 仅在微信进程内更换该次分发的数组。底层传感器以及其他应用继续使用原始数据。 */
public final class WechatHook implements IXposedHookLoadPackage {
    private String id = "", stopped = "", process;
    private long start, end, lease, newest = -1, lastOutput, lastEvent;
    private int hz, count;
    private boolean available, quick;
    // 每个进程分别保留普通/线性加速度最新一次观测间隔，用于核对实际收到数据的位置。
    private final long[] intervalMs = new long[2];
    private final int[] streamCycles = new int[2];
    private final Map<Object, SparseArray<Motion.Stream>> streams = new WeakHashMap<>();

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!Wire.WECHAT.equals(lp.packageName)) return;
        process = lp.processName;
        try {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager$SensorEventQueue",
                lp.classLoader, "dispatchSensorEvent", int.class, float[].class, int.class, long.class,
                new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        synchronized (WechatHook.this) {
                            long now = SystemClock.elapsedRealtime();
                            if (now < start || now >= lease || now >= end) return;
                            try { replace(p, now); }
                            catch (RuntimeException | LinkageError ex) {
                                available = false;
                                lease = 0;
                                XposedBridge.log(ex);
                            }
                        }
                    }
                });
            available = true;
        } catch (Throwable ex) { XposedBridge.log(ex); }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                Application app = (Application)p.thisObject;
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    public void onReceive(Context c, Intent i) { receive(c, i); }
                };
                IntentFilter filter = new IntentFilter(Wire.COMMAND);
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Wire.PERMISSION, null, 2);
                else app.registerReceiver(receiver, filter, Wire.PERMISSION, null);
            }
        });
    }
    private synchronized void receive(Context c, Intent i) {
        long now = SystemClock.elapsedRealtime(), created = i.getLongExtra("created", -1);
        String incoming = i.getStringExtra("id");
        if (incoming == null || created < android.os.Process.getStartElapsedRealtime() || created < newest) return;
        if (i.getBooleanExtra("stop", false)) {
            if (incoming.equals(id)) report(c);
            stopped = incoming;
            newest = created;
            lease = 0;
            return;
        }
        long sent = i.getLongExtra("sent", 0), deadline = i.getLongExtra("end", 0);
        if (incoming.equals(stopped) || now >= sent + Motion.LEASE || now >= deadline) return;
        if (!incoming.equals(id)) {
            id = incoming;
            newest = created;
            start = created;
            end = deadline;
            quick = i.getBooleanExtra("quick", false);
            hz = i.getIntExtra("hz", 0);
            count = 0;
            lastOutput = lastEvent = 0;
            streams.clear();
            Arrays.fill(intervalMs, 0);
            Arrays.fill(streamCycles, 0);
        }
        if (available && hz >= 1 && hz <= Motion.MAX_RATE) lease = Math.min(end, sent + Motion.LEASE);
        report(c);
    }
    private void report(Context c) {
        c.sendBroadcast(new Intent(Wire.ACK).setPackage(Wire.APP).putExtra("id", id)
            .putExtra("process", process).putExtra("ready", available).putExtra("count", count)
            .putExtra("intervals", intervalMs.clone()).putExtra("byType", streamCycles.clone())
            .putExtra("output", lastOutput).putExtra("event", lastEvent));
    }
    private void replace(XC_MethodHook.MethodHookParam p, long now) {
        int handle = (Integer)p.args[0];
        long ns = (Long)p.args[3];
        if (ns < start * 1000000L || ns >= end * 1000000L || now - ns / 1000000L > Motion.LEASE) return;
        Object manager = XposedHelpers.getObjectField(p.thisObject, "mManager");
        Map<?, ?> sensors = (Map<?, ?>)XposedHelpers.getObjectField(manager, "mHandleToSensor");
        Sensor sensor = (Sensor)sensors.get(handle);
        if (sensor == null || (sensor.getType() != Sensor.TYPE_ACCELEROMETER &&
                sensor.getType() != Sensor.TYPE_LINEAR_ACCELERATION)) return;
        float[] values = (float[])p.args[1];
        if (values == null || values.length < 3) return;
        lastEvent = now;
        SparseArray<Motion.Stream> byHandle = streams.get(p.thisObject);
        if (byHandle == null) {
            byHandle = new SparseArray<>();
            streams.put(p.thisObject, byHandle);
        }
        Motion.Stream stream = byHandle.get(handle);
        if (stream == null) {
            stream = new Motion.Stream();
            byHandle.put(handle, stream);
        }
        stream.sample(ns, hz, quick);
        int typeIndex = sensor.getType() == Sensor.TYPE_ACCELEROMETER ? 0 : 1;
        intervalMs[typeIndex] = stream.gapMs;
        if (!stream.sampled) return;
        float[] copy = values.clone();
        stream.apply(copy);
        streamCycles[typeIndex] = Math.max(streamCycles[typeIndex], stream.cycles);
        p.args[1] = copy;
        count = Math.max(count, stream.cycles);
        lastOutput = now;
    }
}