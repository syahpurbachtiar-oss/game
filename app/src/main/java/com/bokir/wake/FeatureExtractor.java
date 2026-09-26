package com.bokir.wake;

public class FeatureExtractor {
    public static float[] extract(short[] audio, int length) {
        if (audio == null || length < 1600) return null;

        int start = 0, end = length - 1;
        int max = 1;
        for (int i = 0; i < length; i++) {
            int v = Math.abs(audio[i]);
            if (v > max) max = v;
        }
        int threshold = Math.max(350, (int)(max * 0.08f));

        while (start < end && Math.abs(audio[start]) < threshold) start++;
        while (end > start && Math.abs(audio[end]) < threshold) end--;
        int n = end - start + 1;
        if (n < 1600) return null;

        final int bins = 64;
        float[] out = new float[bins * 3];
        float maxRms = 1f;
        float maxDiff = 1f;

        for (int b = 0; b < bins; b++) {
            int s = start + (int)((long)n * b / bins);
            int e = start + (int)((long)n * (b + 1) / bins);
            if (e <= s) e = s + 1;

            double sumSq = 0;
            double sumDiff = 0;
            int zc = 0;
            short prev = audio[s];
            for (int i = s; i < e && i < length; i++) {
                short cur = audio[i];
                sumSq += (double)cur * cur;
                if (i > s) {
                    sumDiff += Math.abs((int)cur - (int)prev);
                    if ((cur >= 0 && prev < 0) || (cur < 0 && prev >= 0)) zc++;
                }
                prev = cur;
            }
            int count = Math.max(1, e - s);
            float rms = (float)Math.sqrt(sumSq / count);
            float diff = (float)(sumDiff / count);
            float zcr = (float)zc / count;

            out[b] = rms;
            out[bins + b] = zcr;
            out[bins * 2 + b] = diff;
            if (rms > maxRms) maxRms = rms;
            if (diff > maxDiff) maxDiff = diff;
        }

        for (int b = 0; b < bins; b++) {
            out[b] /= maxRms;
            out[bins * 2 + b] /= maxDiff;
        }

        double norm = 0;
        for (float v : out) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm < 1e-6) return null;
        for (int i = 0; i < out.length; i++) out[i] /= (float)norm;
        return out;
    }

    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1f;
        float dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    public static String encode(float[] f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(f[i]);
        }
        return sb.toString();
    }

    public static float[] decode(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        float[] f = new float[p.length];
        try {
            for (int i = 0; i < p.length; i++) f[i] = Float.parseFloat(p[i]);
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
