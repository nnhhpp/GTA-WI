import java.io.*;
import java.util.*;
import javax.microedition.lcdui.*;

/** Chunk 01: tile pages streamed by a background thread into a circular back buffer (Gangstar style),
 *  occlusion re-cover per sprite, 8 px collision bits, props, GTA path graphs and the Tiled objects. */
public final class MapR {
    static final int T = 16, TL = Graphics.TOP | Graphics.LEFT;
    static final float M = 12f;                       // map px per metre
    static final int POOL_U = 0, POOL_O = 1, POOL_B = 2;
    static final String[] PRE = {"/c/u", "/c/o", "/c/b"};

    public int W16, H16, PW, PH, nCells;
    short[] um, brm, dem, rlm, bm, bb;
    // collision
    public int CW, CH; byte[] col;
    // props
    public int nProps; short[] pImg, pX, pY, pFoot, pFx; byte[] pR; boolean[] pDown, pOv; short[] piW, piH;
    Image[] pim; int[] pimUse; int pimCount, PIMAX = 999;
    int[][] pBlock; int PBW, PBH;                     // pole index per 128 px block
    // pages
    final Image[][] pg = new Image[3][]; final int[][] pgUse = new int[3][]; final int[] pgCnt = new int[3];
    final int[] cap = {999, 999, 999};     // all pages kept (decoded once at game start); WI-Cache overrides, OOM falls back to streaming
    final byte[][] queued = new byte[3][];
    final int[] qPool = new int[512], qPage = new int[512]; int qHead, qTail;          // prefetch ring
    final int[] uPool = new int[256], uPage = new int[256]; int uHead, uTail;          // urgent ring (visible misses)
    volatile boolean running = true; volatile int fr; public volatile int loads; volatile long loadMsTotal; volatile int loaderBusyFrame = -1;
    // back buffer
    Image buf; Graphics bg; int BWt, BHt, BW, BH, vx0 = -9999, vy0 = -9999, SW, SH;
    int[] missX = new int[512], missY = new int[512]; int nMiss; public int missesTotal; boolean missOver;
    public int camX, camY;
    // paths [0] car [1] ped
    public short[][] px = new short[2][], py = new short[2][];
    public int[][][] adj = new int[2][][];
    public short[][] ea = new short[2][], eb = new short[2][];
    public byte[][] el = new byte[2][], er = new byte[2][], en = new byte[2][];
    // objects
    public Vector obj = new Vector();
    // cover bookkeeping
    public boolean covered;

