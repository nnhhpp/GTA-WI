/** A car. Player physics (with map collision) or kinematic AI (traffic / cops). */
public final class Car extends Mob {
    static final float M = MapR.M;
    public float vx, vy, w;          // px/s, rad/s
    public float hl, hw;             // half length / width px (sprite scale)
    public int type;
    public boolean ai = true, siren, wreck, cop, locked;
    public float damage, fireT, smokeT;
    // AI
    public int edge = -1, from, to, mode; public float cruise, loose, spd, tx, ty, stuckT, holdT;
    public int crew;                 // cops still inside
    public float ramT;               // time spent trying to run Claude down
    public float imp, impX, impY;
    public float farT;
    public float wreckAt = -99;      // animT when it blew up (fire for a few seconds, then a smoking shell)               // cop car: time spent far away off screen (then it is moved closer)    // last wall impact (px/s) and where it happened (sparks)
    // player input
    public float throttle, steer; public boolean handbrake;

    public Car(int type, float widthM, float lengthM) { this.type = type; hw = widthM * 15f / 2; hl = lengthM * 15f / 2; }

    public float speed() { return (float) Math.sqrt(vx * vx + vy * vy); }

    public float drift;              // 0..1 power oversteer: gas + full lock at low speed = donuts / drifts

    public void physics(MapR m, float dt, float maxMS) {
        float fx = dirX(), fy = dirY(), rx = -fy, ry = fx;
        float vf = vx * fx + vy * fy, vl = vx * rx + vy * ry;
        float maxF = maxMS * M, maxR = 8 * M, sp = Math.abs(vf) / M;
        // gas + steering below ~20 m/s breaks the rear loose (strongest from a standstill), it grips again when you let go
        float want = throttle > 0 && steer != 0 && !handbrake && vf > -0.5f * M ? Math.max(0, Math.min(1, 1.2f - sp / (drift > 0.3f ? 24f : 18f))) : 0;   // breaks loose up to ~20 m/s, once sliding holds on longer
        if (drift < want) drift = Math.min(want, drift + dt * 2.5f); else drift = Math.max(want, drift - dt * 1.6f);
        if (throttle > 0) { if (vf < -0.5f * M) vf += 22 * M * dt; else vf += throttle * 9 * M * dt * (1 - vf / maxF) * (1 - 0.5f * drift); }
        else if (throttle < 0) { if (vf > 0.5f * M) vf -= 22 * M * dt; else vf -= 6 * M * dt * (1 + vf / maxR); }
        else { float d = 2.5f * M * dt; if (vf > d) vf -= d; else if (vf < -d) vf += d; else vf = 0; }
        if (handbrake) { float d = 9 * M * dt; if (vf > d) vf -= d; else if (vf < -d) vf += d; else vf = 0; }
        float grip = handbrake ? 1.6f : 9f - 7.6f * drift;           // rear tyres let go -> the car slides sideways
        vl -= vl * Math.min(1f, grip * dt);
        float turn = steer * 2.4f * Math.min(1f, sp / 6f) / (1 + sp / 40f);
        if (handbrake) turn *= 1.4f;
        w = vf >= 0 ? turn : -turn;
        w += steer * drift * 3.3f;                                    // the tail swings out: spins on the spot at low speed
        float oa = a; a += w * dt;
        if (!fits(m)) a = oa;
        // the velocity turns with the car only as far as the tyres grip: when drifting the car keeps sliding the old way
        float ka = oa + (a - oa) * (1 - 0.85f * drift);
        fx = G.sin(ka); fy = -G.cos(ka); rx = -fy; ry = fx;
        vx = fx * vf + rx * vl; vy = fy * vf + ry * vl;
        float pv = -w * hl * 0.7f * drift;                             // spinning round the front wheels: the tail swings wide
        move(m, (vx - dirY() * pv) * dt, (vy + dirX() * pv) * dt);
    }

    /** sub-stepped move with sliding; returns the impact speed (px/s) */
    public float move(MapR m, float dx, float dy) {
        float span = Math.max(Math.abs(dx), Math.abs(dy));
        int n = 1 + (int) (span / 3f);
        float sx = dx / n, sy = dy / n, imp = 0;
        for (int i = 0; i < n; i++) {
            float ox = x, oy = y;
            x = ox + sx; y = oy + sy;
            if (fits(m)) continue;
            y = oy;
            if (sx != 0 && fits(m)) { imp = Math.max(imp, hit(Math.abs(vy))); vy *= -0.15f; sy = 0; continue; }
            x = ox; y = oy + sy;
            if (sy != 0 && fits(m)) { imp = Math.max(imp, hit(Math.abs(vx))); vx *= -0.15f; sx = 0; continue; }
            x = ox; y = oy;
            imp = Math.max(imp, hit(speed()));
            vx *= -0.3f; vy *= -0.3f;
            break;
        }
        if (imp > this.imp) {
            float l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l > 0.001f) { float ux = dx / l, uy = dy / l, fx = dirX(), fy = dirY(); float e = Math.abs(ux * fx + uy * fy) * hl + Math.abs(-ux * fy + uy * fx) * hw; impX = x + ux * e; impY = y + uy * e; this.imp = imp; }
        }
        return imp;
    }
    static final float TOUGH = 1 / 1.2f;                    // v0.7.5: every car (yours, cops, traffic) takes 20% more punishment
    void hurt(float d) { damage += d * TOUGH; }
    float hit(float imp) { if (imp > 4 * M) hurt(imp / M * 0.4f); return imp; }    // cars are twice as tough as in v0.5

    public boolean fits(MapR m) {
        float fx = dirX(), fy = dirY(), rx = -fy, ry = fx;
        float l = hl * 0.9f, wv = hw * 0.8f;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            if (i == 0 && j == 0) continue;
            if (m.solid(x + fx * l * i + rx * wv * j, y + fy * l * i + ry * wv * j)) return false;
        }
        return true;
    }

    /** lowest map y of the footprint (draw order key) */
    public int foot() { return (int) (y + Math.abs(dirY()) * hl + Math.abs(dirX()) * hw); }
}
