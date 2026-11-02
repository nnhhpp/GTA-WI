import java.io.*;
import java.util.*;
import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.*;
import javax.microedition.media.*;
import javax.microedition.media.control.VolumeControl;
import javax.microedition.rms.*;

/** "What if 8-Ball drove?" - GTA III chunk 01 cop-survival. Map 12 px/m, sprites 15-16 px/m, screen 1:1. */
public final class Game extends GameCanvas implements Runnable, PlayerListener {
    static final float M = MapR.M;
    static final int TL = Graphics.TOP | Graphics.LEFT;
    static final int ST_LOAD = -1, ST_MENU = 0, ST_CTRL = 1, ST_INTRO = 2, ST_PLAY = 3, ST_OVER = 4, ST_SND = 5;
    static final int K_UP = 1, K_DOWN = 2, K_LEFT = 4, K_RIGHT = 8, K_5 = 16, K_0 = 32, K_STAR = 64, K_POUND = 128, K_SOFT = 256;
    // weapons
    static final int W_FIST = 0, W_PISTOL = 1, W_UZI = 2, W_SHOTGUN = 3, W_AK = 4, W_M16 = 5, NW = 6;
    static final String[] WN = {"fist", "pistol", "uzi", "shotgun", "ak47", "m16"};
    static final float[] W_RATE = {0.42f, 0.34f, 0.11f, 0.95f, 0.15f, 0.12f};      // s between shots
    static final int[] W_DMG = {22, 55, 24, 90, 40, 45};
    static final float[] W_RANGE = {1.4f, 22, 18, 11, 30, 32};                      // m
    static final boolean[] W_AUTO = {false, false, true, false, true, true};
    static final int[] W_CLIP = {0, 24, 90, 16, 60, 90};                             // ammo per pickup / run-over
    // wanted levels 2..6: quota, min time s, max cops alive, cop ped, car, cop weapon, crew, spawn interval s
    static final int[] WV_QUOTA = {0, 0, 6, 10, 12, 14, 0};
    static final int[] WV_TIME = {0, 0, 45, 60, 60, 75, 0};
    static final int[] WV_ALIVE = {0, 0, 6, 7, 8, 9, 10};
    static final int[] WV_CARS = {0, 0, 2, 2, 3, 3, 3};     // cop cars at once; the rest of the pressure comes on foot
    static final String[] WV_PED = {"", "", "cop", "cop", "swat", "fbi", "army"};
    static final String[] WV_CAR = {"", "", "police", "police", "enforcer", "fbicar", "barracks"};
    static final int[] WV_WEAP = {0, 0, W_PISTOL, W_SHOTGUN, W_UZI, W_AK, W_M16};     // wave 1 pistols, 2 shotguns, 3 uzis, 4 AKs, 5+ M16s
    static final int[] WV_CREW = {0, 0, 3, 3, 4, 4, 4};
    static final float[] WV_EVERY = {0, 0, 8, 7, 6, 6, 5};
    static final float[] WV_COPDMG = {0, 0, 4, 11, 4, 6, 7};  // per hit on the player (the shotgun hits hard but only up close)
    static final float[] WV_ACC = {0, 0, 0.30f, 0.36f, 0.36f, 0.42f, 0.48f};

    final WhatIf midlet;
    boolean FPS, GOD, sound = true; String lastErr; int dbgWave; int[] dbgAt;
    int W, H;
    volatile boolean running = true, paused;
    int keys, prevKeys;
    Random rnd = new Random();
    MapR map;

    // assets
    String[] carName; Image[] carImg; int[] carCW, carCH; float[] carWm, carLm; short[][] carAnc;
    String[] pedName; Image[] pedWalk, pedStand, pedDead; int[] pwW, pwH, pwA, pwY, pwN, psW, psH, psA, pdW, pdH, pdA;
    Image atkPunch, atkGun; int punchW, punchH, punchAX, punchN, gunW, gunH, gunAX, gunN;
    Image fireImg, smokeImg, boomImg; int fireW, fireH, fireN, smokeW, smokeH, smokeN, boomW, boomH, boomN;
    Image lightImg, hShadow, hRotor, heliImg; int lightW, lightH, hsW, rotW, heliW, heliH, heliAX, heliAY;
    boolean heliOn; float hx, hy, hvx, hvy, ha;                 // hx,hy = where the searchlight hits the ground
    Image[] carBurnt; float[] exX = new float[4], exY = new float[4], exT = new float[4]; int exHead;
    Image wastedImg, hStarOff; Image portClaude, portEight, radar, hRing, hNorth, hArrow, hStar, fHealth, bWeapon, bCop, menuImg, logoImg;
    Image[] wIcon = new Image[NW], kIcon = new Image[NW];
    int[] healthW, healthX;
    Font fSmall, fBold, fBig; BFont fs, fb, cf, fh;
    static final int RD = 60;
    Hashtable text = new Hashtable();
    int CAR_KURUMA, CAR_POLICE, PED_CLAUDE, PED_EIGHT, PED_TRIAD;
    int[] civTypes, trafficTypes, civAll, trafficAll;
    static Class CL;

    // actors
    Ped claude = new Ped();
    Ped[] peds = new Ped[26];
    Car[] traffic = new Car[4], copCars = new Car[6], parked = new Car[36];
    Car car;                                  // the car Claude drives
    Car wreck;                                // the Kuruma with 8-Ball, after the crash
    float[] fireX, fireY; int[] fireT; int nFire;
    float[] deadX = new float[16], deadY = new float[16]; int[] deadT = new int[16], deadF = new int[16]; int deadN;
    // pickups
    int[] pkX, pkY, pkW; float[] pkT; int nPk;
    // spawns
    int[] spX, spY; int nSp; int[] rbR; int nRb; float rbT;
    // player
    float health; int weapon; int[] ammo = new int[NW]; boolean[] have = new boolean[NW];
    float shotT, atkT, flashT; float aimX, aimY; boolean aimHit; Ped aimP;
    float aimShowT; Ped aimShowP; Car aimShowC;                       // the green [ ] on what Claude shoots at
    Car jackCar; int jackSrc, jackIx; float jackT, jackDur;            // Gangstar-style carjack progress
    static final int NSM = 24; float[] mX = new float[NSM], mY = new float[NSM], mL = new float[NSM]; int mHead; float smokeAcc;   // tyre smoke
    static final int NSPK = 40; float[] kX = new float[NSPK], kY = new float[NSPK], kVX = new float[NSPK], kVY = new float[NSPK], kL = new float[NSPK]; int kHead;
    int stars, kills, waveKills; float waveT, spawnT, survT, overT; boolean overtime;
    float shakeT; String popup; float popupT;
    // intro
    int phase; float phT; float[] ipX, ipY; float ipS; float[] ipCum; int crashPole = -1;
    // state
    int state = ST_LOAD; float stT, animT; int menuSel; float best; boolean newBest;
    float camX, camY; String help, helpNext; float helpT;
    String[] dlgText; Image[] dlgPort; String[] dlgName; int dlgIdx; float dlgChars;
    Player music; boolean radioOn; volatile boolean radioNext;
    int fps, frames; long fpsT; int frameMs, frameMax, fmShow;
    long tA, tB, acU, acM, acS, acH, acF, acW; int lastLoads, lastMiss; String prof = "", fpsS = "";
    int loadPct, sndSel; String sndErr;
    float[] pt = new float[2];
    int[] tmpI = new int[24];
    // flow field around the player (64x64 cells of 16 px)
    static final int FF = 96; short[] ffD = new short[FF * FF]; int[] ffQ = new int[FF * FF]; int ffX0, ffY0; float ffT;
    // cop car routing (Dijkstra on the car graph towards the player)
    float[] rD; boolean[] rDone; float routeT; int routeTarget = -1;

    public Game(WhatIf m) {
        super(false); midlet = m; CL = getClass(); setFullScreenMode(true);
        try { FPS = "1".equals(m.getAppProperty("WI-Fps")); GOD = "1".equals(m.getAppProperty("WI-God")); String w = m.getAppProperty("WI-Stars"); if (w != null) dbgWave = Integer.parseInt(w.trim()); w = m.getAppProperty("WI-At"); if (w != null && w.trim().length() > 0) dbgAt = nums(w); } catch (Throwable t) { }
        for (int i = 0; i < peds.length; i++) peds[i] = new Ped();
    }

    // ------------------------------------------------------------------ helpers
    static int[] nums(String s) {
        int cnt = 0, i = 0, n = s.length(); int[] tmp = new int[256];
        while (i < n) {
            while (i < n && (s.charAt(i) < '0' || s.charAt(i) > '9') && s.charAt(i) != '-') i++;
            if (i >= n) break;
            int j = i; if (s.charAt(j) == '-') j++;
            while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') j++;
            if (j > i + (s.charAt(i) == '-' ? 1 : 0)) tmp[cnt++] = Integer.parseInt(s.substring(i, j));
            i = j;
        }
        int[] r = new int[cnt]; System.arraycopy(tmp, 0, r, 0, cnt); return r;
    }
    static String readText(String p) throws IOException {
        InputStream in = CL.getResourceAsStream(p); ByteArrayOutputStream bo = new ByteArrayOutputStream(); int c;
        byte[] b = new byte[512];
        while ((c = in.read(b)) > 0) bo.write(b, 0, c);
        in.close(); byte[] a = bo.toByteArray(); char[] ch = new char[a.length];
        for (int i = 0; i < a.length; i++) ch[i] = (char) (a[i] & 255);
        return new String(ch);
    }
    static String[] split(String s) {
        Vector v = new Vector(); int p = 0;
        while (p < s.length()) { int e = s.indexOf(' ', p); if (e < 0) e = s.length(); if (e > p) v.addElement(s.substring(p, e)); p = e + 1; }
        String[] r = new String[v.size()]; v.copyInto(r); return r;
    }
    String txt(String k) { String s = (String) text.get(k); return s == null ? k : s; }
    float rf() { return rnd.nextFloat(); }
    int idx(String[] a, String n) { for (int i = 0; i < a.length; i++) if (a[i].equals(n)) return i; return 0; }