    public MapR(int sw, int sh, String cache) throws IOException {
        SW = sw; SH = sh;
        if (cache != null) { int[] v = Game.nums(cache); if (v.length >= 4) { cap[0] = v[0]; cap[1] = v[1]; cap[2] = v[2]; PIMAX = v[3]; } }
        DataInputStream in = new DataInputStream(getClass().getResourceAsStream("/c/map"));
        in.readShort(); W16 = in.readShort(); H16 = in.readShort();
        int nU = in.readShort(), nO = in.readShort(), nB = in.readShort(); in.readShort(); in.readShort();
        nCells = W16 * H16; PW = W16 * T; PH = H16 * T;
        um = rd(in, nCells); brm = rd(in, nCells); dem = rd(in, nCells); rlm = rd(in, nCells); bm = rd(in, nCells); bb = rd(in, nCells);
        nProps = in.readShort(); int ni = in.readShort();
        piW = new short[ni]; piH = new short[ni]; pim = new Image[ni]; pimUse = new int[ni];
        for (int i = 0; i < ni; i++) { piW[i] = in.readShort(); piH[i] = in.readShort(); }
        pImg = new short[nProps]; pX = new short[nProps]; pY = new short[nProps]; pFoot = new short[nProps]; pFx = new short[nProps]; pR = new byte[nProps]; pDown = new boolean[nProps]; pOv = new boolean[nProps];
        for (int i = 0; i < nProps; i++) { pImg[i] = in.readShort(); pX[i] = in.readShort(); pY[i] = in.readShort(); pFoot[i] = in.readShort(); pFx[i] = in.readShort(); int r = in.readByte(); pR[i] = (byte) (r & 0x3f); pOv[i] = (r & 0x40) != 0; }
        in.close();
        int[] n = {nU, nO, nB};
        for (int p = 0; p < 3; p++) { pg[p] = new Image[n[p]]; pgUse[p] = new int[n[p]]; queued[p] = new byte[n[p]]; }
        // collision
        in = new DataInputStream(getClass().getResourceAsStream("/c/col"));
        in.readInt(); CW = in.readUnsignedShort(); CH = in.readUnsignedShort(); in.readByte();
        col = new byte[((CW + 7) >> 3) * CH]; in.readFully(col); in.close();
        // pole blocks
        PBW = (PW >> 7) + 1; PBH = (PH >> 7) + 1;
        int[] cnt = new int[PBW * PBH];
        for (int i = 0; i < nProps; i++) if (pR[i] > 0) cnt[(pFoot[i] >> 7) * PBW + (pFx[i] >> 7)]++;
        pBlock = new int[PBW * PBH][];
        for (int b = 0; b < cnt.length; b++) pBlock[b] = new int[cnt[b]];
        for (int b = 0; b < cnt.length; b++) cnt[b] = 0;
        for (int i = 0; i < nProps; i++) if (pR[i] > 0) { int b = (pFoot[i] >> 7) * PBW + (pFx[i] >> 7); pBlock[b][cnt[b]++] = i; }
        // paths
        in = new DataInputStream(getClass().getResourceAsStream("/c/paths"));
        for (int g = 0; g < 2; g++) {
            int nn = in.readShort(); px[g] = new short[nn]; py[g] = new short[nn];
            for (int i = 0; i < nn; i++) { px[g][i] = in.readShort(); py[g][i] = in.readShort(); }
            int ne = in.readShort(); ea[g] = new short[ne]; eb[g] = new short[ne]; el[g] = new byte[ne]; er[g] = new byte[ne]; en[g] = new byte[ne];
            int[] deg = new int[nn];
            for (int i = 0; i < ne; i++) { ea[g][i] = in.readShort(); eb[g][i] = in.readShort(); el[g][i] = in.readByte(); er[g][i] = in.readByte(); en[g][i] = in.readByte(); deg[ea[g][i]]++; deg[eb[g][i]]++; }
            adj[g] = new int[nn][];
            for (int i = 0; i < nn; i++) { adj[g][i] = new int[deg[i]]; deg[i] = 0; }
            for (int i = 0; i < ne; i++) { int a = ea[g][i], b = eb[g][i]; adj[g][a][deg[a]++] = i; adj[g][b][deg[b]++] = i; }
        }
        in.close();
        String o = Game.readText("/c/obj"); int p = 0;
        while (p < o.length()) {
            int e = o.indexOf('\n', p); if (e < 0) e = o.length();
            String l = o.substring(p, e).trim(); p = e + 1;
            if (l.length() > 0) obj.addElement(Game.split(l));
        }
        BWt = (SW + T - 1) / T + 1; BHt = (SH + T - 1) / T + 1; BW = BWt * T; BH = BHt * T;
        buf = Image.createImage(BW, BH); bg = buf.getGraphics();
        new Thread(new Loader()).start();
    }
    static short[] rd(DataInputStream in, int n) throws IOException { short[] s = new short[n]; for (int i = 0; i < n; i++) s[i] = in.readShort(); return s; }

