package io.github.leekin2.xnotify;

import android.app.Application;
import android.util.Log;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.List;

/**
 * Pop-ups need the System UI scope, which LSPosed does not add when an existing install is
 * updated. When LSPosed connects, ask for it once; LSPosed shows a one-tap approval notice.
 */
public final class ScopeApp extends Application {
    private static final String SYSTEMUI = "com.android.systemui";

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) { requestSystemUi(ScopeApp.this, service); }
            @Override public void onServiceDied(XposedService service) { }
        });
    }

    private static void requestSystemUi(Application app, XposedService service) {
        try {
            if (service.getScope().contains(SYSTEMUI)) return;
            // Ask once per version, so a declined request does not come back at every boot.
            android.content.SharedPreferences prefs = app.getSharedPreferences("scope", MODE_PRIVATE);
            long version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode();
            if (prefs.getLong("asked", 0) == version) return;
            prefs.edit().putLong("asked", version).apply();
            service.requestScope(List.of(SYSTEMUI), new XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> approved) { Log.i("XNotify", "System UI scope approved"); }
                @Override public void onScopeRequestFailed(String message) { Log.i("XNotify", "System UI scope not added: " + message); }
            });
        } catch (Throwable t) {
            Log.w("XNotify", "System UI scope request failed", t);
        }
    }
}
