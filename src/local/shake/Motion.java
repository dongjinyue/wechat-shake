package local.shake;

/** 时间均来自开机后的单调时钟，调整手机日期不会延长任务。 */
final class Motion {
    static final int DEFAULT_RATE = 2, DEFAULT_SECONDS = 30;
    static final int MAX_RATE = 50;
    static final long LEASE = 700;
    static boolean valid(int hz, int seconds) {
        return hz >= 1 && hz <= MAX_RATE && seconds >= 1 && seconds <= 300;
    }
    static final class Run {
        final String id;
        final int hz;
        final boolean quick;
        final long start, end;
        Run(String id, int hz, int seconds, boolean quick, long now) {
            if (!valid(hz, seconds)) throw new IllegalArgumentException("频率或时长超出范围");
            this.id = id;
            this.hz = hz;
            this.quick = quick;
            start = now;
            end = start + seconds * 1000L;
        }
    }
    /** 每个监听器的每种传感器单独记录。间隔太大时丢弃残缺周期，避免把断流算成摇动。 */
    static final class Stream {
        private long previous = -1, phase;
        int cycles;
        long gapMs;
        boolean sampled;
        float offset;
        // 在三个方向上叠加同相的运动分量，保留真实读数中的重力。
        void apply(float[] values) {
            values[0] += offset;
            values[1] += offset;
            values[2] += offset;
        }
        void sample(long ns, int hz, boolean quick) {
            sampled = false;
            if (ns <= previous) return;
            long gap = previous < 0 ? Long.MAX_VALUE : ns - previous;
            gapMs = previous < 0 ? 0 : gap / 1000000L;
            previous = ns;
            if (gap >= 1000000000L / (2 * hz)) {
                phase = 0;
                return;
            }
            phase += gap * hz;
            cycles += (int)(phase / 1000000000L);
            phase %= 1000000000L;
            if (quick) {
                // 三轴在约 20 毫秒内从负峰切到正峰，半周期后反向；调低频率只拉长两次变向之间的间隔。
                double position = phase / 1000000000.0;
                double distance = position <= 0.5 ? Math.min(position, 0.5 - position)
                        : -Math.min(position - 0.5, 1.0 - position);
                // 高频时缩短过渡段，让波形在四分之一周期内仍能达到原设定峰值。
                double normalized = distance / Math.min(0.25, hz * 0.010);
                offset = (float)(18.0 * Math.max(-1.0, Math.min(1.0, normalized)));
            } else {
                offset = (float)(18.0 * Math.sin(2.0 * Math.PI * phase / 1000000000L));
            }
            sampled = true;
        }
    }
}