    // ------------------------------------------------------------ collision
    public boolean solid(float x, float y) {
        int cx = (int) x >> 3, cy = (int) y >> 3;
        if (x < 0 || y < 0 || cx >= CW || cy >= CH) return true;
        return (col[cy * ((CW + 7) >> 3) + (cx >> 3)] & (0x80 >> (cx & 7))) != 0;
    }
    public boolean solidCell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= CW || cy >= CH) return true;
        return (col[cy * ((CW + 7) >> 3) + (cx >> 3)] & (0x80 >> (cx & 7))) != 0;
    }
    /** line of sight on the collision grid (buildings block bullets) */
    public boolean los(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay; int n = (int) (Math.max(Math.abs(dx), Math.abs(dy)) / 6) + 1;
        for (int i = 1; i < n; i++) { float t = (float) i / n; if (solid(ax + dx * t, ay + dy * t)) return false; }
        return true;
    }
    /** standing poles near (x,y) within r px: returns count, fills out[] */
    public int poles(float x, float y, float r, int[] out) {
        int n = 0;
        int bx0 = Math.max(0, (int) (x - r) >> 7), bx1 = Math.min(PBW - 1, (int) (x + r) >> 7);
        int by0 = Math.max(0, (int) (y - r) >> 7), by1 = Math.min(PBH - 1, (int) (y + r) >> 7);
        for (int by = by0; by <= by1; by++) for (int bx = bx0; bx <= bx1; bx++) {
            int[] l = pBlock[by * PBW + bx];
            for (int k = 0; k < l.length && n < out.length; k++) { int i = l[k]; if (!pDown[i]) out[n++] = i; }
        }
        return n;
    }

    // ------------------------------------------------------------ pages
    Image page(int pool, int p) { Image im = pg[pool][p]; pgUse[pool][p] = fr; if (im == null) urgent(pool, p); return im; }
    void request(int pool, int p) {
        synchronized (qPool) {
            if (queued[pool][p] != 0) return;
            int nt = (qTail + 1) & 511; if (nt == qHead) return;
            qPool[qTail] = pool; qPage[qTail] = p; qTail = nt; queued[pool][p] = 1; qPool.notify();
        }
    }
    void urgent(int pool, int p) {
        synchronized (qPool) {
            if (queued[pool][p] == 2) return;
            int nt = (uTail + 1) & 255; if (nt == uHead) return;
            uPool[uTail] = pool; uPage[uTail] = p; uTail = nt; queued[pool][p] = 2; qPool.notify();
        }
    }
    final class Loader implements Runnable {
        public void run() {
            try { Thread.currentThread().setPriority(Thread.NORM_PRIORITY); } catch (Exception e) { }
            while (running) {
                int pool, p;
                synchronized (qPool) {
                    while (qHead == qTail && uHead == uTail && running) { try { qPool.wait(200); } catch (InterruptedException e) { } }
                    if (!running) return;
                    if (uHead != uTail) { pool = uPool[uHead]; p = uPage[uHead]; uHead = (uHead + 1) & 255; }
                    else { pool = qPool[qHead]; p = qPage[qHead]; qHead = (qHead + 1) & 511; }
                }
                if (pg[pool][p] == null) {
                    if (pgCnt[pool] >= cap[pool]) evict(pool);
                    long t0 = System.currentTimeMillis(); loaderBusyFrame = fr;
                    try { pg[pool][p] = Image.createImage(PRE[pool] + p + ".png"); pgCnt[pool]++; loads++; }
                    catch (Throwable e) { evict(pool); evict(pool); }
                    loadMsTotal += System.currentTimeMillis() - t0; loaderBusyFrame = fr;
                }
                queued[pool][p] = 0;
                Thread.yield();
            }
        }
    }
    void evict(int pool) {
        if (cap[pool] >= pg[pool].length) return;
        Image[] a = pg[pool]; int[] u = pgUse[pool]; int best = -1, bu = Integer.MAX_VALUE;
        for (int i = 0; i < a.length; i++) if (a[i] != null && u[i] < bu) { bu = u[i]; best = i; }
        if (best >= 0) { a[best] = null; pgCnt[pool]--; }
    }
    /** load point (game start): the whole ground pool and every prop image, so the frame never decodes those */
    public void preloadStatic() {
        if (cap[0] >= pg[0].length) for (int i = 0; i < pg[0].length; i++) need(POOL_U, i);
        for (int i = 0; i < pim.length; i++) propImg(i);
    }
    /** pages of the pools whose cap holds all of them (WI-Cache 999,999,999,...): decoded once at game start, never again */
    public int preloadTotal() { int n = 0; for (int p = 0; p < 3; p++) if (cap[p] >= pg[p].length) n += pg[p].length; return n; }
    /** false = out of memory: that pool goes back to streaming with a cap it can afford */
    public boolean preloadPage(int k) {
        for (int p = 0; p < 3; p++) {
            if (cap[p] < pg[p].length) continue;
            if (k < pg[p].length) {
                try { need(p, k); return true; }
                catch (OutOfMemoryError e) { cap[p] = Math.max(8, pgCnt[p] * 3 / 4); for (int i = 0; i < 400 && pgCnt[p] > cap[p]; i++) evict(p); System.gc(); return false; }
            }
            k -= pg[p].length;
        }
        return true;
    }
    /** 16 px cells a ped can walk through (>= 3 of the 4 collision cells free), bit per cell */
    public byte[] pass; public int PW16;
    public void buildPass() {
        PW16 = (W16 + 7) >> 3; pass = new byte[PW16 * H16];
        for (int Y = 0; Y < H16; Y++) for (int X = 0; X < W16; X++) {
            int f = 0, cx = X * 2, cy = Y * 2;
            if (!solidCell(cx, cy)) f++; if (!solidCell(cx + 1, cy)) f++; if (!solidCell(cx, cy + 1)) f++; if (!solidCell(cx + 1, cy + 1)) f++;
            if (f >= 3) pass[Y * PW16 + (X >> 3)] |= (byte) (0x80 >> (X & 7));
        }
    }
    public boolean pass16(int X, int Y) { return X >= 0 && Y >= 0 && X < W16 && Y < H16 && (pass[Y * PW16 + (X >> 3)] & (0x80 >> (X & 7))) != 0; }
    public void flush() {
        for (int p = 0; p < 3; p++) { for (int i = 0; i < pg[p].length; i++) pg[p][i] = null; pgCnt[p] = 0; }
        for (int i = 0; i < pim.length; i++) pim[i] = null; pimCount = 0; vx0 = -9999;
    }
    /** synchronous load of the view (load points only) */
    public void preload(int cx, int cy) {
        int cx0 = cx >> 4, cy0 = cy >> 4;
        for (int Y = cy0 - 1; Y < cy0 + BHt + 1; Y++) for (int X = cx0 - 1; X < cx0 + BWt + 1; X++) {
            if (X < 0 || Y < 0 || X >= W16 || Y >= H16) continue;
            int c = Y * W16 + X;
            need(POOL_U, (um[c] & 0x1FFF) >> 6);
            if (brm[c] != 0) need(POOL_O, ((brm[c] & 0x1FFF) - 1) >> 6);
            if (dem[c] != 0) need(POOL_O, ((dem[c] & 0x1FFF) - 1) >> 6);
            if (rlm[c] != 0) need(POOL_O, ((rlm[c] & 0x1FFF) - 1) >> 6);
            if (bm[c] != 0) need(POOL_B, ((bm[c] & 0xFFFF) - 1) >> 6);
        }
        vx0 = -9999;
    }
    void need(int pool, int p) {
        pgUse[pool][p] = fr;
        if (pg[pool][p] != null) return;
        if (pgCnt[pool] >= cap[pool]) evict(pool);
        try { pg[pool][p] = Image.createImage(PRE[pool] + p + ".png"); pgCnt[pool]++; loads++; } catch (IOException e) { }
    }
    void prefetch(int camX, int camY, int bx0, int by0, int bx1, int by1) {
        int cx0 = camX >> 4, cy0 = camY >> 4;
        int x0 = cx0 - bx0, x1 = cx0 + BWt + bx1, y0 = cy0 - by0, y1 = cy0 + BHt + by1;
        for (int Y = Math.max(0, y0); Y <= Math.min(H16 - 1, y1); Y++) for (int X = Math.max(0, x0); X <= Math.min(W16 - 1, x1); X++) {
            if (X >= vx0 && X < vx0 + BWt && Y >= vy0 && Y < vy0 + BHt) continue;
            int c = Y * W16 + X;
            want(POOL_U, (um[c] & 0x1FFF) >> 6);
            if (brm[c] != 0) want(POOL_O, ((brm[c] & 0x1FFF) - 1) >> 6);
            if (dem[c] != 0) want(POOL_O, ((dem[c] & 0x1FFF) - 1) >> 6);
            if (rlm[c] != 0) want(POOL_O, ((rlm[c] & 0x1FFF) - 1) >> 6);
            if (bm[c] != 0) want(POOL_B, ((bm[c] & 0xFFFF) - 1) >> 6);
        }
    }
    void want(int pool, int p) { pgUse[pool][p] = fr; if (pg[pool][p] == null) request(pool, p); }

    // ------------------------------------------------------------ back buffer
    boolean drawCell(int X, int Y) {
        int px = (X % BWt) * T, py = (Y % BHt) * T;
        if (X < 0 || Y < 0 || X >= W16 || Y >= H16) { bg.setColor(0); bg.fillRect(px, py, T, T); return true; }
        int c = Y * W16 + X; boolean ok = true;
        int v = um[c], id = v & 0x1FFF;
        Image im = page(POOL_U, id >> 6);
        if (im != null) bg.drawRegion(im, (id & 7) << 4, ((id >> 3) & 7) << 4, T, T, (v >> 13) & 7, px, py, TL);
        else { bg.setColor(0x505a48); bg.fillRect(px, py, T, T); ok = false; }
        ok &= ov(bg, brm[c], px, py); ok &= ov(bg, dem[c], px, py);
        int b = bm[c] & 0xFFFF;
        if (b != 0) { int bid = b - 1; Image bi = page(POOL_B, bid >> 6); if (bi != null) bg.drawRegion(bi, (bid & 7) << 4, ((bid >> 3) & 7) << 4, T, T, 0, px, py, TL); else ok = false; }
        ok &= ov(bg, rlm[c], px, py);
        return ok;
    }
    boolean ov(Graphics g, short v, int px, int py) {
        if (v == 0) return true;
        int id = (v & 0x1FFF) - 1; Image im = page(POOL_O, id >> 6);
        if (im == null) return false;
        g.drawRegion(im, (id & 7) << 4, ((id >> 3) & 7) << 4, T, T, (v >> 13) & 7, px, py, TL);
        return true;
    }
    void cell(int X, int Y) {
        if (drawCell(X, Y)) return;
        missesTotal++;
        if (nMiss < missX.length) { missX[nMiss] = X; missY[nMiss] = Y; nMiss++; } else missOver = true;
    }
    void column(int X, int y0) { for (int Y = y0; Y < y0 + BHt; Y++) cell(X, Y); }
    void row(int Y, int x0) { for (int X = x0; X < x0 + BWt; X++) cell(X, Y); }
    void scrollBuffer() {
        int cx0 = camX >> 4, cy0 = camY >> 4;
        if (Math.abs(cx0 - vx0) >= BWt || Math.abs(cy0 - vy0) >= BHt) {
            nMiss = 0; for (int Y = cy0; Y < cy0 + BHt; Y++) row(Y, cx0); vx0 = cx0; vy0 = cy0; return;
        }
        while (vx0 < cx0) { column(vx0 + BWt, vy0); vx0++; }
        while (vx0 > cx0) { vx0--; column(vx0, vy0); }
        while (vy0 < cy0) { row(vy0 + BHt, vx0); vy0++; }
        while (vy0 > cy0) { vy0--; row(vy0, vx0); }
        if (missOver && uHead == uTail) { missOver = false; nMiss = 0; for (int Y = vy0; Y < vy0 + BHt; Y++) row(Y, vx0); return; }
        if (nMiss > 0 && (fr & 1) == 0) {
            int k = 0;
            for (int i = 0; i < nMiss; i++) {
                int X = missX[i], Y = missY[i];
                if (X < vx0 || X >= vx0 + BWt || Y < vy0 || Y >= vy0 + BHt) continue;
                if (!drawCell(X, Y)) { missX[k] = X; missY[k] = Y; k++; }
            }
            nMiss = k;
        }
    }
    /** bring the buffer to the camera, prefetch, copy to the screen */
    public void draw(Graphics g, float vx, float vy, float aheadX, float aheadY) {
        fr++;
        scrollBuffer();
        // ring of 2 cells round the window, plus the window 1 s ahead of the motion (alternating frames)
        if ((fr & 3) == 0) prefetch(camX, camY, 2, 2, 2, 2);
        else if ((fr & 3) == 2 && (vx * vx + vy * vy) > 1600) {
            int ax = clampX((int) aheadX - SW / 2), ay = clampY((int) aheadY - SH / 2);
            prefetch(ax, ay, vx < 0 ? 3 : 0, vy < 0 ? 3 : 0, vx > 0 ? 3 : 0, vy > 0 ? 3 : 0);
            int mx = clampX((camX + ax) / 2), my = clampY((camY + ay) / 2);
            prefetch(mx, my, 0, 0, 0, 0);
        }
        int sx = camX % BW, sy = camY % BH;
        int w1 = Math.min(SW, BW - sx), h1 = Math.min(SH, BH - sy);
        piece(g, sx, sy, 0, 0, w1, h1);
        if (w1 < SW) piece(g, 0, sy, w1, 0, SW - w1, h1);
        if (h1 < SH) piece(g, sx, 0, 0, h1, w1, SH - h1);
        if (w1 < SW && h1 < SH) piece(g, 0, 0, w1, h1, SW - w1, SH - h1);
        g.setClip(0, 0, SW, SH);
    }
    void piece(Graphics g, int bx, int by, int dx, int dy, int w, int h) { g.setClip(dx, dy, w, h); g.drawImage(buf, dx - bx, dy - by, TL); }
    public int clampX(int v) { return Math.max(0, Math.min(PW - SW, v)); }
    public int clampY(int v) { return Math.max(0, Math.min(PH - SH, v)); }

    /** redraw, clipped to the sprite rect (map px): buildings whose base is below the foot, and the El rail */
    public void cover(Graphics g, int x0, int y0, int x1, int y1, int foot) {
        covered = false;
        x0 = Math.max(x0, 0); y0 = Math.max(y0, 0); x1 = Math.min(x1, PW); y1 = Math.min(y1, PH);
        if (x0 >= x1 || y0 >= y1) return;
        boolean clip = false;
        for (int Y = y0 >> 4; Y <= (y1 - 1) >> 4; Y++) for (int X = x0 >> 4; X <= (x1 - 1) >> 4; X++) {
            int c = Y * W16 + X;
            boolean bld = bm[c] != 0 && bb[c] > foot, rail = rlm[c] != 0;
            if (!bld && !rail) continue;
            if (!clip) { g.setClip(x0 - camX, y0 - camY, x1 - x0, y1 - y0); clip = true; }
            int px = (X << 4) - camX, py = (Y << 4) - camY;
            if (bld) {
                int bid = (bm[c] & 0xFFFF) - 1; Image bi = page(POOL_B, bid >> 6);
                if (bi != null) { g.drawRegion(bi, (bid & 7) << 4, ((bid >> 3) & 7) << 4, T, T, 0, px, py, TL); covered = true; }
            }
            if (rail && ov(g, rlm[c], px, py)) covered = true;
        }
        if (clip) g.setClip(0, 0, SW, SH);
    }
    public Image propImg(int i) {
        if (pim[i] == null) {
            if (pimCount >= PIMAX) {
                int best = -1, bu = Integer.MAX_VALUE;
                for (int k = 0; k < pim.length; k++) if (pim[k] != null && pimUse[k] < bu) { bu = pimUse[k]; best = k; }
                if (best >= 0) { pim[best] = null; pimCount--; }
            }
            try { pim[i] = Image.createImage("/c/p" + i + ".png"); pimCount++; loads++; } catch (IOException e) { return null; }
        }
        pimUse[i] = fr; return pim[i];
    }
    public void stop() { running = false; synchronized (qPool) { qPool.notify(); } }
}
