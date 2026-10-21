package android.app;

import android.content.Context;

public class Activity extends Context {
    public void runOnUiThread(Runnable r) { r.run(); }
    public android.view.Window getWindow() { return new android.view.Window(); }
    public void onCreate(android.os.Bundle b) {}
    public void setContentView(int id) {}
}