package android.view;

public class Window {
    public View getDecorView() { return new View(); }
    public void setFlags(int flags, int mask) {}
    public void addFlags(int flags) {}
    public void clearFlags(int flags) {}
}