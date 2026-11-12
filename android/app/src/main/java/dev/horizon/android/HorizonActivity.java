package dev.horizon.android;

/**
 * Native game surface. The Compose launcher is hosted by LauncherActivity;
 * Rust owns the game loop, rendering, input and platform services.
 */
public final class HorizonActivity extends android.app.NativeActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyDisplayPreferences();
        preferHighestRefreshRate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyDisplayPreferences();
        preferHighestRefreshRate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            preferHighestRefreshRate();
        }
    }

    private void applyDisplayPreferences() {
        boolean keepScreenOn = getSharedPreferences("launcher", MODE_PRIVATE)
                .getBoolean("keep_screen_on", true);
        if (keepScreenOn) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
