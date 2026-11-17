package local.shake;

public class MotionTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    // 模拟静止手机和页面的双轴判定。基准更新和计数间隔均在判定通过后推进。
    static int pageScore(int hz, int stepMs) {
        Motion.Stream stream = new Motion.Stream();
        float previousX = 0, previousY = 0;
        int lastAward = 0, score = 0;
        for (int t = 0; t <= 5000; t += stepMs) {
            stream.sample(t * 1000000L, hz, false);
            float[] values = {0, 0, 9.81f};
            if (stream.sampled) stream.apply(values);
            if (Math.abs(values[0] - previousX) > 8 && Math.abs(values[1] - previousY) > 8
                    && t - lastAward > 400) {
                score++;
                previousX = values[0];
                previousY = values[1];
                lastAward = t;
            }
            check(values[2] == 9.81f + values[0]);
        }
        return score;
    }
    // 重放网页的 >100ms 筛选、三轴和差值阈值，覆盖高频设置反而零分的回归。
    static int sampledPageScore(int hz, int sensorMs, int pageMs, int pageOffset) {
        Motion.Stream stream = new Motion.Stream();
        double previous = 9.81;
        int last = pageOffset, score = 0;
        for (int t = 0; t <= 30000; t += sensorMs) {
            stream.sample(t * 1000000L, hz, true);
            if (t - last <= 100 || t - last < pageMs) continue;
            float[] values = {0, 0, 9.81f};
            if (stream.sampled) stream.apply(values);
            double sum = values[0] + values[1] + values[2];
            if (Math.abs(sum - previous) / (t - last) * 10000 > 1000) score++;
            previous = sum;
            last = t;
        }
        return score;
    }
    public static void main(String[] args) {
        // 在具备至少 270 个检测机会的回放中，波形也必须达到 270 次。
        int targetScore = sampledPageScore(10, 5, 110, 0);
        System.out.println("30s, target10, page110ms=" + targetScore);
        check(targetScore >= 270);
        Motion.Run r = new Motion.Run("demo", 2, 30, false, 100);
        check(r.start == 100 && r.end == 30100);
        Motion.Run shortest = new Motion.Run("short", 2, 1, true, 42);
        check(shortest.start == 42 && shortest.end == 1042);
        check(!Motion.valid(0, 1) && !Motion.valid(51, 1) && !Motion.valid(1, 0) && !Motion.valid(1, 301));
        check(Motion.valid(1, 1) && Motion.valid(11, 300) && Motion.valid(50, 300));
        for (int hz = 1; hz <= 10; hz++) {
            for (int ms : new int[]{5, 10, 20}) {
                Motion.Stream s = new Motion.Stream();
                boolean positive = false, negative = false;
                for (int t = 0; t <= 5000; t += ms) {
                    s.sample(t * 1000000L, hz, false);
                    positive |= s.offset > 1;
                    negative |= s.offset < -1;
                }
                check(positive && negative && s.cycles == hz * 5);
                s.sample(9000000000L, hz, false);
                check(!s.sampled && s.cycles == hz * 5);
            }
        }
        // 平滑模式保留原频率，快速模式核对实际完整周期和三轴幅度。
        for (int rate = 1; rate <= 50; rate++) {
            for (boolean quick : new boolean[]{false, true}) {
                Motion.Stream stream = new Motion.Stream();
                float highest = 0, lowest = 0;
                for (int t = 0; t <= 1000; t += 5) {
                    stream.sample(t * 1000000L, rate, quick);
                    if (stream.sampled) {
                        float[] reading = {1f, -2f, 9.81f};
                        stream.apply(reading);
                        check(reading[0] == 1f + stream.offset && reading[1] == -2f + stream.offset
                                && reading[2] == 9.81f + stream.offset);
                    }
                    highest = Math.max(highest, stream.offset);
                    lowest = Math.min(lowest, stream.offset);
                }
                check((quick ? stream.cycles >= 1 && stream.cycles <= rate : stream.cycles == rate)
                        && highest > 1 && lowest < -1);
                if (rate == 50 && !quick) check(highest == 18 && lowest == -18);
            }
        }
        Motion.Stream tooSlow = new Motion.Stream();
        for (int t = 0; t <= 1000; t += 10) {
            tooSlow.sample(t * 1000000L, 50, false);
            check(!tooSlow.sampled && tooSlow.cycles == 0);
        }
        for (int step : new int[]{20, 50}) {
            for (int rate : new int[]{2, 6}) {
                int score = pageScore(rate, step);
                System.out.println("rate=" + rate + ", sampleMs=" + step + ", detected=" + score);
                check(score >= 5);
            }
        }
        check(pageScore(6, 100) == 0);
        for (int step : new int[]{9, 16, 20, 50}) {
            Motion.Stream fast = new Motion.Stream(), smooth = new Motion.Stream();
            float peakFast = 0, peakSmooth = 0;
            for (int t = 0; t <= 5000; t += step) {
                float prevFast = fast.offset, prevSmooth = smooth.offset;
                fast.sample(t * 1000000L, 2, true);
                smooth.sample(t * 1000000L, 2, false);
                peakFast = Math.max(peakFast, Math.abs(fast.offset - prevFast));
                peakSmooth = Math.max(peakSmooth, Math.abs(smooth.offset - prevSmooth));
                check(Math.abs(fast.offset) <= 18 && fast.cycles <= smooth.cycles);
            }
            // 连续上升后的回落应产生明显变化，并保持原有三轴幅度边界。
            check(peakFast >= 16 && peakFast > peakSmooth);
            System.out.println("sampleMs=" + step + ", max change smooth=" + peakSmooth + ", quick=" + peakFast);
        }
        for (int sensorMs : new int[]{5, 9, 16, 20}) {
            for (int pageMs : new int[]{101, 110, 125, 150, 200}) {
                for (int offset : new int[]{0, 31, 77}) {
                    int ten = sampledPageScore(10, sensorMs, pageMs, offset);
                    int forty = sampledPageScore(40, sensorMs, pageMs, offset);
                    check(forty == ten && forty > 0);
                    int actualInterval = ((Math.max(pageMs, 101) + sensorMs - 1) / sensorMs) * sensorMs;
                    int firstSample = ((offset + Math.max(pageMs, 101) + sensorMs - 1) / sensorMs) * sensorMs;
                    int opportunities = 1 + (30000 - firstSample) / actualInterval;
                    if (forty < opportunities - 2 || forty > opportunities) {
                        throw new AssertionError("sensor=" + sensorMs + ", page=" + pageMs
                                + ", offset=" + offset + ", score=" + forty + ", limit=" + opportunities);
                    }
                }
            }
            System.out.println("sensorMs=" + sensorMs + ", page125ms="
                    + sampledPageScore(40, sensorMs, 125, 0));
            System.out.println("sensorMs=" + sensorMs + ", page101ms="
                    + sampledPageScore(10, sensorMs, 101, 0));
        }
        Motion.Stream interrupted = new Motion.Stream();
        interrupted.sample(0, 40, true);
        interrupted.sample(16000000L, 40, true);
        check(interrupted.sampled);
        int before = interrupted.cycles;
        interrupted.sample(9000000000L, 40, true);
        check(!interrupted.sampled && interrupted.cycles == before);
        interrupted.sample(9016000000L, 40, true);
        check(interrupted.sampled && interrupted.cycles == before);
        System.out.println("PASS: boundaries, XYZ, smooth frequency, quick sampling, page detector replay and gaps");
    }
}
