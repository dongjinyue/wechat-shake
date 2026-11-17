package local.shake;

/** 时间均来自开机后的单调时钟，调整手机日期不会延长任务。 */
final class Motion {
    static final int DEFAULT_RATE = 2, DEFAULT_SECONDS = 30;
    static final int MAX_RATE = 50;
    // 快速模式持续改变读数，减少固定检测间隔连续采到相同峰值造成的漏计。
    private static final long[] PERIOD_JITTER_NS = {0, 12000000L, 24000000L, 6000000L};
    static long quickPeriod(int hz) { return Math.max(300000000L, 1000000000L / hz); }
    static String rhythm(int hz) {
        double rate = 1000000000.0 / (quickPeriod(hz) + 10500000L);
        return String.format(java.util.Locale.ROOT, "%.1f", rate);
    }
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
            long period = quick ? quickPeriod(hz) + PERIOD_JITTER_NS[cycles % PERIOD_JITTER_NS.length] : 1000000000L;
            if (gap >= (quick ? period / 2 : period / (2 * hz))) {
                phase = 0;
                return;
            }
            phase += quick ? gap : gap * hz;
            if (phase >= period) {
                phase -= period;
                cycles++;
                period = quick ? quickPeriod(hz) + PERIOD_JITTER_NS[cycles % PERIOD_JITTER_NS.length] : 1000000000L;
            }
            if (quick) {
                // 斜坡从负峰持续上升后回落。周期约 300ms 时，间隔 100～200ms 的
                // 两次检测在上升段和跨周期时都有明显差值，避免正负峰驻留的盲区。
                double position = (double)phase / period;
                offset = (float)(36.0 * position - 18.0);
            } else {
                offset = (float)(18.0 * Math.sin(2.0 * Math.PI * phase / 1000000000L));
            }
            sampled = true;
        }
    }
}
