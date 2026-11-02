import javax.microedition.lcdui.*;

/** Text helper over a system font (the phone's own, bold): plain / shadow / outline drawing and cached text images. */
final class BFont {
    static final int TL = Graphics.TOP | Graphics.LEFT;
    final Font f; final int h, asc;

    BFont(Font f) { this.f = f; h = f.getHeight(); asc = f.getBaselinePosition(); }
    int width(String s) { return f.stringWidth(s); }
    int width(String s, int off, int len) { return f.substringWidth(s, off, len); }
    void draw(Graphics g, String s, int x, int y, int anchor, int col) { g.setFont(f); g.setColor(col); g.drawString(s, x, y, anchor); }
    void draw(Graphics g, String s, int off, int len, int x, int y, int anchor, int col) { if (len <= 0) return; g.setFont(f); g.setColor(col); g.drawSubstring(s, off, len, x, y, anchor); }
    void shadow(Graphics g, String s, int x, int y, int anchor, int col) { draw(g, s, x + 1, y + 1, anchor, 0); draw(g, s, x, y, anchor, col); }
    void outline(Graphics g, String s, int x, int y, int anchor, int col) {
        g.setFont(f); g.setColor(0);
        g.drawString(s, x - 1, y, anchor); g.drawString(s, x + 1, y, anchor); g.drawString(s, x, y - 1, anchor); g.drawString(s, x, y + 1, anchor);
        g.setColor(col); g.drawString(s, x, y, anchor);
    }
    /** text image with a transparent background (black keyed out) and a 1 px dark shadow or outline */
    Image render(String s, int col, boolean outline) {
        int o = outline ? 1 : 0, w = Math.max(1, f.stringWidth(s) + 1 + 2 * o), hh = h + 1 + 2 * o;
        Image m = Image.createImage(w, hh); Graphics g = m.getGraphics();
        g.setColor(0); g.fillRect(0, 0, w, hh); g.setFont(f); g.setColor(0x101010);
        if (outline) { g.drawString(s, 0, 1, TL); g.drawString(s, 2, 1, TL); g.drawString(s, 1, 0, TL); g.drawString(s, 1, 2, TL); }
        else g.drawString(s, 1, 1, TL);
        g.setColor(col); g.drawString(s, o, o, TL);
        int[] px = new int[w * hh]; m.getRGB(px, 0, w, 0, 0, w, hh);
        for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xffffff) == 0 ? 0 : px[i] | 0xff000000;
        return Image.createRGBImage(px, w, hh, true);
    }
}
