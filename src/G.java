/** Small math helpers (CLDC 1.1 has no atan2). */
public final class G {
    public static final float PI = 3.14159265f, TWO_PI = 6.2831853f;

    public static float sin(float a) { return (float) Math.sin(a); }
    public static float cos(float a) { return (float) Math.cos(a); }

    public static float atan2(float y, float x) {
        if (x == 0 && y == 0) return 0;
        float ax = Math.abs(x), ay = Math.abs(y);
        float mn = Math.min(ax, ay), mx = Math.max(ax, ay);
        float t = mn / mx, s = t * t;
        float r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * t + t;
        if (ay > ax) r = 1.57079637f - r;
        if (x < 0) r = PI - r;
        if (y < 0) r = -r;
        return r;
    }

    /** heading (0 = up, clockwise) of vector dx,dy in screen coords */
    public static float heading(float dx, float dy) { return atan2(dx, -dy); }

    public static float wrap(float a) {
        while (a > PI) a -= TWO_PI;
        while (a < -PI) a += TWO_PI;
        return a;
    }

    public static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static int frame(float a, int n) {
        int f = (int) Math.floor(a * n / TWO_PI + 0.5f) % n;
        return f < 0 ? f + n : f;
    }
}
