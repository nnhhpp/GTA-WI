/** Pedestrian, cop or player on foot. */
public final class Ped extends Mob {
    public static final int CIV = 0, TRIAD = 1, COP = 2, PLAYER = 3, EIGHT = 4, MISTY = 5;
    public static final int WALK = 0, FLEE = 1, CHASE = 2, SHOOT = 3, IDLE = 4;
    public int type, role, state, weapon;
    public int edge = -1, from, to; public float off;
    public float tx, ty, speed, timer, bob, health = 100, hitT, fireT, stuck, knockT, farT;
    public boolean active, moving;
    public Car ride;                 // cop: the car he came in
}