    // ------------------------------------------------------------------ init
    void init() throws IOException {
        W = getWidth(); H = getHeight();
        { Graphics g0 = getGraphics(); g0.setColor(0); g0.fillRect(0, 0, W, H); flushGraphics(); }
        fSmall = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fBold = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
        fBig = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);
        fs = new BFont(fBold); fb = new BFont(fBig); cf = fs;
        fh = new BFont(Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM));   // top HUD (v0.7.1: a bit bigger)
        state = ST_LOAD; loadStep(3);
        String t = readText("/t"); int p = 0;
        while (p < t.length()) {
            int e = t.indexOf('\n', p); if (e < 0) e = t.length();
            String l = t.substring(p, e); p = e + 1; int q = l.indexOf('=');
            if (q > 0) { String v = l.substring(q + 1).trim(); if (v.startsWith("'") && v.endsWith("'") && v.length() > 1) v = v.substring(1, v.length() - 1); text.put(l.substring(0, q).trim(), v); }
        }
        loadStep(15);
        String meta = readText("/s/meta");
        Vector cars = new Vector(), pw = new Vector(), ps = new Vector(), pd = new Vector();
        p = 0;
        while (p < meta.length()) {
            int e = meta.indexOf('\n', p); if (e < 0) e = meta.length();
            String line = meta.substring(p, e).trim(); p = e + 1;
            if (line.length() == 0) continue;
            String[] a = split(line);
            if (a[0].equals("car")) cars.addElement(a);
            else if (a[0].equals("pedw")) pw.addElement(a); else if (a[0].equals("peds")) ps.addElement(a); else if (a[0].equals("pedd")) pd.addElement(a);
            else if (a[0].equals("atk") && a[1].equals("punch")) { atkPunch = Image.createImage("/s/a_punch.png"); punchW = Integer.parseInt(a[2]); punchH = Integer.parseInt(a[3]); punchAX = Integer.parseInt(a[4]); punchN = Integer.parseInt(a[5]); }
            else if (a[0].equals("atk") && a[1].equals("gun")) { atkGun = Image.createImage("/s/a_gun.png"); gunW = Integer.parseInt(a[2]); gunH = Integer.parseInt(a[3]); gunAX = Integer.parseInt(a[4]); gunN = Integer.parseInt(a[5]); }
        }
        int nc = cars.size();
        carName = new String[nc]; carImg = new Image[nc]; carBurnt = new Image[nc]; carCW = new int[nc]; carCH = new int[nc]; carWm = new float[nc]; carLm = new float[nc]; carAnc = new short[nc][];
        for (int i = 0; i < nc; i++) {
            String[] a = (String[]) cars.elementAt(i);
            carName[i] = a[1];
            carCW[i] = Integer.parseInt(a[2]); carCH[i] = Integer.parseInt(a[3]);
            carWm[i] = Float.parseFloat(a[4]); carLm[i] = Float.parseFloat(a[5]);
            carAnc[i] = new short[a.length - 6];
            for (int j = 0; j < carAnc[i].length; j++) carAnc[i][j] = Short.parseShort(a[6 + j]);
        }
        int np = pw.size();
        pedName = new String[np]; pedWalk = new Image[np]; pedStand = new Image[np]; pedDead = new Image[np];
        pwW = new int[np]; pwH = new int[np]; pwA = new int[np]; pwY = new int[np]; pwN = new int[np]; psW = new int[np]; psH = new int[np]; psA = new int[np]; pdW = new int[np]; pdH = new int[np]; pdA = new int[np];
        for (int i = 0; i < np; i++) {
            String[] a = (String[]) pw.elementAt(i);
            pedName[i] = a[1];
            pwW[i] = Integer.parseInt(a[2]); pwH[i] = Integer.parseInt(a[3]); pwA[i] = Integer.parseInt(a[4]);
            pwY[i] = a.length > 5 ? Integer.parseInt(a[5]) : pwH[i] - 1;      // ground row in the cell
            pwN[i] = a.length > 6 ? Integer.parseInt(a[6]) : 4;              // frames per direction (run cycles: 8)
            for (int j = 0; j < ps.size(); j++) { String[] b = (String[]) ps.elementAt(j); if (b[1].equals(a[1])) { psW[i] = Integer.parseInt(b[2]); psH[i] = Integer.parseInt(b[3]); psA[i] = Integer.parseInt(b[4]); } }
            for (int j = 0; j < pd.size(); j++) { String[] c = (String[]) pd.elementAt(j); if (c[1].equals(a[1])) { pdW[i] = Integer.parseInt(c[2]); pdH[i] = Integer.parseInt(c[3]); pdA[i] = Integer.parseInt(c[4]); } }
        }
        CAR_KURUMA = idx(carName, "kuruma"); CAR_POLICE = idx(carName, "police");
        PED_CLAUDE = idx(pedName, "claude"); PED_EIGHT = idx(pedName, "eight"); PED_TRIAD = idx(pedName, "gang03");
        PED_MISTY = -1; for (int i = 0; i < np; i++) if (pedName[i].equals("misty")) PED_MISTY = i;
        int[] tmp = new int[np]; int k = 0;
        for (int i = 0; i < np; i++) {
            String n = pedName[i];
            if (!(n.equals("claude") || n.equals("eight") || n.equals("cop") || n.equals("swat") || n.equals("fbi") || n.equals("army") || n.equals("gang03") || n.equals("misty"))) tmp[k++] = i;
        }
        civAll = new int[k]; System.arraycopy(tmp, 0, civAll, 0, k);
        tmp = new int[nc]; k = 0;
        for (int i = 0; i < nc; i++) {
            String n = carName[i];
            if (!(n.equals("police") || n.equals("enforcer") || n.equals("fbicar") || n.equals("barracks") || n.equals("kuruma"))) tmp[k++] = i;
        }
        trafficAll = new int[k]; System.arraycopy(tmp, 0, trafficAll, 0, k);
        civTypes = new int[Math.min(4, civAll.length)]; trafficTypes = new int[Math.min(4, trafficAll.length)];
        int[] fx = nums(readText("/s/fx"));
        fireW = fx[0]; fireH = fx[1]; fireN = fx[2]; smokeW = fx[3]; smokeH = fx[4]; smokeN = fx[5];
        fireImg = Image.createImage("/s/fire.png"); smokeImg = Image.createImage("/s/smoke.png");
        if (fx.length >= 9) { boomW = fx[6]; boomH = fx[7]; boomN = fx[8]; boomImg = Image.createImage("/s/boom.png"); }
        if (fx.length >= 14) {
            lightW = fx[9]; lightH = fx[10]; hsW = fx[11]; rotW = fx[12];
            lightImg = Image.createImage("/s/light.png"); hShadow = Image.createImage("/s/hshadow.png"); hRotor = Image.createImage("/s/hrotor.png");
            if (fx[13] == 1 && fx.length >= 18) { heliW = fx[14]; heliH = fx[15]; heliAX = fx[16]; heliAY = fx[17]; heliImg = Image.createImage("/s/heli.png"); }
        }
        portClaude = Image.createImage("/p/claude.png"); portEight = Image.createImage("/p/eight.png");
        radar = Image.createImage("/radar.png");
        hRing = Image.createImage("/h/ring.png"); hNorth = Image.createImage("/h/north.png"); hArrow = Image.createImage("/h/arrow.png");
        hStar = Image.createImage("/h/star.png"); hStarOff = greyed(hStar); fHealth = Image.createImage("/h/health.png");
        bWeapon = Image.createImage("/h/weapon.png"); bCop = Image.createImage("/h/copcar.png");
        for (int i = 0; i < NW; i++) { wIcon[i] = Image.createImage("/h/w_" + WN[i] + ".png"); kIcon[i] = Image.createImage("/h/k_" + WN[i] + ".png"); }
        String hm = readText("/h/meta"); p = 0;
        while (p < hm.length()) {
            int e = hm.indexOf('\n', p); if (e < 0) e = hm.length();
            String[] a = split(hm.substring(p, e).trim()); p = e + 1;
            if (a.length < 2 || !a[0].equals("health")) continue;
            healthW = new int[a.length - 1]; healthX = new int[a.length - 1];
            for (int i = 0, acc = 0; i < healthW.length; i++) { healthW[i] = Integer.parseInt(a[i + 1]); healthX[i] = acc; acc += healthW[i]; }
        }
        prepRadar(); prepDigits(); initSfx(); loadStep(40);
        String cache = null; try { cache = midlet.getAppProperty("WI-Cache"); } catch (Throwable e) { }
        map = new MapR(W, H, cache); loadStep(75); map.buildPass(); loadStep(90);
        Vector sp = new Vector(), wp = new Vector(), fv = new Vector(), rb = new Vector();
        for (int i = 0; i < map.obj.size(); i++) {
            String[] a = (String[]) map.obj.elementAt(i);
            if (a[0].equals("spawn")) sp.addElement(a); else if (a[0].equals("weapon")) wp.addElement(a); else if (a[0].equals("fire")) fv.addElement(a); else if (a[0].equals("rb")) rb.addElement(a);
        }
        for (int i = 0; i < map.obj.size(); i++) { String[] a = (String[]) map.obj.elementAt(i); if (a[0].equals("misty")) { mistyX = Integer.parseInt(a[1]); mistyY = Integer.parseInt(a[2]); } }   // a 'misty' point in Tiled's mission layer moves her
        nRb = rb.size(); rbR = new int[nRb * 4];
        for (int i = 0; i < nRb; i++) { String[] a = (String[]) rb.elementAt(i); for (int q = 0; q < 4; q++) rbR[i * 4 + q] = Integer.parseInt(a[1 + q]); }
        nSp = sp.size(); spX = new int[nSp]; spY = new int[nSp];
        for (int i = 0; i < nSp; i++) { String[] a = (String[]) sp.elementAt(i); spX[i] = Integer.parseInt(a[1]); spY[i] = Integer.parseInt(a[2]); }
        nPk = wp.size(); pkX = new int[nPk]; pkY = new int[nPk]; pkW = new int[nPk]; pkT = new float[nPk];
        for (int i = 0; i < nPk; i++) { String[] a = (String[]) wp.elementAt(i); pkX[i] = Integer.parseInt(a[1]); pkY[i] = Integer.parseInt(a[2]); }
        nFire = fv.size(); fireX = new float[nFire]; fireY = new float[nFire]; fireT = new int[nFire];
        for (int i = 0; i < nFire; i++) { String[] a = (String[]) fv.elementAt(i); fireX[i] = Integer.parseInt(a[1]); fireY[i] = Integer.parseInt(a[2]); fireT[i] = Integer.parseInt(a[3]); }
        rD = new float[map.px[0].length]; rDone = new boolean[rD.length];
        best = loadBest();
        logoImg = Image.createImage("/u/logo.png"); try { wastedImg = Image.createImage("/u/wasted.png"); } catch (IOException e) { } loadStep(100);
        state = ST_SND; sndSel = 0;
    }
    void loadStep(int pct) { loadPct = pct; present(); }
    void present() { Graphics g = getGraphics(); render(g); flushGraphics(); }
    void drawErr(Graphics g) {
        if (lastErr != null && fSmall != null) { g.setClip(0, 0, W, H); g.setFont(fSmall); g.setColor(0x800000); g.fillRect(0, H / 2, W, fSmall.getHeight() + 2); g.setColor(0xffffff); g.drawString(lastErr, 2, H / 2 + 1, TL); }
    }
    float[] objXY(String n) {
        for (int i = 0; i < map.obj.size(); i++) { String[] a = (String[]) map.obj.elementAt(i); if (a[0].equals(n)) return new float[]{Integer.parseInt(a[1]), Integer.parseInt(a[2])}; }
        return new float[]{map.PW / 2, map.PH / 2};
    }
    Car newCar(int type) { needCar(type); return new Car(type, carWm[type], carLm[type]); }
    Image burnt(int t) { if (carBurnt[t] == null) try { carBurnt[t] = Image.createImage("/s/c_" + carName[t] + "_b.png"); } catch (Throwable e) { } return carBurnt[t]; }
    void needCar(int t) { if (carImg[t] == null) try { carImg[t] = Image.createImage("/s/c_" + carName[t] + ".png"); } catch (IOException e) { } }
    void needPed(int t) {
        if (pedWalk[t] != null) return;
        try { String n = pedName[t]; pedWalk[t] = Image.createImage("/s/p_" + n + "_w.png"); pedStand[t] = Image.createImage("/s/p_" + n + "_s.png"); pedDead[t] = Image.createImage("/s/p_" + n + "_d.png"); }
        catch (IOException e) { }
    }
    /** load point: pick this run's civilians + traffic, drop the sprites nobody uses */
    void pickTypes() {
        for (int i = 0; i < carImg.length; i++) carImg[i] = null;
        for (int i = 0; i < pedWalk.length; i++) { pedWalk[i] = pedStand[i] = pedDead[i] = null; }
        for (int i = 0; i < civTypes.length; i++) civTypes[i] = civAll[(rnd.nextInt(civAll.length) + i * 3) % civAll.length];
        for (int i = 0; i < trafficTypes.length; i++) trafficTypes[i] = trafficAll[(rnd.nextInt(trafficAll.length) + i * 3) % trafficAll.length];
        System.gc();
        for (int i = 0; i < civTypes.length; i++) needPed(civTypes[i]);
        for (int i = 0; i < trafficTypes.length; i++) needCar(trafficTypes[i]);
        needPed(PED_CLAUDE); needPed(PED_EIGHT); needPed(PED_TRIAD); needPed(idx(pedName, "cop"));
        needCar(CAR_KURUMA); needCar(CAR_POLICE);
    }

    // ------------------------------------------------------------------ best time (RMS)
    float loadBest() {
        try { RecordStore rs = RecordStore.openRecordStore("wi8", true); float v = 0;
            if (rs.getNumRecords() > 0) { byte[] b = rs.getRecord(1); v = ((b[0] & 255) << 8 | (b[1] & 255)); }
            rs.closeRecordStore(); return v; } catch (Throwable t) { return 0; }
    }
    void saveBest(float v) {
        try { RecordStore rs = RecordStore.openRecordStore("wi8", true); int s = (int) v; byte[] b = {(byte) (s >> 8), (byte) s};
            if (rs.getNumRecords() > 0) rs.setRecord(1, b, 0, 2); else rs.addRecord(b, 0, 2); rs.closeRecordStore(); } catch (Throwable t) { }
    }

    // ------------------------------------------------------------------ new run
    void reset() {
        for (int i = 0; i < peds.length; i++) peds[i].active = false;
        pickTypes();
        for (int i = 0; i < traffic.length; i++) traffic[i] = null;
        for (int i = 0; i < copCars.length; i++) copCars[i] = null;
        for (int i = 0; i < parked.length; i++) parked[i] = null;
        for (int i = 0; i < map.nProps; i++) map.pDown[i] = false;
        deadN = 0; car = null; wreck = null;
        float[] c = objXY("car");
        Car k = newCar(CAR_KURUMA); k.ai = false; k.x = c[0]; k.y = c[1]; k.a = 0.13f; k.locked = true;
        parked[0] = k;
        // the map exits: police cars parked across the road (Tiled 'spawns' layer, type roadblock)
        int pi = 1, pol = idx(carName, "police");
        for (int r = 0; r < nRb && pi < parked.length - 8; r++) {
            int x = rbR[r * 4], y = rbR[r * 4 + 1], w = rbR[r * 4 + 2], h = rbR[r * 4 + 3]; boolean vert = h >= w;
            int len = vert ? h : w, n = Math.max(1, (len + 20) / 80);
            for (int q = 0; q < n && pi < parked.length - 8; q++) {
                Car b = newCar(pol); b.ai = false; b.locked = true; b.siren = true; b.cop = true;
                float t = (q + 0.5f) * len / n; b.x = vert ? x + w / 2 : x + t; b.y = vert ? y + t : y + h / 2;
                b.a = (vert ? 0 : G.PI * 0.5f) + ((q & 1) == 0 ? 0 : G.PI); parked[pi++] = b;
            }
        }
        float[] p = objXY("claude");
        claude.x = p[0]; claude.y = p[1]; claude.type = PED_CLAUDE; claude.role = Ped.PLAYER; claude.active = true; claude.moving = false;
        Ped e = peds[0]; p = objXY("eight");
        e.x = p[0]; e.y = p[1]; e.type = PED_EIGHT; e.role = Ped.EIGHT; e.active = true; e.state = Ped.IDLE; e.moving = false; e.health = 100;
        claude.a = G.heading(e.x - claude.x, e.y - claude.y); e.a = G.heading(claude.x - e.x, claude.y - e.y);
        health = 100; weapon = W_PISTOL;
        for (int i = 0; i < NW; i++) { ammo[i] = 0; have[i] = false; }
        have[W_FIST] = true; have[W_PISTOL] = true; ammo[W_PISTOL] = 24;
        stars = 0; kills = 0; waveKills = 0; waveT = 0; spawnT = 0; survT = 0; overtime = false; overT = 0;
        for (int i = 0; i < nPk; i++) { pkW[i] = -1; pkT[i] = 0; }
        atkT = 0; shotT = 0; flashT = 0; shakeT = 0; popup = null; help = helpNext = null; dlgText = null;
        jackCar = null; aimShowT = 0; aimShowP = null; aimShowC = null; heliOn = false; rbT = 4; mistyP = null; mistyDead = false; mistyTalks = 0; mistyTalkT = 0; for (int i = 0; i < 4; i++) exT[i] = 0; for (int i = 0; i < NSPK; i++) kL[i] = 0; for (int i = 0; i < NSM; i++) mL[i] = 0;
        camX = claude.x; camY = claude.y - 40;
        // crash target: the traffic light at the top of the ramp (nearest pole to the ramp exit)
        crashPole = -1; float bd = 1e9f;
        for (int i = 0; i < map.nProps; i++) if (map.pR[i] > 0) {
            float dx = map.pFx[i] - 5590, dy = map.pFoot[i] - 1480, d = dx * dx + dy * dy;
            if (d < bd) { bd = d; crashPole = i; }
        }
        float cx = crashPole >= 0 ? map.pFx[crashPole] : 5590, cy = crashPole >= 0 ? map.pFoot[crashPole] + 6 : 1480;
        ipX = new float[]{k.x, k.x + 30, 5530, 5545, cx - 10, cx};
        ipY = new float[]{k.y, k.y - 500, 2000, 1700, cy + 60, cy};
        ipCum = cum(ipX, ipY); ipS = 0;
        map.camX = map.clampX((int) camX - W / 2); map.camY = map.clampY((int) camY - H / 2);
        map.preload(map.camX, map.camY); System.gc();
    }
    static float[] cum(float[] x, float[] y) {
        float[] c = new float[x.length]; for (int i = 1; i < x.length; i++) { float dx = x[i] - x[i - 1], dy = y[i] - y[i - 1]; c[i] = c[i - 1] + (float) Math.sqrt(dx * dx + dy * dy); } return c;
    }
    void at(float[] x, float[] y, float[] c, float s) {
        int i = 0; while (i < c.length - 2 && c[i + 1] < s) i++;
        float seg = c[i + 1] - c[i], t = seg > 0.01f ? (s - c[i]) / seg : 0; if (t > 1) t = 1; if (t < 0) t = 0;
        pt[0] = x[i] + (x[i + 1] - x[i]) * t; pt[1] = y[i] + (y[i + 1] - y[i]) * t;
    }
    void startGame() {
        stopMusic(); menuImg = null; loading = true; loadPct = 0; present(); System.gc();
        map.preloadStatic();
        int n = map.preloadTotal();
        for (int k = 0; k < n; k++) { if (!map.preloadPage(k)) break; if ((k & 7) == 0) { loadPct = k * 100 / n; present(); } }
        loading = false;
        reset();
        setState(ST_INTRO); phase = 0; phT = 0;
        say(new String[]{"EBAL_A", "EBAL_A1", "WI_8A"}, new boolean[]{true, true, true});
    }
    void setState(int s) { state = s; stT = 0; }
    void toMenu() {
        stopMusic();
        try { if (menuImg == null) menuImg = Image.createImage("/u/menu.png"); } catch (IOException e) { }
        setState(ST_MENU); menuSel = 0; playMusic("/a/theme.mid", true);
    }

    // ------------------------------------------------------------------ input
    int mapKey(int code) {
        switch (code) {
            case KEY_NUM2: return K_UP; case KEY_NUM8: return K_DOWN; case KEY_NUM4: return K_LEFT; case KEY_NUM6: return K_RIGHT;
            case KEY_NUM5: return K_5; case KEY_NUM0: return K_0;
            case KEY_NUM1: return K_UP | K_LEFT; case KEY_NUM3: return K_UP | K_RIGHT; case KEY_NUM7: return K_DOWN | K_LEFT; case KEY_NUM9: return K_DOWN | K_RIGHT;
            case KEY_POUND: return K_POUND; case KEY_STAR: return K_STAR;
            case -6: case -7: case 21: case 22: return K_SOFT;
        }
        int ga = 0; try { ga = getGameAction(code); } catch (Exception e) { }
        switch (ga) { case UP: return K_UP; case DOWN: return K_DOWN; case LEFT: return K_LEFT; case RIGHT: return K_RIGHT; case FIRE: return K_5; }
        return 0;
    }
    public void keyPressed(int c) { keys |= mapKey(c); }
    public void keyReleased(int c) { keys &= ~mapKey(c); }
    public void hideNotify() { if (state == ST_PLAY || state == ST_INTRO) paused = true; keys = 0; }

    // ------------------------------------------------------------------ loop
    public void run() {
        Graphics g = getGraphics();
        try { init(); } catch (Throwable e) {
            g.setColor(0); g.fillRect(0, 0, getWidth(), getHeight()); g.setColor(0xff4040); g.drawString("Init error: " + e, 2, 2, 0); flushGraphics();
            try { Thread.sleep(8000); } catch (Exception x) { } midlet.quit(); return;
        }
        long last = System.currentTimeMillis(); fpsT = last; int mx = 0;
        while (running) {
            long now = System.currentTimeMillis();
            float dt = (now - last) / 1000f; last = now; if (dt > 0.1f) dt = 0.1f;
            int ks = keys;
            try {
                int st = getKeyStates();
                if ((st & UP_PRESSED) != 0) ks |= K_UP; if ((st & DOWN_PRESSED) != 0) ks |= K_DOWN;
                if ((st & LEFT_PRESSED) != 0) ks |= K_LEFT; if ((st & RIGHT_PRESSED) != 0) ks |= K_RIGHT;
                if ((st & FIRE_PRESSED) != 0) ks |= K_5;
            } catch (Exception e) { }
            int pr = ks & ~prevKeys; prevKeys = ks;
            if ((pr & K_SOFT) != 0 && (state == ST_PLAY || state == ST_INTRO)) paused = !paused;
            if (radioNext) { radioNext = false; if (radioOn) startRadio(); }
            if (paused) {
                if ((pr & K_5) != 0) paused = false;
                if ((pr & K_STAR) != 0) { sound = !sound; if (!sound) stopMusic(); else if (car != null) startRadio(); }
                if ((pr & K_POUND) != 0) { paused = false; toMenu(); }
            } else {
                tA = System.currentTimeMillis();
                try { update(dt, ks, pr); } catch (OutOfMemoryError e) { map.flush(); System.gc(); } catch (Throwable e) { lastErr = "U " + e; }
            }
            tB = System.currentTimeMillis(); acU += tB - tA; tA = tB;
            try { render(g); } catch (OutOfMemoryError e) { map.flush(); System.gc(); } catch (Throwable e) { lastErr = "R " + e; }
            drawErr(g);
            long f0 = System.currentTimeMillis(); flushGraphics(); long f1 = System.currentTimeMillis(); acF += f1 - f0;
            frames++;
            int spent = (int) (f1 - now); frameMs = spent; if (spent > mx) mx = spent;
            if (now - fpsT >= 1000) { fps = frames; fmShow = mx; mx = 0;
                if (FPS) { Runtime rt = Runtime.getRuntime(); int f = Math.max(1, frames);
                    prof = "u" + acU / f + " m" + acM / f + " s" + acS / f + " h" + acH / f + " f" + acF / f + " w" + acW / f + " L" + (map.loads - lastLoads) + " miss" + (map.missesTotal - lastMiss) + " " + (rt.totalMemory() - rt.freeMemory()) / 1024 + "/" + rt.totalMemory() / 1024 + "K";
                    fpsS = fps + "fps max" + fmShow + "ms  v" + midlet.getAppProperty("MIDlet-Version"); lastLoads = map.loads; lastMiss = map.missesTotal; }
                acU = acM = acS = acH = acF = acW = 0; frames = 0; fpsT = now;
                if (FPS) System.out.println("STAT fps " + fps + " max " + fmShow + " st " + state + " p " + (int) focusX() + "," + (int) focusY() + " stars " + stars + " kills " + kills + " cops " + copsAlive() + " cc " + nCopCars() + " near " + copsNear() + " d " + (int) nearestCop() + " wt " + (int) waveT + " heli " + (heliOn ? (int) hx + "," + (int) hy : "-") + " rb " + (int) rbT + " hp " + (int) health + " loads " + map.loads + " miss " + map.missesTotal + " mem " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 + "K"); }
            boolean game = state == ST_PLAY || state == ST_INTRO || state == ST_OVER;
            if (!game || paused) { try { Thread.sleep(Math.max(5, 40 - spent)); } catch (InterruptedException e) { } }
            else if (spent < 12) { try { Thread.sleep(16 - spent); } catch (InterruptedException e) { } }   // ~60 fps cap; the N95 never gets here (flush alone is 11+ ms)
            else Thread.yield();
            acW += System.currentTimeMillis() - f1;
        }
        map.stop(); stopMusic(); midlet.quit();
    }
    float focusX() { return car != null ? car.x : claude.x; }
    float focusY() { return car != null ? car.y : claude.y; }

    // ------------------------------------------------------------------ update
    void update(float dt, int ks, int pr) {
        stT += dt; animT += dt;
        if (helpT > 0) { helpT -= dt; if (helpT <= 0) { help = helpNext; helpNext = null; if (help != null) helpT = 6; } }
        if (popupT > 0) popupT -= dt;
        if (shakeT > 0) shakeT -= dt;
        boolean act = (pr & K_5) != 0;
        if (dlgText != null) {
            dlgChars += dt * 40;
            if (act) { if (dlgChars < dlgText[dlgIdx].length()) dlgChars = 1000; else { dlgIdx++; dlgChars = 0; if (dlgIdx >= dlgText.length) dlgText = null; } act = false; pr &= ~K_5; }
        }
        switch (state) {
            case ST_LOAD: return;
            case ST_SND:
                if ((pr & (K_UP | K_DOWN | K_LEFT | K_RIGHT)) != 0) sndSel ^= 1;
                if (act) { sound = sndSel == 0; toMenu(); }
                return;
            case ST_MENU:
                if ((pr & K_UP) != 0) menuSel = (menuSel + MENU.length - 1) % MENU.length;
                if ((pr & K_DOWN) != 0) menuSel = (menuSel + 1) % MENU.length;
                if (act && map != null) {
                    if (menuSel == 0) startGame();
                    else if (menuSel == 1) { sound = !sound; if (sound) playMusic("/a/theme.mid", true); else stopMusic(); }
                    else if (menuSel == 2) setState(ST_CTRL); else running = false;
                }
                return;
            case ST_CTRL: if (act || (pr & (K_SOFT | K_0)) != 0) setState(ST_MENU); return;
            case ST_OVER:
                updateWorld(dt); followCam(dt);
                if (stT > 2 && act) { setState(ST_MENU); toMenu(); }
                return;
            case ST_INTRO: updateIntro(dt, pr); updateWorld(dt); return;
        }
        // ST_PLAY
        if (car != null) { car.throttle = 0; car.steer = 0; car.handbrake = false; driveControls(ks, pr); }
        else footControls(dt, ks, pr);
        if (car != null) {
            car.physics(map, dt, 27);
            if (car.drift > 0.35f && (car.speed() > 0.5f * M || Math.abs(car.w) > 1)) tyreSmoke(car, dt);
            if (car.imp > 4 * M) { sparks(car.impX, car.impY, Math.min(10, 2 + (int) (car.imp / (3 * M))), Math.min(1.6f, car.imp / (10 * M))); sfx(SFX_CRASH); }
            car.imp = 0;
            carVsPoles(car);
            if (car.damage >= 100 && car.fireT <= 0) { car.fireT = 3.5f; help = txt("WRECKED"); helpT = 3; }
        }
        updateWave(dt);
        updateRoadblock(dt);
        updateHeli(dt);
        updateWorld(dt);
        updatePickups(dt);
        followCam(dt);
        if (health <= 0 && state == ST_PLAY) gameOver();
    }

    void updateIntro(float dt, int pr) {
        phT += dt;
        Car k = parked[0];
        if ((pr & K_POUND) != 0 && phase < 2) {          // skip the cutscene
            dlgText = null; peds[0].active = false; claude.active = false;
            int n = ipX.length - 1; k.x = ipX[n]; k.y = ipY[n]; k.a = G.heading(ipX[n] - ipX[n - 1], ipY[n] - ipY[n - 1]);
            phase = 2; phT = 10; crash(k); shakeT = 0; camX = k.x; camY = k.y;
        }
        switch (phase) {
            case 0:   // 8-Ball's lines next to the car
                camTo(claude.x, claude.y - 60, dt, 2);
                if (dlgText == null && phT > 0.5f) { phase = 1; phT = 0; claude.active = false; peds[0].active = false; k.siren = false; startRadio(); help = null; helpT = 0; }
                break;
            case 1: { // 8-Ball floors it up the ramp
                float total = ipCum[ipCum.length - 1];
                float v = Math.min(26 * M, 4 * M + phT * 12 * M);
                float ox = k.x, oy = k.y;
                ipS = Math.min(total, ipS + v * dt);
                at(ipX, ipY, ipCum, ipS); k.x = pt[0]; k.y = pt[1];
                at(ipX, ipY, ipCum, Math.min(total, ipS + 40)); float hx = pt[0] - k.x, hy = pt[1] - k.y;
                if (hx * hx + hy * hy > 1) k.a += G.clamp(G.wrap(G.heading(hx, hy) - k.a), -3 * dt, 3 * dt);
                float lvx = (k.x - ox) / Math.max(dt, 0.001f), lvy = (k.y - oy) / Math.max(dt, 0.001f); k.vx = k.vy = 0;
                if (total - ipS < 400 && help == null) { help = txt("WI_8B"); helpT = 3; }
                camTo(k.x + lvx * 0.3f, k.y + lvy * 0.3f, dt, 4);
                if (ipS >= total) { phase = 2; phT = 0; crash(k); sfx(SFX_CRASH); }
                break;
            }
            case 2:   // smoking wreck, Claude climbs out
                camTo(k.x, k.y, dt, 3);
                if (phT > 1.4f) {
                    phase = 3; phT = 0;
                    k.locked = true; k.vx = k.vy = 0; wreck = k;
                    float fx = k.dirX(), fy = k.dirY();
                    claude.x = k.x + fy * (k.hw + 9) - fx * 6; claude.y = k.y - fx * (k.hw + 9) - fy * 6;
                    if (map.solid(claude.x, claude.y)) { claude.x = k.x - fx * (k.hl + 10); claude.y = k.y - fy * (k.hl + 10); }
                    claude.active = true; claude.a = k.a + 1.57f; claude.moving = false;
                    radioOn = false; stopMusic();
                    startWave(Math.max(2, Math.min(6, dbgWave)));
                    if (dbgAt != null) {                                // test builds: start next to a car somewhere else
                        Car c = newCar(idx(carName, "police")); c.ai = false; c.x = dbgAt[0]; c.y = dbgAt[1]; c.a = dbgAt[2] * G.PI / 180; if (dbgAt.length > 3) c.damage = dbgAt[3]; addParked(c);
                        claude.x = c.x - c.dirY() * (c.hw + 8); claude.y = c.y + c.dirX() * (c.hw + 8);
                    }
                    setState(ST_PLAY);
                    help = txt("WI_C1"); helpT = 6; helpNext = txt("HELP_FOOT");
                }
                break;
        }
    }
    void crash(Car k) {
        if (crashPole >= 0) map.pDown[crashPole] = true;
        k.damage = 70; k.smokeT = 1; k.vx = k.vy = 0; shakeT = 0.7f; vibrate(200);
       
        help = null; helpT = 0;
    }

    void updateWorld(float dt) {
        updateTraffic(dt);
        updateCopCars(dt);
        updatePeds(dt);
        updateParked(dt);
        collideCars();
        if (car != null && car.fireT > 0) {
            car.fireT -= dt;
            if (car.fireT <= 0) { explode(car); }
        }
        for (int i = 0; i < parked.length; i++) {
            Car c = parked[i];
            if (c != null && c.fireT > 0) { c.fireT -= dt; if (c.fireT <= 0) explode(c); }
        }
        if (flashT > 0) flashT -= dt;
        if (aimShowT > 0) aimShowT -= dt;
        for (int i = 0; i < 4; i++) if (exT[i] > 0) exT[i] += dt;
        for (int i = 0; i < NSM; i++) if (mL[i] > 0) { mL[i] -= dt; mY[i] -= 5 * dt; }
        for (int i = 0; i < NSPK; i++) if (kL[i] > 0) { kL[i] -= dt; kX[i] += kVX[i] * dt; kY[i] += kVY[i] * dt; kVY[i] += 300 * dt; kVX[i] *= 0.94f; }
    }
    /** grey puffs off both rear wheels while the car slides */
    void tyreSmoke(Car c, float dt) {
        smokeAcc += dt; if (smokeAcc < 0.07f) return; smokeAcc = 0;
        float fx = c.dirX(), fy = c.dirY(), rx = -fy, ry = fx;
        for (int s = -1; s <= 1; s += 2) {
            int k = mHead; mHead = (mHead + 1) % NSM;
            mX[k] = c.x - fx * c.hl * 0.7f + rx * c.hw * 0.8f * s + (rf() - 0.5f) * 4; mY[k] = c.y - fy * c.hl * 0.7f + ry * c.hw * 0.8f * s + (rf() - 0.5f) * 4; mL[k] = 0.75f;
        }
    }
    void drawTyreSmoke(Graphics g, int cx, int cy) {
        for (int i = 0; i < NSM; i++) {
            float l = mL[i]; if (l <= 0) continue;
            int r = 2 + (int) ((0.75f - l) * 9), x = (int) mX[i] - cx, y = (int) mY[i] - cy;
            g.setColor(l > 0.4f ? 0xe4e4e4 : 0xbcbcbc); g.fillArc(x - r, y - r, r * 2, r * 2, 0, 360);
        }
    }
    /** metal-on-metal sparks: n particles thrown out of (x,y), power ~ impact */
    void sparks(float x, float y, int n, float power) {
        for (int i = 0; i < n; i++) {
            int k = kHead; kHead = (kHead + 1) % NSPK;
            float a = rf() * 6.2832f, v = (50 + rf() * 110) * power;
            kX[k] = x; kY[k] = y - 6; kVX[k] = G.sin(a) * v; kVY[k] = -G.cos(a) * v * 0.6f - 50 * power; kL[k] = 0.22f + rf() * 0.3f;
        }
    }
    void drawSparks(Graphics g, int cx, int cy) {
        for (int i = 0; i < NSPK; i++) {
            float l = kL[i]; if (l <= 0) continue;
            int x = (int) kX[i] - cx, y = (int) kY[i] - cy;
            g.setColor(l > 0.32f ? 0xffffff : (l > 0.16f ? 0xffe050 : 0xff8020));
            g.drawLine(x, y, x - (int) (kVX[i] * 0.035f), y - (int) (kVY[i] * 0.035f)); g.fillRect(x, y, 2, 2);
        }
    }
    void explode(Car c) {
        c.wreck = true; c.fireT = 0; c.damage = 200; c.wreckAt = animT; shakeT = 0.7f; vibrate(300);
        sfx(SFX_BOOM);
        int k = exHead; exHead = (exHead + 1) & 3; exX[k] = c.x; exY[k] = c.y; exT[k] = 0.001f;
        sparks(c.x, c.y, 16, 1.9f);
        for (int i = 0; i < 6; i++) { int m = mHead; mHead = (mHead + 1) % NSM; mX[m] = c.x + (rf() - 0.5f) * 40; mY[m] = c.y + (rf() - 0.5f) * 24; mL[m] = 0.75f; }
        if (c == car) { health = 0; exitCar(); claude.active = false; addBody(claude); }
        for (int i = 0; i < peds.length; i++) {
            Ped p = peds[i]; if (!p.active) continue;
            if (near(p, c.x, c.y, 4 * M)) kill(p, false); else if (p.role != Ped.COP && near(p, c.x, c.y, 14 * M)) scare(p, c.x, c.y);
        }
        if (claude.active && near(claude, c.x, c.y, 4.5f * M)) {
            hurtPlayer(near(claude, c.x, c.y, 3 * M) ? 36 : 16);
            if (claude.active && claude.knockT <= 0) { claude.knockT = 1.0f; deadPose = rnd.nextInt(4); float dx = claude.x - c.x, dy = claude.y - c.y, d = Math.max(1, (float) Math.sqrt(dx * dx + dy * dy)); stepPed(claude, dx / d * 14, dy / d * 14); }
        }
        for (int t = 0; t < 4; t++) {                    // the blast: nearby cars get hurt and shoved - enough of it and they go up too
            Car[] arr = t == 0 ? parked : (t == 1 ? traffic : copCars); int n = t == 3 ? 1 : arr.length;
            for (int i = 0; i < n; i++) {
                Car o = t == 3 ? car : arr[i]; if (o == null || o == c || o.wreck || o.locked || (t == 0 && o == car)) continue;   // 8-Ball's wreck stays ambiguous
                float dx = o.x - c.x, dy = o.y - c.y, d = (float) Math.sqrt(dx * dx + dy * dy), r = 7 * M;
                if (d >= r) continue;
                float f = 1 - d / r; d = Math.max(1, d);
                o.hurt(25 + 70 * f); o.vx += dx / d * 140 * f; o.vy += dy / d * 140 * f; o.w += (rf() - 0.5f) * 3 * f;
                if (o.ai && !o.cop) o.loose = 1.2f + rf();
                if (o.cop) o.spd *= 0.3f;
                if (o.damage >= 100 && o.fireT <= 0) o.fireT = o == car ? 3.5f : 1.0f + rf() * 1.2f;   // a chain reaction never blows YOUR car up without warning
                if (o == car) hurtPlayer(10 * f);
            }
        }
    }
    void drawBooms(Graphics g, int cx, int cy) {
        if (boomImg == null) return;
        for (int i = 0; i < 4; i++) {
            if (exT[i] <= 0) continue;
            int f = (int) (exT[i] / 0.075f); if (f >= boomN) { exT[i] = 0; continue; }
            g.drawRegion(boomImg, f * boomW, 0, boomW, boomH, 0, (int) exX[i] - boomW / 2 - cx, (int) exY[i] - boomH * 2 / 3 - cy, TL);
        }
    }
    boolean near(Mob m, float x, float y, float r) { float dx = m.x - x, dy = m.y - y; return dx * dx + dy * dy < r * r; }

    // ------------------------------------------------------------------ player: car
    void driveControls(int ks, int pr) {
        if ((ks & K_UP) != 0) car.throttle = 1; if ((ks & K_DOWN) != 0) car.throttle = -1;
        if ((ks & K_LEFT) != 0) car.steer = -1; if ((ks & K_RIGHT) != 0) car.steer = 1;
        if ((ks & K_POUND) != 0) car.handbrake = true;
        if ((pr & K_5) != 0) horn();
        if ((pr & K_0) != 0 || car.wreck) { if (car.speed() < 3 * M || car.wreck) exitCar(); else car.handbrake = true; }
    }
    void horn() {
        for (int i = 0; i < peds.length; i++) { Ped p = peds[i]; if (p.active && p.role == Ped.CIV && ahead(car, p, car.hl + 10 * M, car.hw + 3 * M)) scare(p, car.x, car.y); } }
    void exitCar() {
        Car c = car; if (c == null) return;
        float fx = c.dirX(), fy = c.dirY(), rx = -fy, ry = fx;
        for (int s = -1; s <= 1; s += 2) {
            float x = c.x + rx * (c.hw + 8) * s, y = c.y + ry * (c.hw + 8) * s;
            if (!pedBlocked(x, y)) { claude.x = x; claude.y = y; break; }
            claude.x = c.x - fx * (c.hl + 9); claude.y = c.y - fy * (c.hl + 9);
        }
        claude.active = true; claude.a = c.a; claude.moving = false;
        c.throttle = 0; c.steer = 0; c.handbrake = false; c.ai = false;
        addParked(c); car = null; radioOn = false; stopMusic();
    }
    void addParked(Car c) {
        int slot = -1; float far = -1;
        for (int i = 0; i < parked.length; i++) {
            if (parked[i] == c) return;
            if (parked[i] == null) { slot = i; break; }
            if (parked[i].locked) continue;                         // 8-Ball's wreck and the exit roadblocks stay
            float dx = parked[i].x - c.x, dy = parked[i].y - c.y, d = dx * dx + dy * dy; if (d > far) { far = d; slot = i; }
        }
        if (slot >= 0) parked[slot] = c;
    }
    static final float[] JACK_S = {0.9f, 1.5f, 1.2f};   // parked, traffic (driver inside), cop car
    boolean jackOk() {
        Car c = jackCar; if (c == null || c.locked || c.wreck || c.fireT > 0) return false;
        Car[] arr = jackSrc == 0 ? parked : (jackSrc == 1 ? traffic : copCars);
        if (arr[jackIx] != c) return false;
        float dx = claude.x - c.x, dy = claude.y - c.y, r = c.hl + 2.5f * M;
        return dx * dx + dy * dy < r * r;
    }
    void tryEnterCar() {
        Car best = null; float bd = 1e9f; int src = -1, ix = -1;
        for (int t = 0; t < 3; t++) {
            Car[] arr = t == 0 ? parked : (t == 1 ? traffic : copCars);
            for (int i = 0; i < arr.length; i++) {
                Car c = arr[i]; if (c == null || c.locked || c.wreck || c.fireT > 0) continue;
                if (t == 2 && c.crew > 0 && c.speed() > 2 * M) continue;
                float dx = claude.x - c.x, dy = claude.y - c.y, fx = c.dirX(), fy = c.dirY();
                float al = Math.abs(dx * fx + dy * fy), sd = Math.abs(-dx * fy + dy * fx);
                if (al > c.hl + 1.0f * M || sd > c.hw + 1.4f * M) continue;
                float d = dx * dx + dy * dy; if (d < bd) { bd = d; best = c; src = t; ix = i; }
            }
        }
        if (best == null) return;
        jackCar = best; jackSrc = src; jackIx = ix; jackT = 0; jackDur = JACK_S[src];
        if (src == 2) best.holdT = Math.max(best.holdT, jackDur + 0.5f);
       
    }
    void enterCar(Car best, int src, int ix) {
        jackCar = null;
        if (src == 0) { parked[ix] = null; best.cop = false; best.siren = false; }
        else if (src == 1) {
            traffic[ix] = null;
            Ped p = freePed();
            if (p != null) { p.x = best.x - best.dirY() * (best.hw + 8); p.y = best.y + best.dirX() * (best.hw + 8); p.type = civTypes[rnd.nextInt(civTypes.length)]; p.role = Ped.CIV; p.active = true; p.health = 100; p.speed = 1.4f * M; p.edge = -1; scare(p, claude.x, claude.y); }
        } else { copCars[ix] = null; best.cop = false; best.siren = false; }
        best.ai = false; best.loose = 0; best.vx *= 0.3f; best.vy *= 0.3f; best.w = 0;
        car = best; claude.active = false; claude.moving = false; atkT = 0;
        startRadio();
    }

    // ------------------------------------------------------------------ player: on foot
    void footControls(float dt, int ks, int pr) {
        Ped p = claude;
        if (!p.active) return;
        if (p.knockT > 0) { p.knockT -= dt; p.moving = false; atkT = 0; jackCar = null; return; }
        if (jackCar != null) {
            if ((ks & (K_UP | K_DOWN | K_LEFT | K_RIGHT)) != 0 || !jackOk()) jackCar = null;
            else {
                p.moving = false; p.a = G.heading(jackCar.x - p.x, jackCar.y - p.y); jackT += dt;
                if (jackSrc == 1) { jackCar.spd = Math.max(0, jackCar.spd - 20 * M * dt); }
                if (jackT >= jackDur) enterCar(jackCar, jackSrc, jackIx);
                pushFromCars(p);
                return;
            }
        }
        if (atkT > 0) atkT -= dt;
        if (shotT > 0) shotT -= dt;
        if ((pr & K_STAR) != 0) { for (int k = 1; k <= NW; k++) { int w = (weapon + k) % NW; if (have[w] && (w == W_FIST || ammo[w] > 0)) { weapon = w; break; } } }
        if ((pr & K_0) != 0) { tryEnterCar(); if (car != null) return; }
        boolean fire = W_AUTO[weapon] ? (ks & K_5) != 0 : (pr & K_5) != 0;
        if (fire && shotT <= 0) attack();
        int dx = ((ks & K_RIGHT) != 0 ? 1 : 0) - ((ks & K_LEFT) != 0 ? 1 : 0);
        int dy = ((ks & K_DOWN) != 0 ? 1 : 0) - ((ks & K_UP) != 0 ? 1 : 0);
        if (dx != 0 || dy != 0) {
            boolean sprint = (ks & K_POUND) != 0 && (ks & K_5) == 0;
            float want = G.heading(dx, dy);
            if ((ks & K_5) == 0 || aimP == null) p.a = want;
            float mps = sprint ? 9.0f : ((ks & K_5) != 0 ? 3.9f : 5.4f), sp = mps * M * dt;
            stepPed(p, G.sin(want) * sp, -G.cos(want) * sp);
            p.bob += dt * mps * 1.33f; p.moving = true;
        } else p.moving = false;
        pushFromCars(p);
        pedVsPoles(p);
    }
    void attack() {
        int w = weapon;
        if (w != W_FIST && ammo[w] <= 0) { weapon = W_FIST; w = W_FIST; }
        float reach = W_RANGE[w] * M + (w == W_FIST ? 1.2f * M : 0), cone = w == W_FIST ? 1.2f : (w == W_SHOTGUN ? 0.6f : 0.8f);
        // auto-aim: cops first (any direction, the facing cone is preferred), others only in the cone
        Ped tgt = null; float bs = 1e18f;
        for (int i = 0; i < peds.length; i++) {
            Ped q = peds[i]; if (!q.active || q.role == Ped.EIGHT) continue;
            float dx = q.x - claude.x, dy = q.y - claude.y, d = dx * dx + dy * dy;
            if (d >= reach * reach) continue;
            float ang = Math.abs(G.wrap(G.heading(dx, dy) - claude.a));
            boolean qc = q.role == Ped.COP;
            if (!qc && ang > cone) continue;
            if (w != W_FIST && !map.los(claude.x, claude.y - 10, q.x, q.y - 10)) continue;
            float sc = d * (ang > cone ? 2.5f : 1) * (qc ? 0.5f : 1);
            if (sc < bs) { bs = sc; tgt = q; }
        }
        aimP = tgt; aimShowP = tgt; aimShowC = null; aimShowT = 0.8f;
        if (tgt != null) claude.a = G.heading(tgt.x - claude.x, tgt.y - claude.y);
        atkT = w == W_FIST ? 0.42f : 0.25f; shotT = W_RATE[w];
        if (w == W_FIST) {
            float fx = claude.dirX(), fy = claude.dirY();
            for (int i = 0; i < peds.length; i++) {
                Ped q = peds[i]; if (!q.active || q.role == Ped.EIGHT) continue;
                float dx = q.x - claude.x, dy = q.y - claude.y, al = dx * fx + dy * fy, sd = Math.abs(-dx * fy + dy * fx);
                if (al > -0.2f * M && al < W_RANGE[w] * M && sd < 0.9f * M) hurt(q, W_DMG[w], false);
            }
            return;
        }
        ammo[w]--; flashT = 0.07f;
        sfx(w);
        float fx = claude.dirX(), fy = claude.dirY();
        aimX = claude.x + fx * reach; aimY = claude.y + fy * reach; aimHit = false;
        if (w == W_SHOTGUN) {          // spread: everyone in the cone within range
            for (int i = 0; i < peds.length; i++) {
                Ped q = peds[i]; if (!q.active || q.role == Ped.EIGHT) continue;
                float dx = q.x - claude.x, dy = q.y - claude.y; if (dx * dx + dy * dy > reach * reach) continue;
                if (Math.abs(G.wrap(G.heading(dx, dy) - claude.a)) > 0.45f) continue;
                if (!map.los(claude.x, claude.y - 10, q.x, q.y - 10)) continue;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                hurt(q, (int) (W_DMG[w] * (1.2f - d / reach)), false); aimHit = true;
            }
            if (tgt != null) { aimX = tgt.x; aimY = tgt.y - 10; }
        } else if (tgt != null) {
            float dx = tgt.x - claude.x, dy = tgt.y - claude.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            float hit = 0.95f - d / reach * 0.35f;
            aimX = tgt.x; aimY = tgt.y - 10;
            if (rf() < hit) { hurt(tgt, W_DMG[w], false); aimHit = true; }
        } else {                         // shoot cars in front
            Car c = carAhead(claude.x, claude.y, fx, fy, reach);
            if (c != null) { c.hurt(W_DMG[w] * 0.06f); aimX = c.x; aimY = c.y; aimHit = true; aimShowC = c; if (c.damage >= 100 && c.fireT <= 0 && !c.wreck) c.fireT = 3; }
        }
        if (ammo[w] <= 0 && w != W_FIST) { int k = W_PISTOL; weapon = ammo[k] > 0 ? k : W_FIST; }
    }
    Car carAhead(float x, float y, float fx, float fy, float reach) {
        Car best = null; float bd = 1e9f;
        for (int t = 0; t < 3; t++) {
            Car[] arr = t == 0 ? parked : (t == 1 ? traffic : copCars);
            for (int i = 0; i < arr.length; i++) {
                Car c = arr[i]; if (c == null || c.wreck || c.locked) continue;
                float dx = c.x - x, dy = c.y - y, al = dx * fx + dy * fy, sd = Math.abs(-dx * fy + dy * fx);
                if (al > 0 && al < reach && sd < c.hw + 6 && al < bd) { bd = al; best = c; }
            }
        }
        return best;
    }
    boolean pedBlocked(float x, float y) {
        float r = 5;
        return map.solid(x, y) || map.solid(x - r, y) || map.solid(x + r, y) || map.solid(x, y - r) || map.solid(x, y + 3);
    }
    boolean stepPed(Ped p, float mx, float my) {
        float nx = p.x + mx, ny = p.y + my;
        if (!pedBlocked(nx, ny)) { p.x = nx; p.y = ny; return true; }
        if (mx != 0 && !pedBlocked(nx, p.y)) { p.x = nx; return true; }
        if (my != 0 && !pedBlocked(p.x, ny)) { p.y = ny; return true; }
        return false;
    }
    void pedVsPoles(Ped p) {
        int n = map.poles(p.x, p.y, 16, tmpI);
        for (int k = 0; k < n; k++) {
            int i = tmpI[k]; float dx = p.x - map.pFx[i], dy = p.y - map.pFoot[i], r = map.pR[i] + 4, d2 = dx * dx + dy * dy;
            if (d2 < r * r && d2 > 0.01f) { float d = (float) Math.sqrt(d2); stepPed(p, dx / d * (r - d), dy / d * (r - d)); }
        }
    }
    void carVsPoles(Car c) {
        int n = map.poles(c.x, c.y, c.hl + 8, tmpI);
        float fx = c.dirX(), fy = c.dirY();
        for (int k = 0; k < n; k++) {
            int i = tmpI[k]; float dx = map.pFx[i] - c.x, dy = map.pFoot[i] - c.y;
            float al = dx * fx + dy * fy, sd = -dx * fy + dy * fx, r = map.pR[i];
            if (Math.abs(al) > c.hl + r || Math.abs(sd) > c.hw + r) continue;
            float sp = c.speed();
            if (sp > 3.5f * M) { map.pDown[i] = true; c.vx *= 0.82f; c.vy *= 0.82f; c.hurt(2); vibrate(60); if (c == car || vis(map.pFx[i], map.pFoot[i])) sfx(SFX_CRASH); sparks(map.pFx[i], map.pFoot[i], 6, Math.min(1.4f, sp / (12 * M))); }
            else {   // push the car out along the shallower axis
                float el = c.hl + r - Math.abs(al), es = c.hw + r - Math.abs(sd);
                if (el < es) { float m = el * (al >= 0 ? -1 : 1); c.move(map, fx * m, fy * m); }
                else { float m = es * (sd >= 0 ? -1 : 1); c.move(map, -fy * m, fx * m); }
                c.vx *= 0.5f; c.vy *= 0.5f;
            }
        }
    }
    void pushFromCars(Ped p) {
        for (int t = 0; t < 4; t++) {
            Car[] arr = t == 0 ? parked : (t == 1 ? traffic : copCars); int n = t == 3 ? 1 : arr.length;
            for (int i = 0; i < n; i++) {
                Car c = t == 3 ? car : arr[i]; if (c == null) continue;
                float dx = p.x - c.x, dy = p.y - c.y, fx = c.dirX(), fy = c.dirY();
                float al = dx * fx + dy * fy, sd = -dx * fy + dy * fx;
                float el = c.hl + 0.3f * M - Math.abs(al), es = c.hw + 0.3f * M - Math.abs(sd);
                if (el <= 0 || es <= 0) continue;
                if (el < es) { float m = el * (al >= 0 ? 1 : -1); stepPed(p, fx * m, fy * m); }
                else { float m = es * (sd >= 0 ? 1 : -1); stepPed(p, -fy * m, fx * m); }
            }
        }
    }
    void hurt(Ped q, int dmg, boolean byCar) {
        q.health -= dmg; q.hitT = 0.25f;
        float dx = q.x - claude.x, dy = q.y - claude.y, d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d > 0.01f) stepPed(q, dx / d * 3, dy / d * 3);
        if (q.health <= 0) { kill(q, byCar); return; }
        if (q.role == Ped.CIV || q.role == Ped.TRIAD) scare(q, claude.x, claude.y);
        if (q.role == Ped.COP) { q.state = Ped.SHOOT; q.fireT = Math.min(q.fireT, 0.4f); }
    }
    void scare(Ped p, float fx, float fy) { p.state = Ped.FLEE; p.timer = 1.8f; p.a = G.heading(p.x - fx, p.y - fy) + (rf() - 0.5f) * 0.6f; }
    void kill(Ped p, boolean byCar) {
        p.active = false; addBody(p); vibrate(60);
        if (p.role == Ped.MISTY) mistyDead = true;
        if (p.role == Ped.COP && state == ST_PLAY) { kills++; waveKills++; }
        if (byCar && (p.role == Ped.COP || p.role == Ped.TRIAD) && state == ST_PLAY) {   // run them over for ammo
            int w = weapon == W_FIST ? W_PISTOL : weapon; have[w] = true;
            int n = Math.max(6, W_CLIP[w] / 3); ammo[w] += n; popup = "+" + n + " " + WN[w].toUpperCase(); popupT = 1.5f;
        }
    }
    void addBody(Ped p) { int i = deadN % deadX.length; deadN++; deadX[i] = p.x; deadY[i] = p.y; deadT[i] = p.type; deadF[i] = rnd.nextInt(4); }
    long vibT;
    void vibrate(int ms) { long n = System.currentTimeMillis(); if (!sound || n - vibT < 600) return; vibT = n; try { Display.getDisplay(midlet).vibrate(ms); } catch (Throwable t) { } }
    // ------------------------------------------------------------------ sound effects: GTA III's own gun, explosion + crash sounds (the build makes a/s_*.wav)
    static final String[] SFX = {"", "pistol", "uzi", "shotgun", "ak47", "m16", "boom", "crash"};   // index = weapon id (fist: none), 6 = explosion, 7 = car crash
    static final int SFX_BOOM = 6, SFX_CRASH = 7;
    static final int[] SFX_GAP = {0, 110, 80, 280, 90, 80, 200, 250};        // ms before the same sound may restart
    Player[] sfxP = new Player[SFX.length]; long[] sfxT = new long[SFX.length];
    int sfxPend; final Object sfxLock = new Object();
    void initSfx() {
        for (int i = 1; i < SFX.length; i++) {
            InputStream is = getClass().getResourceAsStream("/a/s_" + SFX[i] + ".wav"); if (is == null) continue;
            try { Player p = Manager.createPlayer(is, "audio/x-wav"); p.realize(); p.prefetch(); sfxP[i] = p; } catch (Throwable t) { sndErr = "wav: " + t; }
        }
        new Thread(new Runnable() { public void run() { sfxLoop(); } }).start();
    }
    /** asks the sound thread to (re)start a sound: the game loop never waits for the phone's media player */
    void sfx(int i) {
        if (!sound || i <= 0 || i >= SFX.length || sfxP[i] == null) return;
        long n = System.currentTimeMillis(); if (n - sfxT[i] < SFX_GAP[i]) return; sfxT[i] = n;
        synchronized (sfxLock) { sfxPend |= 1 << i; sfxLock.notify(); }
    }
    void sfxLoop() {
        while (true) {
            int m;
            synchronized (sfxLock) {
                while (sfxPend == 0) { try { sfxLock.wait(); } catch (InterruptedException e) { return; } }
                m = sfxPend; sfxPend = 0;
            }
            for (int i = SFX.length - 1; i > 0; i--) {
                if ((m & (1 << i)) == 0) continue;
                Player p = sfxP[i];
                try { if (p.getState() == Player.STARTED) p.stop(); p.setMediaTime(0); p.start(); } catch (Throwable t) { }
            }
        }
    }
    void hurtPlayer(float d) {
        if (state != ST_PLAY || GOD) return;
        d *= 0.9f;                                                  // v0.7.1: 10% less damage to the player
        if (car != null) { car.hurt(d * 0.3f); if (car.damage >= 100 && car.fireT <= 0 && !car.wreck) car.fireT = 3.5f; health -= d * 0.25f; }
        else health -= d;
        if (health <= 0) { health = 0; if (claude.active) { addBody(claude); claude.active = false; } gameOver(); }
    }
    void gameOver() {
        if (state == ST_OVER) return;
        setState(ST_OVER); radioOn = false; stopMusic(); dlgText = null; help = null;
        newBest = survT > best; if (newBest) { best = survT; saveBest(best); }
       
    }

    // ------------------------------------------------------------------ waves (rampage rule: survive the time AND get the kills)
    void startWave(int s) {
        needPed(idx(pedName, WV_PED[s])); needCar(idx(carName, WV_CAR[s]));
        stars = s; waveKills = 0; waveT = 0; overtime = false; overT = 0; spawnT = 1.5f;
       
        newPickups();
        if (s > 2) { help = txt("WI_CLEAR"); helpT = 4; health = Math.min(100, health + 40); }
        // two cars straight away
        spawnCop(); spawnCop();
    }
    void updateWave(float dt) {
        survT += dt; waveT += dt;
        if (stars < 6) {
            boolean timeOk = waveT >= WV_TIME[stars], killsOk = waveKills >= WV_QUOTA[stars];
            if (timeOk && killsOk) { startWave(stars + 1); return; }
            if (timeOk && !overtime) { overtime = true; help = txt("WI_OVER"); helpT = 4; }
        }
        if (overtime || stars == 6) overT += dt;
        float every = WV_EVERY[stars] / (1 + overT / 30f);         // the longer you stall, the faster they come
        spawnT -= dt;
        if (spawnT > 2 && copsNear() == 0) spawnT = 2;                                 // nobody on his tail: the next car is on its way
        if (spawnT <= 0) { spawnT = Math.max(2.5f, every); if (copsAlive() < WV_ALIVE[stars] + (int) (overT / 40)) spawnCop(); }
    }
    int copsNear() {
        float px = focusX(), py = focusY(), r = 60 * M; int n = 0;
        for (int i = 0; i < peds.length; i++) { Ped p = peds[i]; if (p.active && p.role == Ped.COP && near(p, px, py, r)) n++; }
        for (int i = 0; i < copCars.length; i++) { Car c = copCars[i]; if (c != null && near(c, px, py, r)) n++; }
        return n;
    }
    float nearestCop() {
        float px = focusX(), py = focusY(), bd = 1e9f;
        for (int i = 0; i < peds.length; i++) { Ped p = peds[i]; if (p.active && p.role == Ped.COP) { float dx = p.x - px, dy = p.y - py; bd = Math.min(bd, dx * dx + dy * dy); } }
        for (int i = 0; i < copCars.length; i++) { Car c = copCars[i]; if (c != null) { float dx = c.x - px, dy = c.y - py; bd = Math.min(bd, dx * dx + dy * dy); } }
        return (float) Math.sqrt(bd) / M;
    }
    int nCopCars() { int n = 0; for (int i = 0; i < copCars.length; i++) if (copCars[i] != null) n++; return n; }
    int copsAlive() {
        int n = 0;
        for (int i = 0; i < peds.length; i++) if (peds[i].active && peds[i].role == Ped.COP) n++;
        for (int i = 0; i < copCars.length; i++) if (copCars[i] != null) n += copCars[i].crew;
        return n;
    }
    /** a cop car at the map spawn point nearest to the player (off screen); if they are all far away, at a road node near him */
    int carRoll;
    void spawnCop() {
        int room = WV_ALIVE[stars] + (int) (overT / 40) - copsAlive(); if (room <= 0) return;     // never more cops than the wave allows
        if (nCopCars() >= WV_CARS[stars]) { spawnFoot(Math.min(room, 2 + (stars >= 4 ? 1 : 0))); return; }
        if (++carRoll % 5 == 0) { spawnFoot(Math.min(room, WV_CREW[stars])); return; }      // v0.7.2: 20% fewer cop cars, same number of cops
        int slot = -1; for (int i = 0; i < copCars.length; i++) if (copCars[i] == null) { slot = i; break; }
        if (slot < 0) return;
        float px = focusX(), py = focusY(); int bi = -1; float bd = 1e18f;
        for (int k = 0; k < nSp; k++) {
            float dx = spX[k] - px, dy = spY[k] - py, d = dx * dx + dy * dy;
            if (d < 350 * 350 || onScreen(spX[k], spY[k], 30)) continue;
            d *= 0.6f + rf() * 0.8f;
            if (d < bd) { bd = d; bi = k; }
        }
        float sx, sy;
        if (bi >= 0 && bd < 900 * 900) { sx = spX[bi]; sy = spY[bi]; }
        else { int n = nodeNear(px, py); if (n < 0) { if (bi < 0) return; sx = spX[bi]; sy = spY[bi]; } else { sx = map.px[0][n]; sy = map.py[0][n]; } }
        Car c = newCar(idx(carName, WV_CAR[stars]));
        c.x = sx; c.y = sy; c.cop = true; c.siren = true; c.crew = Math.min(WV_CREW[stars], room); c.ai = true;
        c.a = G.heading(px - c.x, py - c.y); c.spd = 6 * M; c.cruise = (22 + stars * 1.5f) * M; c.edge = -1;
        int n = nearestNode(0, c.x, c.y); c.to = n; c.from = n; c.mode = 0;
        if (n >= 0) { c.tx = map.px[0][n]; c.ty = map.py[0][n]; }
        copCars[slot] = c;
    }
    /** a car-graph node 32-55 m from (x,y), off screen, ahead of the player's car if he is driving */
    int nodeNear(float x, float y) {
        short[] X = map.px[0], Y = map.py[0]; int best = -1; float bs = 1e18f;
        float vx = car != null ? car.vx : 0, vy = car != null ? car.vy : 0, vl = (float) Math.sqrt(vx * vx + vy * vy);
        for (int i = 0; i < X.length; i++) {
            float dx = X[i] - x, dy = Y[i] - y, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 380 || d > 660 || onScreen(X[i], Y[i], 40)) continue;
            float sc = Math.abs(d - 480) + rf() * 120;
            if (vl > 4 * M && (dx * vx + dy * vy) / (d * vl) > 0.3f) sc -= 150;     // cut him off
            if (sc < bs) { bs = sc; best = i; }
        }
        return best;
    }
    int nearestNode(int g, float x, float y) {
        int best = -1; float bd = 1e18f; short[] X = map.px[g], Y = map.py[g];
        for (int i = 0; i < X.length; i++) { float dx = X[i] - x, dy = Y[i] - y, d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i; } }
        return best;
    }
    /** Dijkstra (O(n^2), n ~ 330) from the node nearest to the player, once a second */
    int[] hpN = new int[2048]; float[] hpK = new float[2048]; int hpS;
    void hpush(int n, float k) {
        if (hpS >= hpN.length) return;
        int i = hpS++; while (i > 0) { int p = (i - 1) >> 1; if (hpK[p] <= k) break; hpN[i] = hpN[p]; hpK[i] = hpK[p]; i = p; }
        hpN[i] = n; hpK[i] = k;
    }
    int hpop() {
        int r = hpN[0]; hpS--; int ln = hpN[hpS]; float lk = hpK[hpS]; int i = 0;
        while (true) { int c = 2 * i + 1; if (c >= hpS) break; if (c + 1 < hpS && hpK[c + 1] < hpK[c]) c++; if (hpK[c] >= lk) break; hpN[i] = hpN[c]; hpK[i] = hpK[c]; i = c; }
        hpN[i] = ln; hpK[i] = lk; return r;
    }
    /** shortest road distance from every car-graph node to the node nearest the player (once a second) */
    void route(float dt) {
        routeT -= dt; if (routeT > 0 && routeTarget >= 0) return;
        routeT = 1;
        int src = nearestNode(0, focusX(), focusY()); if (src == routeTarget && nCopCars() == 0) return; routeTarget = src; if (src < 0) return;
        int n = rD.length; short[] X = map.px[0], Y = map.py[0];
        for (int i = 0; i < n; i++) { rD[i] = 1e9f; rDone[i] = false; }
        rD[src] = 0; hpS = 0; hpush(src, 0);
        while (hpS > 0) {
            int u = hpop(); if (rDone[u]) continue; rDone[u] = true;
            int[] adj = map.adj[0][u];
            for (int k = 0; k < adj.length; k++) {
                int e = adj[k], v = map.ea[0][e] == u ? map.eb[0][e] : map.ea[0][e];
                float dx = X[v] - X[u], dy = Y[v] - Y[u], d = rD[u] + (float) Math.sqrt(dx * dx + dy * dy);
                if (d < rD[v]) { rD[v] = d; hpush(v, d); }
            }
        }
    }
    float ramT;
    /** hit by a car: Claude goes down (dead sprite) for a moment */
    void knockDown(Car c) {
        claude.knockT = 1.3f; claude.moving = false; atkT = 0; deadPose = rnd.nextInt(4); vibrate(150);
        float d = Math.max(1, c.speed()); stepPed(claude, c.vx / d * 14, c.vy / d * 14);
       
    }
    int deadPose;
    void updateCopCars(float dt) {
        if (ramT > 0) ramT -= dt;
        if (state == ST_PLAY) route(dt);
        float px = focusX(), py = focusY();
        for (int i = 0; i < copCars.length; i++) {
            Car c = copCars[i]; if (c == null) continue;
            if (c.wreck) { addParked(c); copCars[i] = null; continue; }
            if (c.fireT > 0) { c.fireT -= dt; if (c.fireT <= 0) { explode(c); kills += c.crew; waveKills += c.crew; c.crew = 0; } }
            if (c.fireT > 0 && !c.wreck && state == ST_PLAY) { float v = c.spd; if (c.crew > 0) dismount(c); c.vx = c.dirX() * v * 0.7f; c.vy = c.dirY() * v * 0.7f; c.siren = false; addParked(c); copCars[i] = null; continue; }
            float dx = px - c.x, dy = py - c.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            boolean onFoot = car == null;
            // close to a player on foot: from 3 stars try to run him down first (max 4 s), then stop and let the crew out
            if (onFoot && d < 22 * M && stars >= 3 && c.fireT <= 0 && c.mode != 3) { c.mode = 2; c.ramT += dt; if (c.ramT > 4 || d < 2.5f * M) { c.mode = 3; c.holdT = 1.5f; } }
            if (state != ST_PLAY || (onFoot && d < 12 * M && c.mode != 2) || c.holdT > 0) {
                c.spd = Math.max(0, c.spd - 18 * M * dt); c.vx = c.dirX() * c.spd; c.vy = c.dirY() * c.spd;
                c.move(map, c.vx * dt, c.vy * dt);
                if (c.spd < 1 * M && c.crew > 0 && state == ST_PLAY) dismount(c);
                if (c.crew == 0) { c.siren = (animT % 2) < 1.6f; addParked(c); copCars[i] = null; }
                if (c.holdT > 0) c.holdT -= dt;
                continue;
            }
            // far behind and out of sight: put it back on a road near the player (GTA style), so they never lose you
            if (d > 70 * M && !onScreen(c.x, c.y, 60)) { c.farT += dt; if (c.farT > 2.5f) { c.farT = 0; relocate(c, px, py); continue; } } else c.farT = 0;
            float tx, ty;
            boolean direct = c.mode == 2 || (d < 22 * M && map.los(c.x, c.y, px, py));
            if (direct) { tx = px; ty = py; if (c.mode != 2) c.mode = 1; }
            else {
                c.mode = 0;
                float ddx = c.tx - c.x, ddy = c.ty - c.y;
                if (ddx * ddx + ddy * ddy < (3 * M) * (3 * M) || c.edge < 0) nextCopNode(c);
                tx = c.tx; ty = c.ty;
            }
            float want = G.heading(tx - c.x, ty - c.y), turn = G.wrap(want - c.a);
            float maxT = 2.4f * dt * Math.max(0.35f, Math.min(1, c.spd / (6 * M)));
            c.a += G.clamp(turn, -maxT, maxT);
            float target = Math.abs(turn) > 0.6f ? c.cruise * 0.4f : (c.mode == 2 ? c.cruise * 1.15f : c.cruise);
            if (d > 40 * M && Math.abs(turn) <= 0.6f) target *= 1.2f;                    // catch up
            if (c.stuckT > 0) { c.stuckT -= dt; c.spd = -4 * M; }
            else if (c.spd < target) c.spd = Math.min(target, c.spd + 7 * M * dt); else c.spd = Math.max(target, c.spd - 16 * M * dt);
            c.vx = c.dirX() * c.spd; c.vy = c.dirY() * c.spd;
            float ox = c.x, oy = c.y;
            c.move(map, c.vx * dt, c.vy * dt);
            carVsPoles(c);
            float mv = Math.abs(c.x - ox) + Math.abs(c.y - oy);
            if (c.stuckT <= 0 && c.spd > 3 * M && mv < c.spd * dt * 0.2f) { c.loose += dt; if (c.loose > 0.8f) { c.stuckT = 0.9f; c.loose = 0; c.a += (rf() - 0.5f); } }
            else c.loose = 0;
            // ram the player's car / run over Claude
            if (claude.active && hitsCar(c, claude, 2) && c.spd > 4 * M) {
                if (ramT <= 0) { knockDown(c); hurtPlayer(Math.min(35, c.spd / M * 1.2f)); ramT = 1; }
                stepPed(claude, c.vx * 0.04f, c.vy * 0.04f);
                if (c.mode == 2) { c.mode = 3; c.holdT = 1.5f; c.spd *= 0.5f; }
            }
        }
    }
    void updateHeli(float dt) {
        if (stars < 4 || lightImg == null) { heliOn = false; return; }
        float px = focusX(), py = focusY();
        if (!heliOn) { heliOn = true; hx = px + 300; hy = py - 260; hvx = hvy = 0; ha = G.heading(px - hx, py - hy); }
        float lx = car != null ? car.vx * 0.5f : 0, ly = car != null ? car.vy * 0.5f : 0;
        float tx = px + lx + G.sin(animT * 0.9f) * 34, ty = py + ly + G.cos(animT * 0.7f) * 22;      // sweeps around him
        float wx = (tx - hx) * 1.3f, wy = (ty - hy) * 1.3f, wl = (float) Math.sqrt(wx * wx + wy * wy), vmax = 30 * M;
        if (wl > vmax) { wx *= vmax / wl; wy *= vmax / wl; }
        float acc = 20 * M * dt; hvx += G.clamp(wx - hvx, -acc, acc); hvy += G.clamp(wy - hvy, -acc, acc);
        hx += hvx * dt; hy += hvy * dt;
        float want = hvx * hvx + hvy * hvy > 4 * M * M ? G.heading(hvx, hvy) : G.heading(px - hx, py - hy);
        ha += G.clamp(G.wrap(want - ha), -1.5f * dt, 1.5f * dt);
    }
    /** the chopper's shadow on the ground (drawn under everyone): a little behind the light, offset by the sun */
    void drawHeliShadow(Graphics g, int cx, int cy) {
        if (!heliOn) return;
        int f = G.frame(ha, 8), x = (int) (hx - G.sin(ha) * 40) + 40 - cx, y = (int) (hy + G.cos(ha) * 40) + 26 - cy;
        if (x < -rotW || y < -rotW || x > W + rotW || y > H + rotW) return;
        g.drawRegion(hShadow, f * hsW, 0, hsW, hsW, 0, x - hsW / 2, y - hsW / 2, TL);
        int rx = x + (int) (G.sin(ha) * hsW * 0.1f), ry = y - (int) (G.cos(ha) * hsW * 0.1f), rf_ = ((int) (animT * 28)) & 3;
        g.drawRegion(hRotor, rf_ * rotW, 0, rotW, rotW, 0, rx - rotW / 2, ry - rotW / 2, TL);
    }
    /** the searchlight (drawn over everyone) and, if the renders exist, the chopper itself up in the air */
    void drawHeli(Graphics g, int cx, int cy) {
        if (!heliOn) return;
        int x = (int) hx - cx, y = (int) hy - cy;
        if (x > -lightW && y > -lightH && x < W + lightW && y < H + lightH) g.drawImage(lightImg, x - lightW / 2, y - lightH / 2, TL);
        if (heliImg != null) {
            int f = G.frame(ha, 8), bx = (int) (hx - G.sin(ha) * 40) - cx - heliAX, by = (int) (hy + G.cos(ha) * 40) - 85 - cy - heliAY;
            g.drawRegion(heliImg, f * heliW, 0, heliW, heliH, 0, bx, by, TL);
            int mx = bx + heliAX, my = by + heliAY - heliH / 9; float ra = animT * 25;   // main rotor: two spinning 2 px blades (the model's own are hidden)
            g.setColor(0x303438);
            for (int k = 0; k < 2; k++) { float a = ra + k * G.PI * 0.5f; int dx = (int) (G.cos(a) * 50), dy = (int) (G.sin(a) * 32); g.drawLine(mx - dx, my - dy, mx + dx, my + dy); g.drawLine(mx - dx, my - dy + 1, mx + dx, my + dy + 1); }
        }
    }
    void relocate(Car c, float px, float py) {
        int n = nodeNear(px, py); if (n < 0) return;
        c.x = map.px[0][n]; c.y = map.py[0][n]; c.a = G.heading(px - c.x, py - c.y); c.spd = c.cruise * 0.5f;
        c.to = n; c.from = n; c.edge = -1; c.mode = 0; c.tx = c.x; c.ty = c.y; c.stuckT = 0; c.loose = 0; c.ramT = 0;
        if (!c.fits(map)) { c.a += G.PI * 0.5f; }
    }
    /** wave 4+: two cop cars parked across the road 40-65 m ahead of the player's car, two cops behind them */
    void updateRoadblock(float dt) {
        if (stars < 5 || car == null) return;
        rbT -= dt; if (rbT > 0) return;
        float sp = car.speed(); if (sp < 8 * M) return;
        rbT = 6;                                                   // try again soon if there was no good spot
        float ux = car.vx / sp, uy = car.vy / sp, px = car.x, py = car.y;
        short[] X = map.px[0], Y = map.py[0]; int best = -1; float bs = 1e9f;
        for (int i = 0; i < X.length; i++) {
            float dx = X[i] - px, dy = Y[i] - py, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 40 * M || d > 65 * M || onScreen(X[i], Y[i], 20)) continue;
            float al = (dx * ux + dy * uy) / d; if (al < 0.9f) continue;
            float sc = (1 - al) * 400 + Math.abs(d - 50 * M); if (sc < bs) { bs = sc; best = i; }
        }
        if (best < 0) return;
        int[] adj = map.adj[0][best]; float ex = ux, ey = uy, ba = -2;          // road direction: the edge most along his way
        for (int k = 0; k < adj.length; k++) {
            int e = adj[k], v = map.ea[0][e] == best ? map.eb[0][e] : map.ea[0][e];
            float dx = X[v] - X[best], dy = Y[v] - Y[best], d = (float) Math.sqrt(dx * dx + dy * dy); if (d < 1) continue;
            float al = Math.abs((dx * ux + dy * uy) / d); if (al > ba) { ba = al; ex = dx / d; ey = dy / d; }
        }
        float rx = -ey, ry = ex, cx = X[best], cy = Y[best]; int t = idx(carName, WV_CAR[stars]), made = 0;
        for (int s = -1; s <= 1; s += 2) {
            Car b = newCar(t); b.ai = false; b.cop = true; b.siren = true;
            b.x = cx + rx * (b.hl + 2) * s; b.y = cy + ry * (b.hl + 2) * s; b.a = G.heading(rx * s, ry * s);
            if (!b.fits(map)) continue;
            addParked(b); made++;
        }
        if (made == 0) return;
        if (FPS) System.out.println("RB " + (int) cx + "," + (int) cy + " made " + made + " r " + rx + "," + ry + " player " + (int) px + "," + (int) py);
        float bx = cx + ux * 30, by = cy + uy * 30;                // the officers stand behind their cars
        for (int k = 0; k < 2; k++) { if (copsAlive() >= WV_ALIVE[stars] + 2) break; Ped p = freePed(); if (p == null) break; p.x = bx + rx * (k == 0 ? -14 : 14); p.y = by + ry * (k == 0 ? -14 : 14); if (pedBlocked(p.x, p.y)) { p.x = bx; p.y = by; } makeCop(p, null); p.state = Ped.SHOOT; }
        rbT = 22 + rf() * 12;
    }
    void nextCopNode(Car c) {
        int node = c.to; if (node < 0) return;
        int[] adj = map.adj[0][node]; int best = -1; float bd = 1e9f;
        for (int k = 0; k < adj.length; k++) {
            int e = adj[k]; int v = map.ea[0][e] == node ? map.eb[0][e] : map.ea[0][e];
            if (e == c.edge && adj.length > 1) continue;
            float s = rD[v] + rf() * 40; if (s < bd) { bd = s; best = e; }
        }
        if (best < 0) { c.holdT = 1; return; }
        c.edge = best; c.from = node; c.to = map.ea[0][best] == node ? map.eb[0][best] : map.ea[0][best];
        float ax = map.px[0][c.from], ay = map.py[0][c.from], bx = map.px[0][c.to], by = map.py[0][c.to];
        float dx = bx - ax, dy = by - ay, d = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
        c.tx = bx - dy / d * 2.2f * M; c.ty = by + dx / d * 2.2f * M;
        if (map.solid(c.tx, c.ty)) { c.tx = bx; c.ty = by; }
    }
    /** cops arriving on foot: a pavement node 28-45 m away, off screen */
    void spawnFoot(int n) {
        float px = focusX(), py = focusY(); short[] X = map.px[1], Y = map.py[1]; int best = -1; float bs = 1e18f;
        for (int i = 0; i < X.length; i++) {
            float dx = X[i] - px, dy = Y[i] - py, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 28 * M || d > 45 * M || onScreen(X[i], Y[i], 30) || pedBlocked(X[i], Y[i])) continue;
            float sc = Math.abs(d - 34 * M) + rf() * 60; if (sc < bs) { bs = sc; best = i; }
        }
        if (best < 0) return;
        for (int k = 0; k < n; k++) {
            Ped p = freePed(); if (p == null) return;
            float x = X[best] + (k - n / 2) * 12, y = Y[best] + (k & 1) * 10;
            if (pedBlocked(x, y)) { x = X[best]; y = Y[best]; }
            p.x = x; p.y = y; makeCop(p, null);
        }
    }
    void makeCop(Ped p, Car c) {
        p.role = Ped.COP; p.type = idx(pedName, WV_PED[stars]); needPed(p.type); p.weapon = WV_WEAP[stars]; p.health = 100 + (stars - 2) * 15;
        p.active = true; p.farT = 0; p.state = Ped.CHASE; p.fireT = 0.4f + rf() * 0.6f; p.speed = (5.8f + rf() * 1.2f) * M; p.timer = 0; p.stuck = 0; p.moving = false; p.ride = c; p.hitT = 0;
    }
    void dismount(Car c) {
        float fx = c.dirX(), fy = c.dirY();
        int k = 0;
        while (c.crew > 0) {
            Ped p = freePed(); if (p == null) { c.crew = 0; break; }
            float s = (k & 1) == 0 ? 1 : -1, back = (k >> 1) * 14;
            p.x = c.x - fy * (c.hw + 8) * s - fx * back; p.y = c.y + fx * (c.hw + 8) * s - fy * back;
            if (pedBlocked(p.x, p.y)) { p.x = c.x - fx * (c.hl + 8 + back); p.y = c.y - fy * (c.hl + 8 + back); }
            makeCop(p, c);
            c.crew--; k++;
        }
        c.siren = false; c.spd = 0; c.vx = c.vy = 0;
    }
    Ped freePed() {
        for (int i = 1; i < peds.length; i++) if (!peds[i].active) return peds[i];
        // replace the civilian farthest away
        Ped best = null; float bd = -1;
        for (int i = 1; i < peds.length; i++) { Ped p = peds[i]; if (p.role == Ped.COP) continue; float dx = p.x - focusX(), dy = p.y - focusY(), d = dx * dx + dy * dy; if (d > bd) { bd = d; best = p; } }
        return best;
    }

    // ------------------------------------------------------------------ flow field to the player (cops on foot)
    void flowField() {
        int pcx = (int) claude.x >> 4, pcy = (int) claude.y >> 4;
        ffX0 = pcx - FF / 2; ffY0 = pcy - FF / 2;
        for (int i = 0; i < ffD.length; i++) ffD[i] = Short.MAX_VALUE;
        int h = 0, t = 0;
        int s = (FF / 2) * FF + FF / 2; ffD[s] = 0; ffQ[t++] = s;
        while (h < t) {
            int c = ffQ[h++], cx = c % FF, cy = c / FF; short d = (short) (ffD[c] + 1);
            for (int k = 0; k < 4; k++) {
                int nx = cx + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = cy + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (nx < 0 || ny < 0 || nx >= FF || ny >= FF) continue;
                int n = ny * FF + nx; if (ffD[n] <= d) continue;
                if (!pass16(ffX0 + nx, ffY0 + ny)) continue;
                ffD[n] = d; ffQ[t++] = n;
            }
        }
    }
    boolean pass16(int X, int Y) { return map.pass16(X, Y); }
    /** direction of steepest descent from (x,y); false if outside the field */
    boolean flowDir(float x, float y) {
        int cx = ((int) x >> 4) - ffX0, cy = ((int) y >> 4) - ffY0;
        if (cx < 1 || cy < 1 || cx >= FF - 1 || cy >= FF - 1) return false;
        int c = cy * FF + cx; int bd = ffD[c]; if (bd == Short.MAX_VALUE) return false;
        int bx = 0, by = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int n = (cy + dy) * FF + cx + dx; int d = ffD[n];
            if (dx != 0 && dy != 0 && (ffD[cy * FF + cx + dx] == Short.MAX_VALUE || ffD[(cy + dy) * FF + cx] == Short.MAX_VALUE)) continue;
            if (d < bd) { bd = d; bx = dx; by = dy; }
        }
        if (bx == 0 && by == 0) return false;
        float l = (float) Math.sqrt(bx * bx + by * by); pt[0] = bx / l; pt[1] = by / l; return true;
    }

    // ------------------------------------------------------------------ peds: civilians, triads, cops
    int copsN;
    void updatePeds(float dt) {
        boolean play = state == ST_PLAY; copsN = copsAlive();
        if (play && claude.active) { ffT -= dt; if (ffT <= 0) { ffT = 0.3f; flowField(); } }
        int civ = 0;
        for (int i = 1; i < peds.length; i++) if (peds[i].active && peds[i].role != Ped.COP) civ++;
        if (PED_MISTY >= 0 && !mistyDead && (mistyP == null || !mistyP.active || mistyP.role != Ped.MISTY)
            && Math.abs(mistyX - camX) < W * 1.2f && Math.abs(mistyY - camY) < H * 1.15f) {
            Ped p = freePed();
            if (p != null) { needPed(PED_MISTY); p.type = PED_MISTY; p.role = Ped.MISTY; p.x = mistyX; p.y = mistyY; p.a = G.PI; p.state = Ped.IDLE; p.active = true; p.moving = false; p.health = 60; p.hitT = 0; p.stuck = 0; p.speed = 0; p.edge = -1; mistyP = p; }
        }
        if (civ < 9 && rnd.nextInt(5) == 0) { Ped p = null; for (int i = 1; i < peds.length; i++) if (!peds[i].active) { p = peds[i]; break; } if (p != null) spawnPed(p); }
        float ps = car != null ? car.speed() : 0;
        float tx = focusX(), ty = focusY();
        for (int i = 0; i < peds.length; i++) {
            Ped p = peds[i]; if (!p.active) continue;
            if (p.hitT > 0) p.hitT -= dt;
            // the player's car and loose traffic run people over
            if (car != null && p.role != Ped.EIGHT && hitsCar(car, p, 3)) {
                if (ps > 2.5f * M) { kill(p, true); car.vx *= 0.92f; car.vy *= 0.92f; continue; }
                float dx = p.x - car.x, dy = p.y - car.y, d = (float) Math.sqrt(dx * dx + dy * dy); if (d > 0.01f) stepPed(p, dx / d * 2, dy / d * 2);
            }
            if (p.role == Ped.EIGHT) continue;
            if (p.role != Ped.COP && (Math.abs(p.x - camX) > W * 1.4f || Math.abs(p.y - camY) > H * 1.3f)) { p.active = false; continue; }
            if (p.role == Ped.MISTY) { updateMisty(p, dt); continue; }
            if (p.role == Ped.COP) { updateCop(p, dt, tx, ty); continue; }
            if (car != null && p.state == Ped.WALK && ps > 3 * M && near(p, car.x, car.y, 9 * M)) {
                float fx = car.dirX(), fy = car.dirY(), dx = p.x - car.x, dy = p.y - car.y, side = -dx * fy + dy * fx;
                p.state = Ped.FLEE; p.timer = 1.4f; p.a = G.heading(-fy * (side >= 0 ? 1 : -1), fx * (side >= 0 ? 1 : -1));
            }
            if (play && p.state == Ped.WALK && near(p, tx, ty, 18 * M) && (flashT > 0 || copsN > 0) && rnd.nextInt(18) == 0) { scare(p, tx, ty); p.timer = 2 + rf() * 2; }
            if (p.state == Ped.FLEE) {
                p.timer -= dt; float sp = 4.5f * M * dt;
                if (!stepPed(p, G.sin(p.a) * sp, -G.cos(p.a) * sp)) p.a += G.PI * 0.5f;
                p.bob += dt * 14; p.moving = true;
                if (p.timer <= 0) { p.state = Ped.WALK; retarget(p); }
                continue;
            }
            float dx = p.tx - p.x, dy = p.ty - p.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1.0f * M) { if (!nextPedEdge(p)) { p.active = false; continue; } dx = p.tx - p.x; dy = p.ty - p.y; d = (float) Math.sqrt(dx * dx + dy * dy); }
            if (d > 0.01f) {
                float s = p.speed * dt;
                if (!stepPed(p, dx / d * s, dy / d * s)) { p.stuck += dt; if (p.stuck > 2) { p.active = false; continue; } } else p.stuck = 0;
                p.a += G.clamp(G.wrap(G.heading(dx, dy) - p.a), -6 * dt, 6 * dt);
            }
            p.bob += dt * 8; p.moving = true;
        }
    }
    /** easter egg: Misty still waits at the hospital entrance for the driver Luigi promised her (8-Ball never made it) */
    int PED_MISTY = -1; Ped mistyP; boolean mistyDead; float mistyX = 1200, mistyY = 656, mistyTalkT; int mistyTalks;
    void updateMisty(Ped p, float dt) {
        p.moving = false; p.state = Ped.IDLE; mistyTalkT -= dt;
        float fx = focusX(), fy = focusY(), dx = fx - p.x, dy = fy - p.y, d2 = dx * dx + dy * dy, r = 4.5f * M + (car != null ? car.hl : 0);
        float want = d2 < 49 * M * M ? G.heading(dx, dy) : G.PI;          // looks at you when you're near, otherwise at the street
        p.a += G.clamp(G.wrap(want - p.a), -4 * dt, 4 * dt);
        if (state == ST_PLAY && d2 < r * r && (car == null || car.speed() < 3 * M) && mistyTalkT <= 0) {
            help = txt(mistyTalks == 0 ? "WI_MISTY1" : "WI_MISTY2"); helpT = 5; mistyTalks++; mistyTalkT = 25;
        }
    }
    void updateCop(Ped p, float dt, float tx, float ty) {
        if (state != ST_PLAY) { p.moving = false; return; }
        float dx = tx - p.x, dy = ty - p.y, d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d > 45 * M && !onScreen(p.x, p.y, 40)) { p.farT += dt; if (p.farT > 2.5f) { p.active = false; p.farT = 0; spawnT = Math.min(spawnT, 1); return; } } else p.farT = 0;
        boolean sg = p.weapon == W_SHOTGUN; float range = (p.weapon == W_PISTOL ? 16 : (sg ? 10 : 22)) * M;
        p.timer -= dt; p.fireT -= dt;
        if (p.timer <= 0) { p.timer = 0.2f + rf() * 0.15f; p.state = d < range && map.los(p.x, p.y - 12, tx, ty - (car != null ? 0 : 12)) ? Ped.SHOOT : Ped.CHASE; }
        if (p.state == Ped.SHOOT && (claude.active || car != null)) {
            p.a = G.heading(dx, dy); p.moving = false;
            if (d > (sg ? 4 : 6) * M && car == null) {           // advance while shooting (no cover, no mercy)
                float sp = p.speed * 0.55f * dt, sx = dx / d, sy = dy / d;
                if (flowDir(p.x, p.y)) { sx = pt[0]; sy = pt[1]; }
                if (stepPed(p, sx * sp, sy * sp)) { p.moving = true; p.bob += dt * p.speed * 0.55f * (1.33f / M); }
            } else if (car == null) {                 // close: strafe round Claude (and back off if right on top of him)
                p.stuck -= dt; if (p.stuck <= 0) { p.stuck = 1.0f + rf(); p.off = rf() < 0.5f ? 1 : -1; }
                float sp = p.speed * 0.5f * dt, sx = -dy / d * p.off, sy = dx / d * p.off;
                if (d < 3 * M) { sx -= dx / d; sy -= dy / d; }
                if (stepPed(p, sx * sp, sy * sp)) { p.moving = true; p.bob += dt * p.speed * 0.5f * (1.33f / M); } else p.off = -p.off;
            }
            if (p.fireT <= 0) {
                boolean auto = W_AUTO[p.weapon];
                p.fireT = auto ? (rf() < 0.25f ? 0.9f + rf() * 0.8f : 0.16f) : (sg ? 1.1f : 0.9f) + rf() * 0.6f;
                float tsp = car != null ? car.speed() / M : (claude.moving ? 3 : 0);
                float acc = (sg ? 0.55f : WV_ACC[stars]) * (1.15f - d / range * 0.6f) / (1 + tsp * 0.05f);
                p.hitT = 0; flashCop = p; flashCopT = 0.06f;
                if (onScreen(p.x, p.y, 16)) sfx(p.weapon);               // you hear the cops you can see
                if (rf() < acc) hurtPlayer(WV_COPDMG[stars]);

            }
            return;
        }
        // chase: flow field near the player, straight line + sliding elsewhere
        float sp = p.speed * dt, mx, my;
        if (car == null && flowDir(p.x, p.y)) { mx = pt[0]; my = pt[1]; }
        else { mx = dx / Math.max(d, 1); my = dy / Math.max(d, 1); }
        if (d < 2.5f * M) { p.moving = false; p.state = Ped.SHOOT; return; }
        p.a = G.heading(mx, my);
        if (!stepPed(p, mx * sp, my * sp)) {
            p.stuck += dt; float side = (p.hashCode() & 1) == 0 ? 1.4f : -1.4f;
            stepPed(p, G.sin(p.a + side) * sp, -G.cos(p.a + side) * sp);
        } else p.stuck = 0;
        p.bob += dt * p.speed * (1.33f / M); p.moving = true;
        pedVsPoles(p);
        // far away and stuck for long: respawn closer (off screen), GTA style
        if (p.stuck > 4 && !onScreen(p.x, p.y, 20)) { p.active = false; }
    }
    Ped flashCop; float flashCopT;
    boolean onScreen(float x, float y, int m) { return x > map.camX - m && x < map.camX + W + m && y > map.camY - m && y < map.camY + H + m; }
    void spawnPed(Ped p) {
        int g = 1, ne = map.ea[g].length; if (ne == 0) return;
        int e = rnd.nextInt(ne); boolean fwd = rnd.nextInt(2) == 0;
        int a = fwd ? map.ea[g][e] : map.eb[g][e], b = fwd ? map.eb[g][e] : map.ea[g][e];
        float t = rf(), x = map.px[g][a] + (map.px[g][b] - map.px[g][a]) * t, y = map.py[g][a] + (map.py[g][b] - map.py[g][a]) * t;
        float cdx = x - (map.camX + W / 2), cdy = y - (map.camY + H / 2);
        if (Math.abs(cdx) < W / 2 + 12 && Math.abs(cdy) < H / 2 + 12) return;
        if (Math.abs(cdx) > W * 1.3f || Math.abs(cdy) > H * 1.2f) return;
        if (pedBlocked(x, y)) return;
        p.x = x; p.y = y; p.edge = e; p.from = a; p.to = b; p.off = (rf() - 0.5f) * 1.6f * M;
        boolean triad = rnd.nextInt(3) == 0;
        p.type = triad ? PED_TRIAD : civTypes[rnd.nextInt(civTypes.length)]; p.role = triad ? Ped.TRIAD : Ped.CIV;
        p.speed = (1.1f + rf() * 0.6f) * M; p.state = Ped.WALK; p.active = true; p.moving = true; p.bob = rf() * 6; p.health = 100; p.hitT = 0; p.stuck = 0;
        pedTarget(p); p.a = G.heading(p.tx - p.x, p.ty - p.y);
        if (state == ST_PLAY && stars >= 2 && rnd.nextInt(3) == 0) { scare(p, focusX(), focusY()); p.timer = 2.5f + rf() * 2; }   // already running from the shooting
    }
    void pedTarget(Ped p) {
        float ax = map.px[1][p.from], ay = map.py[1][p.from], bx = map.px[1][p.to], by = map.py[1][p.to];
        float dx = bx - ax, dy = by - ay, d = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
        p.tx = bx - dy / d * p.off; p.ty = by + dx / d * p.off;
    }
    boolean nextPedEdge(Ped p) {
        int node = p.to; int[] adj = map.adj[1][node]; if (adj.length == 0) return false;
        int e = adj[rnd.nextInt(adj.length)];
        if (e == p.edge && adj.length > 1) e = adj[(indexIn(adj, e) + 1 + rnd.nextInt(adj.length - 1)) % adj.length];
        p.edge = e; p.from = node; p.to = map.ea[1][e] == node ? map.eb[1][e] : map.ea[1][e]; pedTarget(p); return true;
    }
    static int indexIn(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return 0; }
    void retarget(Ped p) {
        int best = nearestNode(1, p.x, p.y);
        if (best < 0 || map.adj[1][best].length == 0) { p.active = false; return; }
        p.to = best; p.edge = -1; p.tx = map.px[1][best]; p.ty = map.py[1][best];
    }
    boolean hitsCar(Car c, Mob p, float margin) {
        float dx = p.x - c.x, dy = p.y - c.y, fx = c.dirX(), fy = c.dirY();
        float al = dx * fx + dy * fy, sd = -dx * fy + dy * fx;
        return Math.abs(al) < c.hl + margin && Math.abs(sd) < c.hw + margin;
    }
    boolean ahead(Car c, Mob o, float len, float half) {
        float fx = c.dirX(), fy = c.dirY(), dx = o.x - c.x, dy = o.y - c.y, al = dx * fx + dy * fy, sd = -dx * fy + dy * fx;
        return al > 0 && al < len && Math.abs(sd) < half;
    }

    // ------------------------------------------------------------------ traffic
    void updateTraffic(float dt) {
        if (state == ST_PLAY && rnd.nextInt(12) == 0) spawnCar();
        for (int i = 0; i < traffic.length; i++) {
            Car c = traffic[i]; if (c == null) continue;
            if (Math.abs(c.x - camX) > W * 1.6f || Math.abs(c.y - camY) > H * 1.5f || c.wreck) { if (c.wreck) addParked(c); traffic[i] = null; continue; }
            if (c.fireT > 0) { c.fireT -= dt; if (c.fireT <= 0) explode(c); }
            if (claude.active && claude.knockT <= 0 && ramT <= 0 && c.speed() > 4 * M && hitsCar(c, claude, 2)) { knockDown(c); hurtPlayer(Math.min(25, c.speed() / M)); ramT = 1; }
            if (c.loose > 0) {
                c.loose -= dt; float f = Math.max(0, 1 - 2.2f * dt); c.vx *= f; c.vy *= f; c.w *= f; c.a += c.w * dt; c.move(map, c.vx * dt, c.vy * dt);
                if (c.loose <= 0) { int b = nearestNode(0, c.x, c.y); if (b < 0) { traffic[i] = null; continue; } c.to = b; c.from = b; c.edge = -1; c.tx = map.px[0][b]; c.ty = map.py[0][b]; c.spd = 0; }
                continue;
            }
            float dx = c.tx - c.x, dy = c.ty - c.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 2.5f * M) { if (!nextEdge(c)) { traffic[i] = null; continue; } dx = c.tx - c.x; dy = c.ty - c.y; }
            float turn = G.wrap(G.heading(dx, dy) - c.a), maxT = 2.2f * dt * Math.max(0.3f, Math.min(1, c.spd / (6 * M)));
            c.a += G.clamp(turn, -maxT, maxT);
            float target = Math.abs(turn) > 0.5f ? c.cruise * 0.45f : c.cruise;
            if (blocked(c) || c == jackCar) target = 0;
            if (c.spd < target) c.spd = Math.min(target, c.spd + 5 * M * dt); else c.spd = Math.max(target, c.spd - 14 * M * dt);
            c.vx = c.dirX() * c.spd; c.vy = c.dirY() * c.spd; c.x += c.vx * dt; c.y += c.vy * dt;
        }
    }
    boolean dirOk(int e, boolean fwd) { if (map.en[0][e] != 0) return false; int l = map.el[0][e], r = map.er[0][e]; if (l == 0 && r == 0) return true; return fwd ? r > 0 : l > 0; }
    void laneTarget(Car c) {
        float ax = map.px[0][c.from], ay = map.py[0][c.from], bx = map.px[0][c.to], by = map.py[0][c.to];
        float dx = bx - ax, dy = by - ay, d = Math.max(0.01f, (float) Math.sqrt(dx * dx + dy * dy));
        c.tx = bx - dy / d * 2.4f * M; c.ty = by + dx / d * 2.4f * M;
    }
    boolean nextEdge(Car c) {
        int node = c.to; int[] adj = map.adj[0][node]; int n = 0; int[] cand = tmpI;
        for (int i = 0; i < adj.length && n < cand.length; i++) { int e = adj[i]; if (e == c.edge) continue; if (dirOk(e, map.ea[0][e] == node)) cand[n++] = e; }
        if (n == 0) return false;
        int e = cand[rnd.nextInt(n)]; c.edge = e; c.from = node; c.to = map.ea[0][e] == node ? map.eb[0][e] : map.ea[0][e]; laneTarget(c); return true;
    }
    void spawnCar() {
        int slot = -1; for (int i = 0; i < traffic.length; i++) if (traffic[i] == null) { slot = i; break; }
        if (slot < 0) return;
        int ne = map.ea[0].length; if (ne == 0) return;
        int e = rnd.nextInt(ne); boolean fwd = rnd.nextInt(2) == 0;
        if (!dirOk(e, fwd)) fwd = !fwd; if (!dirOk(e, fwd)) return;
        int a = fwd ? map.ea[0][e] : map.eb[0][e], b = fwd ? map.eb[0][e] : map.ea[0][e];
        float ax = map.px[0][a], ay = map.py[0][a], bx = map.px[0][b], by = map.py[0][b], dx = bx - ax, dy = by - ay, d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < 20) return;
        float t = 0.2f + rf() * 0.5f, x = ax + dx * t - dy / d * 2.4f * M, y = ay + dy * t + dx / d * 2.4f * M;
        float cdx = x - (map.camX + W / 2), cdy = y - (map.camY + H / 2);
        if (Math.abs(cdx) < W / 2 + 40 && Math.abs(cdy) < H / 2 + 40) return;
        if (Math.abs(cdx) > W * 1.4f || Math.abs(cdy) > H * 1.3f) return;
        for (int i = 0; i < traffic.length; i++) { Car o = traffic[i]; if (o != null && Math.abs(o.x - x) + Math.abs(o.y - y) < 12 * M) return; }
        Car c = newCar(trafficTypes[rnd.nextInt(trafficTypes.length)]);
        c.x = x; c.y = y; c.a = G.heading(dx, dy); c.edge = e; c.from = a; c.to = b; c.cruise = (9 + rf() * 5) * M; c.spd = c.cruise * 0.7f;
        laneTarget(c); traffic[slot] = c;
    }
    boolean blocked(Car c) {
        float look = c.hl + 4 * M + c.spd * 0.6f;
        if (car != null && ahead(c, car, look + car.hl, c.hw + car.hw + 0.3f * M)) return true;
        for (int i = 0; i < parked.length; i++) if (parked[i] != null && ahead(c, parked[i], look + parked[i].hl, c.hw + parked[i].hw)) return true;
        for (int i = 0; i < copCars.length; i++) if (copCars[i] != null && ahead(c, copCars[i], look + copCars[i].hl, c.hw + copCars[i].hw)) return true;
        for (int i = 0; i < traffic.length; i++) { Car o = traffic[i]; if (o == null || o == c) continue; if (ahead(c, o, look + o.hl, c.hw + o.hw) && (c.dirX() * o.dirX() + c.dirY() * o.dirY() > -0.3f || i < indexOfT(c))) return true; }
        for (int i = 0; i < peds.length; i++) { Ped p = peds[i]; if (p.active && ahead(c, p, c.hl + 2.5f * M, c.hw + M)) return true; }
        if (claude.active && ahead(c, claude, c.hl + 3 * M, c.hw + M)) return true;
        return false;
    }
    int indexOfT(Car c) { for (int i = 0; i < traffic.length; i++) if (traffic[i] == c) return i; return -1; }
    void updateParked(float dt) {
        for (int i = 0; i < parked.length; i++) {
            Car c = parked[i]; if (c == null || c == car) continue;
            if (c.vx == 0 && c.vy == 0 && c.w == 0) continue;
            float f = Math.max(0, 1 - 3f * dt); c.vx *= f; c.vy *= f; c.w *= f;
            if (c.speed() < 0.2f * M) { c.vx = c.vy = 0; c.w = 0; continue; }
            c.a += c.w * dt; c.move(map, c.vx * dt, c.vy * dt);
        }
    }
    void collideCars() {
        for (int t = 0; t < 3; t++) {
            Car[] arr = t == 0 ? parked : (t == 1 ? traffic : copCars);
            for (int i = 0; i < arr.length; i++) {
                Car c = arr[i]; if (c == null || c == car) continue;
                if (car != null) collide(car, c);
                if (t == 2) { for (int k = 0; k < parked.length; k++) if (parked[k] != null && parked[k] != car) collide(c, parked[k]);
                              for (int k = 0; k < traffic.length; k++) if (traffic[k] != null) collide(c, traffic[k]);
                              for (int k = i + 1; k < copCars.length; k++) if (copCars[k] != null) collide(c, copCars[k]); }
            }
        }
    }
    void collide(Car a, Car b) {
        float dx0 = b.x - a.x, dy0 = b.y - a.y, lim = a.hl + b.hl + 2;
        if (dx0 * dx0 + dy0 * dy0 > lim * lim) return;
        float best = 0, nx = 0, ny = 0, hx = 0, hy = 0;
        for (int i = -1; i <= 1; i += 2) for (int j = -1; j <= 1; j += 2) {
            float ax = a.x + a.dirX() * (a.hl - a.hw) * i, ay = a.y + a.dirY() * (a.hl - a.hw) * i;
            float bx = b.x + b.dirX() * (b.hl - b.hw) * j, by = b.y + b.dirY() * (b.hl - b.hw) * j;
            float dx = ax - bx, dy = ay - by, d = (float) Math.sqrt(dx * dx + dy * dy), pen = a.hw + b.hw - d;
            if (pen > best && d > 0.001f) { best = pen; nx = dx / d; ny = dy / d; hx = bx + nx * b.hw; hy = by + ny * b.hw; }
        }
        if (best <= 0) return;
        boolean la = a.locked, lb = b.locked; if (la && lb) return;
        float rv = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny;
        if (rv < 0) {
            float j = -1.3f * rv / (la || lb ? 1 : 2);
            if (!la) { a.vx += j * nx; a.vy += j * ny; } if (!lb) { b.vx -= j * nx; b.vy -= j * ny; }
            if (-rv > 2 * M) { float dm = -rv / M * (a.cop && b.cop ? 0.1f : 0.35f); if (!la) a.hurt(dm); if (!lb) b.hurt(dm);
                boolean mine = a == car || b == car;
                if (mine) { vibrate(60); if (b.cop || a.cop) hurtPlayer(0); }
                if (mine ? -rv > 2.5f * M : -rv > (a.cop && b.cop ? 9 : 6) * M && vis(hx, hy)) sfx(SFX_CRASH);   // cop cars jostle a lot: only a real hit is heard
                if (vis(hx, hy)) { sparks(hx, hy, Math.min(10, 3 + (int) (-rv / (3 * M))), Math.min(1.6f, 0.5f - rv / (12 * M))); } }
            if (b.ai && !b.cop) { b.loose = 1.6f + rf(); b.w += (rf() - 0.5f) * (-rv) / (4 * M); }
            if (a.ai && !a.cop) { a.loose = 1.6f + rf(); a.w += (rf() - 0.5f) * (-rv) / (4 * M); }
            if (b.cop) { b.spd = Math.max(-2 * M, b.spd * 0.4f); }
            if (!b.ai && b != car && !lb) b.w += (rf() - 0.5f) * (-rv) / (6 * M);
            for (int k = 0; k < 2; k++) { Car c = k == 0 ? a : b; if (c.damage >= 100 && c.fireT <= 0 && !c.wreck && !c.locked) c.fireT = 3.5f; }
        }
        if (la) b.move(map, -nx * best, -ny * best); else if (lb) a.move(map, nx * best, ny * best);
        else { a.move(map, nx * best / 2, ny * best / 2); b.move(map, -nx * best / 2, -ny * best / 2); }
    }

    // ------------------------------------------------------------------ pickups (from wave 2 on, random each run)
    void newPickups() {
        for (int i = 0; i < nPk; i++) pkW[i] = -1;
        if (nPk == 0 || stars < 3) return;
        int[] pool = stars >= 5 ? new int[]{W_AK, W_M16, W_SHOTGUN, W_UZI} : (stars == 4 ? new int[]{W_UZI, W_SHOTGUN, W_AK} : new int[]{W_UZI, W_SHOTGUN});
        int a = rnd.nextInt(nPk), b = nPk > 1 ? (a + 1 + rnd.nextInt(nPk - 1)) % nPk : a;
        int wa = pool[rnd.nextInt(pool.length)], wb = pool[rnd.nextInt(pool.length)];
        if (wb == wa && pool.length > 1) wb = pool[(indexIn(pool, wa) + 1) % pool.length];
        pkW[a] = wa; pkW[b] = wb;
        helpNext = txt("WI_GUNS");
    }
    void updatePickups(float dt) {
        for (int i = 0; i < nPk; i++) {
            if (pkW[i] < 0) continue;
            if (claude.active && claude.knockT <= 0 && near(claude, pkX[i], pkY[i], 16)) {
                int w = pkW[i]; have[w] = true; ammo[w] += W_CLIP[w]; weapon = w;
                popup = WN[w].toUpperCase() + " +" + W_CLIP[w]; popupT = 1.8f;
               
                for (int k = 0; k < nPk; k++) pkW[k] = -1;          // the other one is gone until the next wave
            }
        }
    }

    // ------------------------------------------------------------------ camera
    void camTo(float x, float y, float dt, float k) { float f = Math.min(1, dt * k); camX += (x - camX) * f; camY += (y - camY) * f; }
    void followCam(float dt) {
        float lx = 0, ly = 0;
        if (car != null) { lx = G.clamp(car.vx * 0.4f, -W * 0.3f, W * 0.3f); ly = G.clamp(car.vy * 0.4f, -H * 0.3f, H * 0.3f); }
        camTo(focusX() + lx, focusY() + ly - 16, dt, car != null ? 3 : 4);
    }

    // ------------------------------------------------------------------ render
    int[] dk = new int[128], dty = new int[128], dix = new int[128]; int dn; int[] ovI = new int[48]; int ovN;
    void add(int key, int type, int i) { if (dn < dk.length) { dk[dn] = key; dty[dn] = type; dix[dn] = i; dn++; } }
    static final int D_PROP = 0, D_PED = 1, D_TRAF = 2, D_COPC = 3, D_PARK = 4, D_CAR = 5, D_PICK = 6, D_FIRE = 7, D_CLAUDE = 8;
    boolean playerCovered;
    int bx, by, bw, bh;

    void render(Graphics g) {
        g.setClip(0, 0, W, H);
        if (state == ST_LOAD || state == ST_MENU || state == ST_CTRL || state == ST_SND) { renderMenu(g); return; }
        int sx = 0, sy = 0;
        if (shakeT > 0) { sx = rnd.nextInt(7) - 3; sy = rnd.nextInt(7) - 3; }
        map.camX = map.clampX((int) camX - W / 2 + sx); map.camY = map.clampY((int) camY - H / 2 + sy);
        int cx = map.camX, cy = map.camY;
        float vx = car != null ? car.vx : 0, vy = car != null ? car.vy : 0;
        map.draw(g, vx, vy, focusX() + vx, focusY() + vy);
        tB = System.currentTimeMillis(); acM += tB - tA; tA = tB;
        // bodies on the ground
        for (int i = 0; i < Math.min(deadN, deadX.length); i++) {
            int t = deadT[i], w = pdW[t], h = pdH[t], x = (int) deadX[i] - pdA[t], y = (int) deadY[i] - h / 2;
            if (x - cx > W || y - cy > H || x + w < cx || y + h < cy || pedDead[t] == null) continue;
            g.drawRegion(pedDead[t], deadF[i] * w, 0, w, h, 0, x - cx, y - cy, TL);
            map.cover(g, x, y, x + w, y + h, (int) deadY[i] + 2);
        }
        drawTyreSmoke(g, cx, cy);
        drawHeliShadow(g, cx, cy);
        dn = 0;
        ovN = 0;
        for (int i = 0; i < map.nProps; i++) {
            if (map.pDown[i]) continue;
            int im = map.pImg[i], px = map.pX[i], py = map.pY[i];
            if (px > cx + W || py > cy + H || px + map.piW[im] < cx || py + map.piH[im] < cy) continue;
            if (map.pOv[i] && ovN < ovI.length) ovI[ovN++] = i;
            if (map.pFoot[i] < cy) continue;
            add(map.pFoot[i], D_PROP, i);
        }
        for (int i = 0; i < peds.length; i++) if (peds[i].active && vis(peds[i].x, peds[i].y)) add(pedKey(peds[i]), D_PED, i);
        if (claude.active) add(pedKey(claude), D_CLAUDE, 0);
        for (int i = 0; i < traffic.length; i++) if (traffic[i] != null && vis(traffic[i].x, traffic[i].y)) add(traffic[i].foot(), D_TRAF, i);
        for (int i = 0; i < copCars.length; i++) if (copCars[i] != null && vis(copCars[i].x, copCars[i].y)) add(copCars[i].foot(), D_COPC, i);
        for (int i = 0; i < parked.length; i++) if (parked[i] != null && parked[i] != car && vis(parked[i].x, parked[i].y)) add(parked[i].foot(), D_PARK, i);
        if (car != null) add(car.foot(), D_CAR, 0);
        for (int i = 0; i < nPk; i++) if (pkW[i] >= 0 && vis(pkX[i], pkY[i])) add(pkY[i], D_PICK, i);
        for (int i = 0; i < nFire; i++) if (vis(fireX[i], fireY[i])) add((int) fireY[i], D_FIRE, i);
        for (int i = 1; i < dn; i++) {
            int k = dk[i], t = dty[i], x = dix[i], j = i - 1;
            while (j >= 0 && dk[j] > k) { dk[j + 1] = dk[j]; dty[j + 1] = dty[j]; dix[j + 1] = dix[j]; j--; }
            dk[j + 1] = k; dty[j + 1] = t; dix[j + 1] = x;
        }
        playerCovered = false;
        for (int i = 0; i < dn; i++) {
            int t = dty[i], ix = dix[i]; bw = 0;
            switch (t) {
                case D_PROP: { int im = map.pImg[ix]; Image pi = map.propImg(im); if (pi != null) g.drawImage(pi, map.pX[ix] - cx, map.pY[ix] - cy, TL); bx = map.pX[ix]; by = map.pY[ix]; bw = map.piW[im]; bh = map.piH[im]; break; }
                case D_PED: drawPed(g, peds[ix], cx, cy); break;
                case D_CLAUDE: drawPed(g, claude, cx, cy); break;
                case D_TRAF: drawCar(g, traffic[ix], cx, cy); break;
                case D_COPC: drawCar(g, copCars[ix], cx, cy); break;
                case D_PARK: drawCar(g, parked[ix], cx, cy); break;
                case D_CAR: drawCar(g, car, cx, cy); break;
                case D_PICK: {
                    int w = pkW[ix]; Image k = kIcon[w]; int bob = (int) (G.sin(animT * 4 + ix) * 3);
                    bx = pkX[ix] - 11; by = pkY[ix] - 26 + bob; bw = 22; bh = 22;
                    g.setColor(0x80ff60); g.drawArc(pkX[ix] - 9 - cx, pkY[ix] - 4 - cy, 18, 8, 0, 360);
                    g.drawImage(k, bx - cx, by - cy, TL); break;
                }
                case D_FIRE: {
                    int fr = (int) (animT * 12) + ix * 3, x = (int) fireX[ix], y = (int) fireY[ix];
                    if (fireT[ix] == 4) { bw = fireW; bh = fireH; bx = x - fireW / 2; by = y - fireH + 6; g.drawRegion(fireImg, (fr % fireN) * fireW, 0, fireW, fireH, 0, bx - cx, by - cy, TL); }
                    else { bw = smokeW; bh = smokeH; bx = x - smokeW / 2; by = y - smokeH - 26; g.drawRegion(smokeImg, ((fr / 2) % smokeN) * smokeW, 0, smokeW, smokeH, 0, bx - cx, by - cy, TL); }
                    break;
                }
            }
            if (bw > 0) {
                map.cover(g, bx, by, bx + bw, by + bh, dk[i]);
                if ((t == D_CLAUDE || t == D_CAR) && map.covered) playerCovered = true;
            }
        }
        // shots
        if (flashT > 0 && claude.active) {
            g.setColor(0xfff0a0); int mx = (int) (claude.x + claude.dirX() * 12) - cx, my = (int) (claude.y - 14 + claude.dirY() * 8) - cy;
            g.fillRect(mx - 2, my - 2, 5, 5);
            if (weapon != W_FIST) { g.setColor(aimHit ? 0xffe080 : 0xa09060); g.drawLine(mx, my, (int) aimX - cx, (int) aimY - cy); }
        }
        if (flashCopT > 0 && flashCop != null && flashCop.active) {
            flashCopT -= 0.033f; g.setColor(0xffd060);
            g.fillRect((int) (flashCop.x + flashCop.dirX() * 10) - cx - 2, (int) (flashCop.y - 14 + flashCop.dirY() * 7) - cy - 2, 4, 4);
        }
        for (int k = 0; k < ovN; k++) {      // the part of an overhead prop below its base line is above everyone's head
            int i = ovI[k], im = map.pImg[i], px = map.pX[i] - cx, top = map.pFoot[i] + 1 - cy, bot = map.pY[i] + map.piH[im] - cy;
            if (bot <= top || top >= H || bot <= 0) continue;
            Image pi = map.propImg(im); if (pi == null) continue;
            g.setClip(px, Math.max(0, top), map.piW[im], Math.min(H, bot) - Math.max(0, top)); g.drawImage(pi, px, map.pY[i] - cy, TL);
        }
        g.setClip(0, 0, W, H);
        drawHeli(g, cx, cy);
        drawBooms(g, cx, cy);
        drawSparks(g, cx, cy);
        if (aimShowT > 0 && claude.active && state == ST_PLAY) {
            int col = aimHit && flashT > 0 ? 0xc0ffc0 : 0x30ff30;
            if (aimShowP != null) { if (aimShowP.active) bracket(g, (int) aimShowP.x - cx, (int) aimShowP.y - 13 - cy, 9, 15, col); }
            else if (aimShowC != null) bracket(g, (int) aimShowC.x - cx, (int) aimShowC.y - 8 - cy, (int) aimShowC.hw + 8, (int) (aimShowC.hw * 0.8f) + 6, col);
            else if (weapon != W_FIST) bracket(g, (int) aimX - cx, (int) aimY - 10 - cy, 6, 6, 0x20a020);
        }
        if (jackCar != null && claude.active && state == ST_PLAY) {   // carjack progress over Claude's head
            int x = (int) claude.x - cx - 16, y = (int) claude.y - 40 - cy, f = (int) (30 * Math.min(1, jackT / jackDur));
            g.setColor(0); g.fillRect(x - 1, y - 1, 34, 7); g.setColor(0x303830); g.fillRect(x, y, 32, 5);
            g.setColor(0x40e040); g.fillRect(x + 1, y + 1, f, 3);
        }
        // markers drawn over everything: pickups and the player when hidden
        for (int i = 0; i < nPk; i++) if (pkW[i] >= 0 && vis(pkX[i], pkY[i])) {
            int x = pkX[i] - cx, y = pkY[i] - 40 - cy + (int) (G.sin(animT * 5) * 3);
            g.setColor(0x40ff40); g.fillTriangle(x - 5, y - 6, x + 5, y - 6, x, y + 1);
        }
        if (playerCovered && state == ST_PLAY) {
            int x = (int) focusX() - cx, y = (int) focusY() - (car != null ? 34 : 38) - cy;
            g.setColor(0); g.fillTriangle(x - 7, y - 8, x + 7, y - 8, x, y + 2);
            g.setColor(0xffd040); g.fillTriangle(x - 5, y - 7, x + 5, y - 7, x, y);
        }
        tB = System.currentTimeMillis(); acS += tB - tA; tA = tB;
        if (state == ST_PLAY || state == ST_OVER) renderHud(g);
        if (help != null && dlgText == null) { if (state == ST_PLAY) boxBottom(g, help, 78, W - 82); else box(g, help, 4, H - 70, W - 8); }
        if (dlgText != null) renderDialogue(g);
        if (popupT > 0 && popup != null) g.drawImage(tc(TC_POP, popup, fs, 0x80ff80, true), W / 2, H / 2 - 50, Graphics.HCENTER | Graphics.BOTTOM);
        if (state == ST_INTRO && phase == 0 && stT < 4) g.drawImage(tc(TC_TITLE, txt("WI_TITLE"), fb, 0xe8c070, true), W - 6, H - 60, Graphics.RIGHT | Graphics.BOTTOM);
        if (state == ST_INTRO && phase < 2 && ((int) (animT * 2) & 1) == 0) g.drawImage(tc(TC_SKIP, "# to skip", fs, 0xe0e0e0, false), W - 4, H - 4, Graphics.RIGHT | Graphics.BOTTOM);
        if (state == ST_OVER) renderOver(g);
        tB = System.currentTimeMillis(); acH += tB - tA; tA = tB;
        if (paused) {
            g.setColor(0); g.fillRect(W / 2 - 80, H / 2 - 30, 160, 60); g.setColor(0xffffff); g.drawRect(W / 2 - 80, H / 2 - 30, 160, 60);
            fb.draw(g, "PAUSED", W / 2, H / 2 - 26, Graphics.HCENTER | Graphics.TOP, 0xffffff);
            fs.draw(g, "5: resume   #: main menu", W / 2, H / 2 + 0, Graphics.HCENTER | Graphics.TOP, 0xffffff);
            fs.draw(g, "*: sound " + (sound ? "ON" : "OFF"), W / 2, H / 2 + 1 + fs.h, Graphics.HCENTER | Graphics.TOP, 0xffffff);
            if (sndErr != null) { g.setColor(0xff8080); g.setFont(fSmall); g.drawString(sndErr.length() > 38 ? sndErr.substring(0, 38) : sndErr, W / 2, H / 2 + 34, Graphics.HCENTER | Graphics.TOP); }
        }
        if (FPS) {
            int lh = fSmall.getHeight();
            if (profI == null || profFor != prof) { profFor = prof; if (profI == null) profI = Image.createImage(W, 2 * lh);
                Graphics pg = profI.getGraphics(); pg.setFont(fSmall); pg.setColor(0); pg.fillRect(0, 0, W, 2 * lh); pg.setColor(0xffff60);
                pg.drawString(fpsS, 2, 0, TL); pg.drawString(prof, 2, lh, TL); }
            g.drawImage(profI, 0, H - 2 * lh - 74, TL);
        }
    }
    /** [ ] : two brackets, open at the top and bottom middle */
    void bracket(Graphics g, int x, int y, int hw, int hh, int col) {
        for (int k = 0; k < 2; k++) {
            int o = k == 0 ? 1 : 0; g.setColor(k == 0 ? 0 : col);
            int l = x - hw + o, r = x + hw - 2 + o, t = y - hh + o, b = y + hh - 2 + o, tk = Math.max(3, hw / 2);
            g.fillRect(l, t, 2, hh * 2); g.fillRect(l, t, tk, 2); g.fillRect(l, b, tk, 2);
            g.fillRect(r, t, 2, hh * 2); g.fillRect(r - tk + 2, t, tk, 2); g.fillRect(r - tk + 2, b, tk, 2);
        }
    }
    Image profI; String profFor;
    /** draw-order key of a ped: its foot, pushed after a car it stands in front of (south / beside) and before one it is behind */
    int pedKey(Ped p) {
        int k = (int) p.y;
        for (int t = 0; t < 4; t++) {
            Car[] arr = t == 0 ? parked : (t == 1 ? traffic : copCars); int n = t == 3 ? 1 : arr.length;
            for (int i = 0; i < n; i++) {
                Car c = t == 3 ? car : arr[i]; if (c == null || (t == 0 && c == car)) continue;
                float dx = p.x - c.x, dy = p.y - c.y, r = c.hl + 26;
                if (dx * dx + dy * dy > r * r) continue;
                float fx = c.dirX(), fy = c.dirY(), al = dx * fx + dy * fy, sd = -dx * fy + dy * fx;
                float ny = Math.abs(al) - c.hl > Math.abs(sd) - c.hw ? (al >= 0 ? fy : -fy) : (sd >= 0 ? fx : -fx);
                int ck = c.foot();
                if (ny > -0.35f) { if (k <= ck) k = ck + 1; } else if (k >= ck) k = ck - 1;
            }
        }
        return k;
    }
    boolean vis(float x, float y) { return x > map.camX - 120 && x < map.camX + W + 120 && y > map.camY - 60 && y < map.camY + H + 140; }

    void drawCar(Graphics g, Car c, int cx, int cy) {
        int t = c.type, f = G.frame(c.a, 16), cw = carCW[t], ch = carCH[t];
        bx = (int) c.x - carAnc[t][f * 2]; by = (int) c.y - carAnc[t][f * 2 + 1]; bw = cw; bh = ch;
        if (carImg[t] == null) needCar(t);
        Image ci = c.wreck && c.damage >= 200 ? burnt(t) : carImg[t]; if (ci == null) ci = carImg[t];
        if (ci != null) g.drawRegion(ci, f * cw, 0, cw, ch, 0, bx - cx, by - cy, TL);
        if (c.siren) {
            boolean ph = ((int) (animT * 6) & 1) == 0; int x = (int) c.x - cx, y = (int) c.y - cy - 14;
            g.setColor(ph ? 0xff2020 : 0x2060ff); g.fillRect(x - 6, y - 2, 6, 4); g.setColor(ph ? 0x2060ff : 0xff2020); g.fillRect(x, y - 2, 6, 4);
        }
        if (c.damage > 55 || c.fireT > 0 || c.wreck) {   // fire + smoke come out of the engine (front of the car)
            int fr = (int) (animT * 10) + t, x = (int) (c.x + c.dirX() * c.hl * 0.45f), y = (int) (c.y + c.dirY() * c.hl * 0.45f) - 4;
            if (c.fireT > 0 || c.wreck && c.damage >= 200 && animT - c.wreckAt < 7) g.drawRegion(fireImg, (fr % fireN) * fireW, 0, fireW, fireH, 0, x - fireW / 2 - cx, y - fireH + 8 - cy, TL);
            g.drawRegion(smokeImg, ((fr / 2) % smokeN) * smokeW, 0, smokeW, smokeH, 0, x - smokeW / 2 - cx, y - smokeH + 4 - cy, TL);
        }
    }
    void drawPed(Graphics g, Ped p, int cx, int cy) {
        int t = p.type, d = G.frame(p.a, 8), x = (int) p.x, y = (int) p.y;
        if (pedWalk[t] == null) { needPed(t); if (pedWalk[t] == null) return; }
        if (p == claude && p.knockT > 0) {
            int w = pdW[t], h = pdH[t]; bx = x - pdA[t]; by = y - h / 2 - 4; bw = w; bh = h;
            g.drawRegion(pedDead[t], deadPose * w, 0, w, h, 0, bx - cx, by - cy, TL);
            return;
        }
        if (p == claude && atkT > 0 && !p.moving) {
            boolean fist = weapon == W_FIST; Image im = fist ? atkPunch : atkGun;
            int n = fist ? punchN : gunN, cw = fist ? punchW : gunW, ch = fist ? punchH : gunH, ax = fist ? punchAX : gunAX;
            int f = Math.min(n - 1, (int) ((0.42f - atkT) / 0.42f * n)); if (!fist) f = Math.min(n - 1, flashT > 0 ? 1 : 2);
            bx = x - ax; by = y - ch + 1; bw = cw; bh = ch;
            g.drawRegion(im, (d * n + f) * cw, 0, cw, ch, 0, bx - cx, by - cy, TL);
        } else if (p.moving) {
            int n = pwN[t], cw = pwW[t], ch = pwH[t], f = d * n + (((int) (p.bob * n)) >> 2) % n;
            bx = x - pwA[t]; by = y - pwY[t]; bw = cw; bh = ch; g.drawRegion(pedWalk[t], f * cw, 0, cw, ch, 0, bx - cx, by - cy, TL);
        } else if (p.role == Ped.COP && p.state == Ped.SHOOT && atkGun != null) {
            int cw = psW[t], ch = psH[t];
            bx = x - psA[t]; by = y - ch + 1; bw = cw; bh = ch; g.drawRegion(pedStand[t], d * cw, 0, cw, ch, 0, bx - cx, by - cy, TL);
            g.setColor(0x202020); g.fillRect(x + (int) (p.dirX() * 7) - cx - 1, y - 15 + (int) (p.dirY() * 5) - cy, 3, 2);
        } else {
            int cw = psW[t], ch = psH[t];
            bx = x - psA[t]; by = y - ch + 1; bw = cw; bh = ch; g.drawRegion(pedStand[t], d * cw, 0, cw, ch, 0, bx - cx, by - cy, TL);
        }
        if (p.hitT > 0) { g.setColor(0xc01010); g.fillRect(x - 2 - cx, by + 5 - cy, 4, 4); }
    }

    void outlined(Graphics g, String s, int x, int y, int anchor, int col) {
        cf.outline(g, s, x, y, anchor, col);
    }
    static String mmss(float t) { int s = (int) t; return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60; }

    static final String[] MENU = {"START", "SOUND", "CONTROLS", "QUIT"};
    static final String[] CTRL = {"ON FOOT", "2 4 6 8 / 1 3 7 9  move", "5  shoot (hold for SMG / rifles)", "#  sprint (hold)", "*  next weapon",
            "0  take a car (stand still)", "", "IN A CAR", "2 8  gas / brake", "4 6  steer", "#  handbrake", "5  horn", "0  get out", "",
            "Survive each wave: hold out AND", "take down enough cops. Run cops", "and triads over for ammo. Guns", "are on the radar (green blips).", "", "soft key  pause"};
    boolean loading;
    void renderMenu(Graphics g) {
        g.setColor(0); g.fillRect(0, 0, W, H);
        if (state == ST_LOAD) {
            fs.draw(g, "Loading...", W / 2, H / 2 - 4 - fs.h, Graphics.HCENTER | Graphics.TOP, 0xd0d0d0);
            g.setColor(0x505050); g.drawRect(W / 2 - 60, H / 2 + 4, 120, 7); g.setColor(0xe8b830); g.fillRect(W / 2 - 58, H / 2 + 6, 116 * loadPct / 100, 4);
            return;
        }
        if (state == ST_SND) {
            cf = fs; outlined(g, "Enable sound?", W / 2, H / 2 - 40, Graphics.HCENTER | Graphics.BASELINE, 0xffffff);
            cf = fb;
            outlined(g, "YES", W / 2, H / 2 - 10, Graphics.HCENTER | Graphics.TOP, sndSel == 0 ? 0xe8b830 : 0x909090);
            outlined(g, "NO", W / 2, H / 2 + 20, Graphics.HCENTER | Graphics.TOP, sndSel == 1 ? 0xe8b830 : 0x909090);
            fs.draw(g, "2/8 choose   5 OK", W / 2, H - 12, Graphics.HCENTER | Graphics.BASELINE, 0x808080);
            return;
        }
        if (loading) {
            cf = fs; outlined(g, "Loading...", W / 2, H / 2, Graphics.HCENTER | Graphics.BASELINE, 0xffffff);
            if (loadPct > 0) { g.setColor(0x505050); g.drawRect(W / 2 - 60, H / 2 + 8, 120, 7); g.setColor(0xe8b830); g.fillRect(W / 2 - 58, H / 2 + 10, 116 * loadPct / 100, 4); }
            return;
        }
        if (menuImg != null) g.drawImage(menuImg, W / 2, H / 2, Graphics.HCENTER | Graphics.VCENTER);
        if (state == ST_CTRL) {
            g.setColor(0x000000); g.fillRect(6, 6, W - 12, H - 12);
            int y = 12;
            for (int i = 0; i < CTRL.length; i++) {
                boolean head = CTRL[i].startsWith("ON ") || CTRL[i].startsWith("IN ");
                fs.draw(g, CTRL[i], 14, y, TL, head ? 0xe8b830 : 0xffffff); y += CTRL[i].length() == 0 ? 6 : fs.h + 1;
            }
            return;
        }
        if (logoImg != null) g.drawImage(logoImg, W / 2, 10, Graphics.HCENTER | Graphics.TOP);
        cf = fs; outlined(g, txt("WI_TITLE"), W / 2, 118, Graphics.HCENTER | Graphics.TOP, 0xe8c070);
        if (state != ST_LOAD && map != null && best > 0) outlined(g, "Best: " + mmss(best), W / 2, 120 + fs.h, Graphics.HCENTER | Graphics.TOP, 0xc8c8c8);
        cf = fb;
        int y0 = H - 132;
        for (int i = 0; i < MENU.length; i++) outlined(g, i == 1 ? (sound ? "SOUND: ON" : "SOUND: OFF") : MENU[i], W / 2, y0 + i * 27, Graphics.HCENTER | Graphics.TOP, i == menuSel ? 0xe8b830 : 0xd0d0d0);
        cf = fs;
        if (state == ST_LOAD || map == null) outlined(g, "Loading...", W / 2, H - 14, Graphics.HCENTER | Graphics.BASELINE, 0xa0a0a0);
    }
    void renderOver(Graphics g) {
        cf = fb;
        if (wastedImg != null) g.drawImage(wastedImg, W / 2, H / 2 - 18, Graphics.HCENTER | Graphics.BOTTOM);   // GTA III pricedown, dark yellow
        else outlined(g, txt("WASTED"), W / 2, H / 2 - 22, Graphics.HCENTER | Graphics.BASELINE, 0xb02020);
        cf = fs;
        outlined(g, txt("WI_TIME") + " " + mmss(survT), W / 2, H / 2 + 6, Graphics.HCENTER | Graphics.BASELINE, 0xffffff);
        outlined(g, "Wanted level " + stars + "   Cops down " + kills, W / 2, H / 2 + 25, Graphics.HCENTER | Graphics.BASELINE, 0xe0e0e0);
        outlined(g, (newBest ? "NEW BEST!  " : "Best ") + mmss(best), W / 2, H / 2 + 44, Graphics.HCENTER | Graphics.BASELINE, newBest ? 0xe8b830 : 0xb0b0b0);
        if (stT > 2 && ((int) (stT * 2) & 1) == 0) { cf = fs; outlined(g, "5: main menu", W / 2, H - 30, Graphics.HCENTER | Graphics.BASELINE, 0xb0b0b0); }
    }

    // ------------------------------------------------------------------ HUD
    int[] gi = new int[8];
    // HUD strings are rebuilt only when their values change (no per-frame garbage)
    String hAmmo = "", hTime = "", hWave = "", hKills = "", hHold = ""; int cAmmo = -1, cTime = -1, cStars = -1, cKills = -1, cHold = -2;
    void shadow(Graphics g, String s, int x, int y, int anchor, int col) { g.setColor(0); g.drawString(s, x + 1, y + 1, anchor); g.setColor(col); g.drawString(s, x, y, anchor); }
    // cached text sprites: slot -> (string, colour, font) -> image with a 1 px shadow / outline and a transparent background
    static final int TC_AMMO = 0, TC_TIME = 1, TC_WAVE = 2, TC_KILLS = 3, TC_HOLD = 4, TC_POP = 5, TC_TITLE = 6, TC_SKIP = 7, TC_N = 8;
    String[] tcS = new String[TC_N]; int[] tcC = new int[TC_N]; Image[] tcI = new Image[TC_N];
    Image tc(int slot, String s, BFont f, int col, boolean outline) {
        if (tcI[slot] != null && tcS[slot] == s && tcC[slot] == col) return tcI[slot];
        tcS[slot] = s; tcC[slot] = col; tcI[slot] = null;
        return tcI[slot] = f.render(s, col, outline);
    }
    void tdraw2(Graphics g, int slot, String s, BFont f, int col, int x, int y, int anchor) { if (s != null && s.length() > 0) g.drawImage(tc(slot, s, f, col, true), x, y, anchor); }
    void tdraw(Graphics g, int slot, String s, BFont f, int col, int x, int y, int anchor) { if (s != null && s.length() > 0) g.drawImage(tc(slot, s, f, col, false), x, y, anchor); }
    static final String DIG = "0123456789:/";
    static final int[] DIG_C = {0xffffff, 0xe8b830, 0x80ff80, 0xff6040};
    Image[] digI = new Image[4]; short[] digX = new short[13];
    void prepDigits() {
        for (int c = 0; c < 4; c++) digI[c] = fh.render(DIG, DIG_C[c], false);
        int x = 0; for (int i = 0; i < 12; i++) { digX[i] = (short) x; x += fh.f.charWidth(DIG.charAt(i)); } digX[12] = (short) x;
    }
    int digW(String s) { int w = 0; for (int i = 0; i < s.length(); i++) { int k = DIG.indexOf(s.charAt(i)); w += k < 0 ? 3 : digX[k + 1] - digX[k]; } return w; }
    /** a number / time in colour c (0 white, 1 yellow, 2 green, 3 red) */
    void digits(Graphics g, String s, int x, int y, int anchor, int c) {
        if ((anchor & Graphics.RIGHT) != 0) x -= digW(s); else if ((anchor & Graphics.HCENTER) != 0) x -= digW(s) / 2;
        Image im = digI[c]; int h = im.getHeight();
        for (int i = 0; i < s.length(); i++) {
            int k = DIG.indexOf(s.charAt(i)); if (k < 0) { x += 3; continue; }
            int w = digX[k + 1] - digX[k]; g.drawRegion(im, digX[k], 0, Math.min(w + 1, im.getWidth() - digX[k]), h, 0, x, y, TL); x += w;
        }
    }
    String hKillsN = "", hHoldN = "";
    void renderHud(Graphics g) {
        Image wi = wIcon[weapon]; int iw = wi.getWidth(), ix = W - 3 - iw, iy = 3;
        g.drawImage(wi, ix, iy, TL);
        int a = weapon == W_FIST ? -1 : ammo[weapon];
        if (a != cAmmo) { cAmmo = a; hAmmo = a < 0 ? "" : String.valueOf(a); }
        if (a >= 0) digits(g, hAmmo, ix + iw / 2, iy + iw - 1, Graphics.HCENTER | Graphics.TOP, 0);
        int n = 0, hp = (int) (Math.max(0, health) + 0.99f); gi[n++] = 0; gi[n++] = 1 + hp / 100 % 10; gi[n++] = 1 + hp / 10 % 10; gi[n++] = 1 + hp % 10;
        glyphs(g, fHealth, healthX, healthW, gi, n, ix - 3, iy + 1);
        int sw = hStar.getWidth(), sh = hStar.getHeight(), sy = iy + iw - 1 + fh.h, sx = W - 3;
        for (int i = 0; i < 6; i++) { int x = sx - (i + 1) * (sw + 2); g.drawImage(i < stars ? hStar : hStarOff, x, sy, TL); }
        if (stars >= 2) {
            if (stars != cStars) { cStars = stars; hWave = stars < 6 ? txt("WI_WAVE") + " " + (stars - 1) : "6 STARS"; cKills = -1; cHold = -2; }
            tdraw(g, TC_WAVE, hWave, fh, 0xffffff, 4, 3, TL);
            int y1 = 3 + fh.h, y2 = y1 + fh.h;
            if (stars < 6) {
                int q = WV_QUOTA[stars], k = Math.min(waveKills, q), left = overtime ? -1 : Math.max(0, (int) (WV_TIME[stars] - waveT + 0.99f));
                if (k != cKills) { cKills = k; hKillsN = k + "/" + q; }
                if (left != cHold) { cHold = left; hHoldN = left < 0 ? "" : mmss(left); }
                int kc = waveKills >= q ? 2 : 0;
                Image li = tc(TC_KILLS, "cops ", fh, DIG_C[kc], false); g.drawImage(li, 4, y1, TL); digits(g, hKillsN, 4 + fh.width("cops "), y1, TL, kc);
                if (left < 0) tdraw(g, TC_HOLD, "OVERTIME", fh, 0xff6040, 4, y2, TL);
                else { int hc = left == 0 ? 2 : 0; g.drawImage(tc(TC_HOLD, "hold ", fh, DIG_C[hc], false), 4, y2, TL); digits(g, hHoldN, 4 + fh.width("hold "), y2, TL, hc); }
            } else {
                if (kills != cKills) { cKills = kills; hKillsN = String.valueOf(kills); }
                g.drawImage(tc(TC_KILLS, "cops down ", fh, 0xffffff, false), 4, y1, TL); digits(g, hKillsN, 4 + fh.width("cops down "), y1, TL, 0);
            }
        }
        renderRadar(g);
    }
    /** a dark grey copy of an icon (GTA III draws the wanted stars you don't have this way) */
    static Image greyed(Image im) {
        int w = im.getWidth(), h = im.getHeight(); int[] px = new int[w * h]; im.getRGB(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24; if (a == 0) continue;
            int l = ((((px[i] >> 16) & 255) * 77 + ((px[i] >> 8) & 255) * 150 + (px[i] & 255) * 29) >> 8) * 30 / 100 + 22;
            px[i] = (a << 24) | (l << 16) | (l << 8) | l;
        }
        return Image.createRGBImage(px, w, h, true);
    }
    void glyphs(Graphics g, Image strip, int[] gx, int[] gw, int[] idx, int n, int x, int y) {
        int w = 0; for (int i = 0; i < n; i++) w += gw[idx[i]] - 1; x -= w; int h = strip.getHeight();
        for (int i = 0; i < n; i++) { int k = idx[i]; g.drawRegion(strip, gx[k], 0, gw[k], h, 0, x, y, TL); x += gw[k] - 1; }
    }
    static final int RS = 36;    // map px per radar px
    Image radarO; int[] rStripY, rStripW;
    /** radar.png pre-blended with the dark disc colour into one opaque image (no per-pixel work per frame) */
    void prepRadar() {
        int rw = radar.getWidth(), rh = radar.getHeight(); int[] px = new int[rw * rh];
        radar.getRGB(px, 0, rw, 0, 0, rw, rh);
        for (int i = 0; i < px.length; i++) {
            int p = px[i], a = p >>> 24, A = 255;
            int bgA = 0x90 * (255 - a) / 255, aa = a + bgA, r, gg, bl;
            if (aa == 0) { px[i] = 0xff000000; continue; }
            r = (((p >> 16) & 255) * a + 0x0c * bgA) / aa; gg = (((p >> 8) & 255) * a + 0x12 * bgA) / aa; bl = ((p & 255) * a + 0x18 * bgA) / aa;
            int k = aa;   // darken where the dark disc is translucent (behind it is black)
            r = r * k / 255; gg = gg * k / 255; bl = bl * k / 255;
            px[i] = (A << 24) | (r << 16) | (gg << 8) | bl;
        }
        radarO = Image.createRGBImage(px, rw, rh, false); radar = null;
        int R = RD / 2, n = 0; rStripY = new int[RD]; rStripW = new int[RD];
        for (int y = -R; y < R; y += 3) { float ym = y + 1.5f; int hw = (int) Math.sqrt(Math.max(0, R * R - ym * ym)); rStripY[n] = y; rStripW[n] = hw; n++; }
        rStripY[n] = 9999;
    }
    void renderRadar(Graphics g) {
        int R = RD / 2, rcx = 39, rcy = H - 39;
        float fx = focusX() / RS, fy = focusY() / RS;
        int ox = rcx - (int) fx, oy = rcy - (int) fy;
        g.setColor(0); g.fillArc(rcx - R - 3, rcy - R - 3, RD + 6, RD + 6, 0, 360);
        for (int k = 0; rStripY[k] != 9999; k++) {
            int hw = rStripW[k]; if (hw <= 0) continue;
            g.setClip(rcx - hw, rcy + rStripY[k], hw * 2, 3); g.drawImage(radarO, ox, oy, TL);
        }
        g.setClip(0, 0, W, H);
        g.drawImage(hRing, rcx, rcy, Graphics.HCENTER | Graphics.VCENTER);
        for (int i = 0; i < nPk; i++) if (pkW[i] >= 0) blip(g, bWeapon, pkX[i] / (float) RS - fx, pkY[i] / (float) RS - fy, rcx, rcy);
        g.drawImage(hNorth, rcx - R - 1, rcy, Graphics.HCENTER | Graphics.VCENTER);
        int fr = G.frame(car != null ? car.a : claude.a, 16); g.drawRegion(hArrow, fr * 12, 0, 12, 12, 0, rcx - 6, rcy - 6, TL);
    }
    void blip(Graphics g, Image icon, float dx, float dy, int rcx, int rcy) {
        float d = (float) Math.sqrt(dx * dx + dy * dy), lim = RD / 2 - 4; if (d > lim) { dx = dx / d * lim; dy = dy / d * lim; }
        g.drawImage(icon, rcx + (int) dx, rcy + (int) dy, Graphics.HCENTER | Graphics.VCENTER);
    }
    void say(String[] k, boolean[] eight) {
        dlgText = new String[k.length]; dlgPort = new Image[k.length]; dlgName = new String[k.length];
        for (int i = 0; i < k.length; i++) { dlgText[i] = txt(k[i]); dlgPort[i] = eight[i] ? portEight : portClaude; dlgName[i] = eight[i] ? "8-BALL" : "CLAUDE"; }
        dlgIdx = 0; dlgChars = 0; dlgL = null; dlgLi = -1;
    }
    Vector dlgL; int dlgLi = -1; Image dlgI; int dlgDrawn = -1, dlgDrawnIx = -1, dlgBlink = -1;
    void renderDialogue(Graphics g) {
        int bw = W - 8, bh = 76;
        String t = dlgText[dlgIdx]; int shown = Math.min(t.length(), (int) dlgChars), blink = shown >= t.length() ? ((int) (stT * 3) & 1) : 2;
        if (dlgI == null) dlgI = Image.createImage(bw, bh);
        if (shown != dlgDrawn || dlgIdx != dlgDrawnIx || blink != dlgBlink) { dlgDrawn = shown; dlgDrawnIx = dlgIdx; dlgBlink = blink; drawDialogue(dlgI.getGraphics()); }
        g.drawImage(dlgI, 4, 4, TL);
    }
    void drawDialogue(Graphics g) {
        String t = dlgText[dlgIdx]; int bw = W - 8, bh = 76, bx = 0, by = 0;
        g.setColor(0x101418); g.fillRect(bx, by, bw, bh); g.setColor(0x8090a0); g.drawRect(bx, by, bw - 1, bh - 1);
        Image port = dlgPort[dlgIdx]; g.drawImage(port, bx + 6, by + 6, TL);
        g.setColor(0xc0c0c0); g.drawRect(bx + 5, by + 5, port.getWidth() + 1, port.getHeight() + 1);
        fs.draw(g, dlgName[dlgIdx], bx + 6 + port.getWidth() / 2 + 1, by + 6 + port.getHeight() + 2, Graphics.HCENTER | Graphics.TOP, 0xe8b830);
        int tx = bx + port.getWidth() + 14, tw = bw - (tx - bx) - 6, shown = Math.min(t.length(), (int) dlgChars);
        if (dlgL == null || dlgLi != dlgIdx) { dlgLi = dlgIdx; dlgL = wrap(t, fs, tw); }
        Vector lines = dlgL; int y = by + 5, used = 0; g.setColor(0xffffff);
        for (int i = 0; i < lines.size(); i++) {
            String l = (String) lines.elementAt(i); if (used >= shown) break;
            fs.draw(g, l, 0, Math.min(l.length(), shown - used), tx, y, TL, 0xffffff); used += l.length() + 1; y += fs.h;
        }
        if (shown >= t.length() && ((int) (stT * 3) & 1) == 0) { g.setColor(0xe8b830); g.fillTriangle(bx + bw - 14, by + bh - 12, bx + bw - 6, by + bh - 12, bx + bw - 10, by + bh - 6); }
    }
    String wrS; int wrW; Vector wrL;
    Vector wrapC(String s, int w) { if (s != wrS || w != wrW) { wrS = s; wrW = w; wrL = wrap(s, fs, w); } return wrL; }
    String bxS; int bxW; Image bxI;
    void boxBottom(Graphics g, String s, int x, int w) {
        if (bxI == null || bxS != s || bxW != w) {
            bxS = s; bxW = w; int h = wrapC(s, w - 10).size() * fs.h + 8;
            bxI = Image.createImage(w, h); box(bxI.getGraphics(), s, 0, 0, w);
        }
        g.drawImage(bxI, x, H - 4 - bxI.getHeight(), TL);
    }
    void box(Graphics g, String s, int x, int y, int w) {
        Vector l = wrapC(s, w - 10); int h = l.size() * fs.h + 8;
        g.setColor(0x101418); g.fillRect(x, y, w, h); g.setColor(0x8090a0); g.drawRect(x, y, w - 1, h - 1);
        for (int i = 0; i < l.size(); i++) fs.draw(g, (String) l.elementAt(i), x + 5, y + 4 + i * fs.h, TL, 0xffffff);
    }
    static Vector wrap(String s, BFont f, int w) {
        Vector out = new Vector(); int p = 0;
        while (p < s.length()) {
            int end = p, sp = -1;
            while (end < s.length() && f.width(s, p, end - p + 1) <= w) { if (s.charAt(end) == ' ') sp = end; end++; }
            if (end >= s.length()) { out.addElement(s.substring(p)); break; }
            int cut = sp > p ? sp : end; out.addElement(s.substring(p, cut)); p = cut; while (p < s.length() && s.charAt(p) == ' ') p++;
        }
        return out;
    }

    // ------------------------------------------------------------------ sound
    int radioN = -1;                       // how many radio tracks (a/r0.mid, a/r1.mid ...) the build put in the jar
    void startRadio() {
        if (radioN < 0) { radioN = 0; while (radioN < 99) { InputStream is = getClass().getResourceAsStream("/a/r" + radioN + ".mid"); if (is == null) break; try { is.close(); } catch (IOException e) { } radioN++; } }
        if (radioN == 0) return;
        radioOn = true; playMusic("/a/r" + rnd.nextInt(radioN) + ".mid", false);
    }
    public void playerUpdate(Player p, String event, Object data) { if (PlayerListener.END_OF_MEDIA.equals(event) && radioOn && p == music) radioNext = true; }
    static final String[] MIDI_T = {"audio/midi", "audio/x-midi", "audio/mid", "audio/sp-midi"};
    void playMusic(String res, boolean loop) {
        stopMusic(); if (!sound) return;
        for (int i = 0; i < MIDI_T.length && music == null; i++) {
            Player p = null;
            try {
                InputStream is = getClass().getResourceAsStream(res); if (is == null) return;          // no music in this build: stay quiet
                p = Manager.createPlayer(is, MIDI_T[i]); p.realize(); p.prefetch();
                try { VolumeControl vc = (VolumeControl) p.getControl("VolumeControl"); if (vc != null) { vc.setMute(false); vc.setLevel(100); } } catch (Throwable t) { }
                p.addPlayerListener(this); p.setLoopCount(loop ? -1 : 1); p.start(); music = p; sndErr = null;
            } catch (Throwable t) { sndErr = MIDI_T[i] + ": " + t; try { if (p != null) p.close(); } catch (Throwable x) { } }
        }
    }
    void stopMusic() { try { if (music != null) { music.stop(); music.close(); } } catch (Throwable t) { } music = null; }
}
