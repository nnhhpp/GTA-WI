/** Anything that moves on the map (map px, 12 px = 1 m; heading 0 = screen up, clockwise). */
public class Mob {
    public float x, y, a;
    public final float dirX() { return G.sin(a); }
    public final float dirY() { return -G.cos(a); }
}
