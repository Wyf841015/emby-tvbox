package android.os;

public class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { new Thread(r).start(); return true; }
    public boolean postDelayed(Runnable r, long ms) { new Thread(r).start(); return true; }
}