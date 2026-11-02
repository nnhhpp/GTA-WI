import javax.microedition.lcdui.*;
import javax.microedition.midlet.*;

public final class WhatIf extends MIDlet {
    Game game;
    protected void startApp() {
        if (game == null) { game = new Game(this); Display.getDisplay(this).setCurrent(game); new Thread(game).start(); }
        else Display.getDisplay(this).setCurrent(game);
    }
    protected void pauseApp() { if (game != null) game.paused = true; }
    protected void destroyApp(boolean u) { if (game != null) game.running = false; }
    public void quit() { destroyApp(true); notifyDestroyed(); }
}
