package android.view;

import android.content.Context;

public class View {
    public View() {}
    public View(Context c) {}
    public Context getContext() { return new Context(); }
    public void setVisibility(int v) {}
    public int getVisibility() { return 0; }
    public Object findViewById(int id) { return null; }
    public void setBackgroundColor(int color) {}
    public void post(Runnable r) { r.run(); }
}