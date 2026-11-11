package dev.horizon.android;

/**
 * Minimal Android NativeActivity host.
 *
 * The Rust entry point owns the game loop, rendering, input and platform services.
 * Keep this class limited to Android Activity lifecycle integration.
 */
public final class HorizonActivity extends android.app.NativeActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferHighestRefreshRate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        preferHighestRefreshRate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            preferHighestRefreshRate();
        }
    }

    private void preferHighestRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }
        final android.view.Window window = getWindow();
        if (window == null) {
            return;
        }
        final android.view.Display display = getWindowManager().getDefaultDisplay();
        if (display == null) {
            return;
        }
        final android.view.Display.Mode current = display.getMode();
        android.view.Display.Mode best = current;
        for (android.view.Display.Mode mode : display.getSupportedModes()) {
            if (current != null
                    && (mode.getPhysicalWidth() != current.getPhysicalWidth()
                    || mode.getPhysicalHeight() != current.getPhysicalHeight())) {
                continue;
            }
            if (best == null || mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        if (best != null) {
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.preferredDisplayModeId = best.getModeId();
            params.preferredRefreshRate = best.getRefreshRate();
            window.setAttributes(params);
        }
    }
}
