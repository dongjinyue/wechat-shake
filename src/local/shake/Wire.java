package local.shake;

import android.content.*;

final class Wire {
    static final String APP = "local.shake", WECHAT = "com.tencent.mm";
    static final String COMMAND = APP + ".COMMAND", ACK = APP + ".ACK", PERMISSION = APP + ".CONTROL";
    // 包定向广播可送达微信已经注册的多个进程。接收控制消息需要发送方具备签名权限。
    static Intent command(Motion.Run r, boolean stop, long now) {
        return new Intent(COMMAND).setPackage(WECHAT).putExtra("id", r.id)
            .putExtra("quick", r.quick).putExtra("hz", r.hz).putExtra("created", r.start).putExtra("end", r.end)
            .putExtra("stop", stop).putExtra("sent", now);
    }
}
