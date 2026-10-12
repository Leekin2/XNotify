package io.github.leekin2.xnotify;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * HyperOS cross-device notification sync (com.milink.service) only forwards seven hard-coded
 * Chinese apps, and the CN tablet ROM hides the receiving side entirely. Forward every app's
 * notifications to nearby devices, labelled with the app name, without duplicating apps the
 * tablet already has.
 */
public final class XNotifyModule extends XposedModule {
    private static final String TAG = "XNotify";
    private static final String MILINK = "com.milink.service";
    private static final String PKG = "com.xiaomi.dist.notification.";
    private static final String HANDLER = PKG + "listener.handle.NotificationHandler";
    private static final String NEARBY = PKG + "common.utils.NearbyDeviceHelper";
    private static final String BUILDER = PKG + "listener.handle.NotificationBuilder";
    private static final String APP_PKG_ID = PKG + "handler.utils.AppPkgId";
    private static final String PROCESSOR = PKG + "trans.client.receive.NotificationProcessor";
    private static final String LYRA_DISCOVER = PKG + "discover.LyraDiscover";
    private static final String UI = "com.miui.circulate.world.ui.connectivitysettings.";
    private static final String SYSTEMUI = "com.android.systemui";
    /** Channel milink posts forwarded notifications on. */
    private static final String SYNC_CHANNEL = "ancs_channel";

    /** Never forwarded: system chrome and the sync stack itself. */
    private static final Set<String> EXCLUDED = Set.of("android", "com.android.systemui", MILINK,
            "com.xiaomi.mi_connect_service", "com.xiaomi.mirror", "com.android.providers.downloads",
            "com.xiaomi.xmsf", "com.miui.securitycenter", "com.android.phone", "com.android.server.telecom");
    /** Ongoing or system-style notifications that would only spam the other device. */
    private static final Set<String> EXCLUDED_CATEGORIES = Set.of(Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS, Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_LOCATION_SHARING, Notification.CATEGORY_WORKOUT);
    private static final int SKIP_FLAGS = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
            | Notification.FLAG_LOCAL_ONLY | Notification.FLAG_NO_CLEAR;

    // No settings screen: the tablet's own app picker decides what is sent.
    /** Also forward apps the tablet has not chosen. */
    private boolean enabled() { return false; }
    private boolean blocked(String pkg) { return false; }
    /** Forward while the phone is in use too, not only when it is locked. */
    private boolean whileInUse() { return true; }
    /** Skip forwarded copies of apps that are installed on the tablet too. */
    private boolean dedupe() { return true; }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (SYSTEMUI.equals(param.getPackageName())) {
            try { hookHeadsUp(param.getClassLoader()); } catch (Throwable t) { error("Pop-up hook unavailable", t); }
            return;
        }
        if (!MILINK.equals(param.getPackageName())) return;
        ClassLoader loader = param.getClassLoader();
        try { hookSender(loader); } catch (Throwable t) { error("Sender hook unavailable", t); }
        try { hookReceiver(loader); } catch (Throwable t) { error("Receiver hook unavailable", t); }
        try { hookDiscovery(loader); } catch (Throwable t) { error("Discovery hook unavailable", t); }
        try { hookReceiveScreen(loader); } catch (Throwable t) { error("Receive screen hook unavailable", t); }
        try { hookAppPicker(loader); } catch (Throwable t) { error("App picker hook unavailable", t); }
        try { hookCatalogRequest(loader); } catch (Throwable t) { error("App list request hook unavailable", t); }
        if ("com.milink.crossdeviceservice".equals(android.app.Application.getProcessName())) sendCatalogSoon(loader);
    }

    // --- Tablet pop-ups ------------------------------------------------------------------------

    /**
     * HyperOS only pops up (heads-up) notifications of apps on its own whitelist unless the user
     * turns on "Floating notifications" for the channel, which Interconnectivity is not on, so
     * forwarded notifications silently land in the shade. Let them pop up like the original
     * app's would, unless the user switched floating off for Interconnectivity or its channel.
     */
    private void hookHeadsUp(ClassLoader loader) throws Exception {
        Method canFloat = Class.forName("com.miui.systemui.notification.NotificationSettingsManager", false, loader)
                .getDeclaredMethod("canFloat", Context.class, String.class, String.class);
        hook(canFloat).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object allowed = chain.proceed();
            if (Boolean.TRUE.equals(allowed) || !MILINK.equals(chain.getArg(1)) || !SYNC_CHANNEL.equals(chain.getArg(2))) return allowed;
            Context context = (Context) chain.getArg(0);
            android.content.SharedPreferences prefs = context.getSharedPreferences("app_notification", Context.MODE_MULTI_PROCESS);
            // 1 = switched off by the user, 2 = on; absent = HyperOS default (whitelist).
            if (prefs.getInt(MILINK, 0) == 1 || prefs.getInt(MILINK + "_" + SYNC_CHANNEL + "_channel_flag", 0) == 1) return allowed;
            return true;
        });
        info("Forwarded notifications pop up on screen");
    }

    // --- Tablet settings screen ----------------------------------------------------------------

    /**
     * Open the Receive/Sync notification screen from Settings (the CN tablet only shows Sync),
     * and keep its device list connected: Xiaomi only ever registers the first screen's listener,
     * so on a second visit found phones go to the closed screen and the list stays empty.
     */
    private void hookReceiveScreen(ClassLoader loader) throws Exception {
        Method route = Class.forName(UI + "ConnectivityHelper", false, loader).getDeclaredMethod("z", Context.class);
        hook(route).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (reported.add("route")) info("Notifications settings opened: routed to the Receive/Sync screen (was " + chain.proceed() + ")");
            return false;
        });

        // Whatever path opens Xiaomi's send-only page (Settings, search, an older entry), show
        // the Receive/Sync screen instead.
        try {
            Method create = Class.forName(UI + "GeneralActivity", false, loader).getDeclaredMethod("onCreate", android.os.Bundle.class);
            hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                android.app.Activity activity = (android.app.Activity) chain.getThisObject();
                Object result = chain.proceed();
                if ("text_pref_key_share_notification".equals(activity.getIntent().getStringExtra("extra_pref_key"))) {
                    info("Send-only notification page opened: switching to the Receive/Sync screen");
                    activity.startActivity(new android.content.Intent().setClassName(activity.getPackageName(),
                            UI + "Notification.FeatureNotificationActivity"));
                    activity.finish();
                }
                return result;
            });
        } catch (Throwable t) {
            error("Send-only page redirect unavailable", t);
        }

        Class<?> model = Class.forName(UI + "Notification.l", false, loader);
        Class<?> listenerType = Class.forName(UI + "Notification.DeviceReceiveStrategy$b", false, loader);
        Method register = model.getDeclaredMethod("o", listenerType);
        Method found = model.getDeclaredMethod("k");
        Method cached = model.getDeclaredMethod("i");
        java.lang.reflect.Field slot = null;
        for (java.lang.reflect.Field f : model.getDeclaredFields()) if (f.getType() == listenerType) slot = f;
        if (slot == null) throw new NoSuchFieldException("device listener");
        java.lang.reflect.Field listenerField = slot;
        listenerField.setAccessible(true);
        Method onFound = listenerType.getMethod("a", Class.forName(PKG + "discover.NotifiDevice", false, loader));
        Method onCached = listenerType.getMethod("onCacheDeviceFound", onFound.getParameterTypes()[0]);
        hook(register).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object listener = chain.getArg(0);
            Object owner = chain.getThisObject();
            listenerField.set(owner, listener);
            if (listener != null) {
                for (Object d : (List<?>) found.invoke(owner)) onFound.invoke(listener, d);
                for (Object d : (List<?>) cached.invoke(owner)) onCached.invoke(listener, d);
            }
            return null;
        });
        info("Tablet notification screen shows the Receive tab and keeps its device list");
    }

    // --- Discovery -----------------------------------------------------------------------------

    /**
     * Notification sync between Xiaomi devices is only offered when both sides advertise phone
     * screen casting (com.xiaomi.mirror "cast" bit). The CN tablet ROM leaves that bit off, so
     * the tablet never even looks for the phone. The bit has no other role in this check.
     */
    private void hookDiscovery(ClassLoader loader) throws Exception {
        Method cast = Class.forName(LYRA_DISCOVER, false, loader).getDeclaredMethod("isCastServiceSupport", String.class);
        cast.setAccessible(true);
        hook(cast).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> true);
        try { hookDiscoveryChecks(loader); } catch (Throwable t) { error("Discovery checks hook unavailable", t); }
        try { hookDiscoveryStart(loader); } catch (Throwable t) { error("Discovery start hook unavailable", t); }
        info("Notification device discovery no longer requires screen casting");
    }

    private static final ThreadLocal<Boolean> judging = new ThreadLocal<>();
    private static final Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Besides casting, Xiaomi drops a found phone unless its advertised data has the MiLink bit
     * and its build region reads exactly "cn". Some HyperOS 4 CN builds (POCO, ported ROMs) fail
     * one of these although notification sync works. Accept every trusted phone or tablet of
     * the same account, and log what each one advertised so a failing setup can be diagnosed.
     */
    private void hookDiscoveryChecks(ClassLoader loader) throws Exception {
        Method add = null;
        for (Method m : Class.forName(LYRA_DISCOVER, false, loader).getDeclaredMethods())
            if (m.getName().equals("addOrUpdateDevice") && m.getParameterCount() == 2) add = m;
        if (add == null) throw new NoSuchMethodException("addOrUpdateDevice");
        add.setAccessible(true);
        Class<?> networking = Class.forName("com.xiaomi.continuity.networking.NetworkingManager", false, loader);
        Method stringProperty = networking.getMethod("getStringProperty", String.class, int.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object regionType = Enum.valueOf((Class) Class.forName("com.xiaomi.continuity.networking.PropertyType", false, loader), "PropBuildRegion");
        int regionKey = (int) regionType.getClass().getMethod("toInteger").invoke(regionType);

        hook(stringProperty).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object value = chain.proceed();
            if (!Boolean.TRUE.equals(judging.get()) || (int) chain.getArg(1) != regionKey) return value;
            if (!"cn".equalsIgnoreCase(String.valueOf(value)) && reported.add("region:" + chain.getArg(0) + ":" + value))
                info("Device " + chain.getArg(0) + " reports region '" + value + "', accepted as cn");
            return "cn";
        });
        hook(add).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object device = chain.getArg(0);
            Object service = chain.getArg(1);
            if (device == null) return chain.proceed();
            int type = (int) call(device, "getDeviceType");
            String name = String.valueOf(call(device, "getDeviceName"));
            byte[] data = service == null ? null : (byte[]) call(service, "getServiceData");
            if ((data == null || data.length == 0) && reported.add("nodata:" + name))
                info("Seen " + name + " (type " + type + ") without notification data yet");
            if (data != null && data.length > 0 && reported.add("data:" + name + ":" + data[0]))
                info("Found " + name + " (type " + type + "), notification data flags 0x" + Integer.toHexString(data[0] & 0xff));
            // Phones (1) and tablets (2): the MiLink bit only gates the old screen-cast path.
            if ((type == 1 || type == 2) && data != null && data.length > 0 && (data[0] & 0x10) == 0) data[0] |= 0x10;
            judging.set(Boolean.TRUE);
            try { return chain.proceed(); } finally { judging.remove(); }
        });
    }

    private static final ThreadLocal<Boolean> starting = new ThreadLocal<>();

    /**
     * Xiaomi only starts looking for phones when the Xiaomi account region is exactly "cn" and
     * this device's own NotificationTrans data has the sync and casting bits. Accounts from other
     * regions on CN ROMs fail the first check, so the Receive screen never finds anything. Accept
     * them for the discovery start only (cloud server selection keeps the real region), and log
     * each step so a setup that still finds nothing can be diagnosed.
     */
    private void hookDiscoveryStart(ClassLoader loader) throws Exception {
        Class<?> manager = Class.forName(PKG + "discover.NotificationTransDeviceManager", false, loader);
        Class<?> account = Class.forName(PKG + "common.cloud.AccountManager", false, loader);
        Class<?> lyra = Class.forName(LYRA_DISCOVER, false, loader);
        Method start = null;
        for (Method m : manager.getDeclaredMethods())
            if (m.getName().equals("startDiscovery") && m.getParameterCount() == 1) start = m;
        if (start == null) throw new NoSuchMethodException("startDiscovery");
        Method region = account.getDeclaredMethod("getRegion");
        Method local = null;
        for (Method m : lyra.getDeclaredMethods())
            if (m.getName().equals("isLocalServiceDataSupport") && m.getParameterCount() == 1) local = m;
        Method lyraStart = lyra.getDeclaredMethod("startDiscovery");

        hook(start).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Context context = (Context) field(chain.getThisObject(), "mContext");
            int state = context == null ? -2 : android.provider.Settings.Secure.getInt(
                    context.getContentResolver(), "pref_key_connectivity_service_state", -1);
            if (reported.add("start:" + state)) info("Looking for devices, connectivity service state " + state);
            starting.set(Boolean.TRUE);
            try { return chain.proceed(); } finally { starting.remove(); }
        });
        region.setAccessible(true);
        hook(region).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object value = chain.proceed();
            if (!Boolean.TRUE.equals(starting.get()) || "cn".equalsIgnoreCase(String.valueOf(value))) return value;
            if (reported.add("account:" + value)) info("Xiaomi account region '" + value + "', accepted as cn for discovery");
            return "cn";
        });
        if (local != null) {
            local.setAccessible(true);
            hook(local).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object supported = chain.proceed();
                if (Boolean.TRUE.equals(supported)) return supported;
                if (reported.add("local")) info("This device's notification data " + localData(chain.getThisObject(), chain.getArg(0))
                        + " lacks sync support, discovery started anyway");
                return true;
            });
        }
        lyraStart.setAccessible(true);
        hook(lyraStart).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            Object started = field(chain.getThisObject(), "mHasStarDiscovered");
            if (reported.add("lyra:" + started)) info("Device scan " + (String.valueOf(started).equals("true") ? "running" : "not started"));
            return result;
        });
    }

    /** Starts XNotify's own process so LSPosed can connect it and offer the System UI scope. */
    private void wakeModule(Context context) {
        try {
            context.getContentResolver().call(android.net.Uri.parse("content://io.github.leekin2.xnotify.XposedService"), "wake", null, null);
        } catch (Throwable t) {
            info("XNotify app not reachable for the scope check: " + t.getMessage());
        }
    }

    private void logLocalData(ClassLoader loader, Context context) {
        try {
            Object lyra = Class.forName(LYRA_DISCOVER, false, loader).getMethod("getInstance", Context.class).invoke(null, context);
            Object builder = Class.forName("com.xiaomi.continuity.ServiceName$Builder", false, loader).getConstructor().newInstance();
            builder.getClass().getMethod("setName", String.class).invoke(builder, "NotificationTrans");
            info("This device advertises notification data " + localData(lyra, call(builder, "build")));
        } catch (Throwable t) {
            error("Notification data not readable", t);
        }
    }

    /** Hex of the NotificationTrans data this device advertises, for diagnostics. */
    private static String localData(Object lyra, Object serviceName) {
        try {
            Object networking = field(lyra, "mNetworkingManager");
            Object me = call(networking, "getLocalDeviceInfo");
            if (me == null) return "(no local device)";
            Object id = call(me, "getDeviceId");
            Object service = null;
            for (Method m : networking.getClass().getMethods())
                if (m.getName().equals("getServiceInfo") && m.getParameterCount() == 2 && m.getParameterTypes()[0] == String.class)
                    service = m.invoke(networking, id, serviceName);
            byte[] data = service == null ? null : (byte[]) call(service, "getServiceData");
            if (data == null || data.length == 0) return "(none)";
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(data.length, 16); i++) hex.append(String.format("%02x", data[i] & 0xff));
            return hex.toString();
        } catch (Throwable t) {
            return "(unreadable: " + t + ")";
        }
    }

    // --- Sending phone -------------------------------------------------------------------------

    private void hookSender(ClassLoader loader) throws Exception {
        Class<?> handler = Class.forName(HANDLER, false, loader);
        Method nearby = Class.forName(NEARBY, false, loader).getDeclaredMethod("isNearbySupported", int.class);
        Class<?> appPkgId = Class.forName(APP_PKG_ID, false, loader);
        Method miPackages = appPkgId.getDeclaredMethod("getMiPackageList", Context.class);
        Method target = null;
        for (Method m : handler.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("isDeviceSupported") && p.length == 2 && p[0] == StatusBarNotification.class) target = m;
        }
        if (target == null) throw new NoSuchMethodException("isDeviceSupported");
        target.setAccessible(true);
        hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            StatusBarNotification sbn = (StatusBarNotification) chain.getArg(0);
            Object device = chain.getArg(1);
            Object handlerObject = chain.getThisObject();
            Context context = context(handlerObject);
            if (context == null || sbn == null || device == null) return result;
            try { maybeSendCatalog(handlerObject, device, context, false, false); } catch (Throwable t) { error("App list not sent", t); }
            String pkg = sbn.getPackageName();
            @SuppressWarnings("unchecked")
            List<String> listed = (List<String>) miPackages.invoke(null, context);
            boolean isListed = listed != null && listed.contains(pkg);
            boolean isNearby = (boolean) nearby.invoke(null, (int) call(device, "getDeviceType"));
            if (Boolean.TRUE.equals(result)) {
                // Only over the direct nearby link: the cloud relay may reject apps outside its
                // list and then drop all of that device's subscriptions.
                return !isListed && (!isNearby || blocked(pkg)) ? Boolean.FALSE : result;
            }
            if (blocked(pkg) || !forwardable(sbn) || (int) call(device, "getDeviceSwitch") != 1) return result;
            boolean isSubscribed = subscribed(device, pkg);
            // Listed apps follow the tablet's choice; they only need help when Xiaomi held them
            // back because the phone is in use.
            if (isListed) return whileInUse() && isSubscribed ? Boolean.TRUE : result;
            if (!isSubscribed && !enabled()) return result;
            if (!isNearby) return result;
            if (!whileInUse() && !lockedOrScreenOff(context)) return result;
            return true;
        });
        info("Forwarding the apps chosen on the tablet to nearby devices");
        hookTitleLabel(loader, miPackages);
    }

    /**
     * The receiving tablet may not have the app, so lead the forwarded title with the app name
     * ("WhatsApp: Ahmet"). Only for apps outside Xiaomi's list, which the tablet renders itself.
     */
    private void hookTitleLabel(ClassLoader loader, Method miPackages) throws Exception {
        Method build = null;
        for (Method m : Class.forName(BUILDER, false, loader).getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("buildNotificationMessage") && p.length == 4 && p[0] == StatusBarNotification.class) build = m;
        }
        if (build == null) throw new NoSuchMethodException("buildNotificationMessage");
        build.setAccessible(true);
        hook(build).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            StatusBarNotification sbn = (StatusBarNotification) chain.getArg(0);
            Context context = context(chain.getThisObject());
            if (sbn != null && context != null) {
                String pkg = sbn.getPackageName();
                @SuppressWarnings("unchecked")
                List<String> listed = (List<String>) miPackages.invoke(null, context);
                String label = AppCatalog.SELF.equals(pkg) || (listed != null && listed.contains(pkg)) ? null : label(context, pkg);
                CharSequence title = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                if (label != null && title != null && !title.toString().startsWith(label + ": "))
                    sbn.getNotification().extras.putCharSequence(Notification.EXTRA_TITLE, label + ": " + title);
            }
            return chain.proceed();
        });
    }

    private static final java.util.Map<String, String> sentCatalog = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String catalog;
    private static volatile String icons, iconsFor;

    /**
     * Send this phone's app list to a nearby subscribed device when the device has not seen it
     * yet, or when it asks ({@code force}). It travels as a regular notification message from
     * XNotify itself, which the tablet keeps for its app picker instead of showing.
     */
    private void maybeSendCatalog(Object handlerObject, Object device, Context context, boolean force,
            boolean offerIcons) throws Exception {
        String target = String.valueOf(call(device, "getDeviceId"));
        int deviceSwitch = (int) call(device, "getDeviceSwitch");
        if (deviceSwitch != 1) {
            if (reported.add("switch:" + target + ":" + deviceSwitch)) info("App list not sent: " + target + " has notifications switched off (" + deviceSwitch + ")");
            return;
        }
        Method nearby = Class.forName(NEARBY, false, handlerObject.getClass().getClassLoader())
                .getDeclaredMethod("isNearbySupported", int.class);
        int type = (int) call(device, "getDeviceType");
        if (!(boolean) nearby.invoke(null, type)) {
            if (reported.add("nearby:" + target)) info("App list not sent: " + target + " (type " + type + ") is not a nearby device");
            return;
        }
        long now = System.currentTimeMillis();
        // Scanned only at start and after an app is installed, updated or removed.
        if (catalog == null) catalog = AppCatalog.build(context);
        String deviceId = (String) call(device, "getDeviceId");
        // Without a request, only a list the device has not seen yet goes out.
        if (!force && catalog.equals(sentCatalog.get(deviceId))) return;
        sentCatalog.put(deviceId, catalog);
        java.lang.reflect.Constructor<StatusBarNotification> ctor = StatusBarNotification.class.getDeclaredConstructor(
                String.class, String.class, int.class, String.class, int.class, int.class, Notification.class,
                android.os.UserHandle.class, String.class, long.class);
        ctor.setAccessible(true);
        Method send = null;
        for (Method m : handlerObject.getClass().getDeclaredMethods())
            if (m.getName().equals("transNotificationToNearby") && m.getParameterCount() == 2) send = m;
        if (send == null) throw new NoSuchMethodException("transNotificationToNearby");
        send.setAccessible(true);
        // Icons are too big for this link; serve them on Wi-Fi and announce where in the list.
        // Only when the tablet asks, or for a list whose icons were never offered to it.
        String hash = Integer.toHexString(catalog.hashCode());
        String address = null;
        if (offerIcons || !hash.equals(AppCatalog.load(context, "iconsfor_" + deviceId))) {
            if (icons == null || !catalog.equals(iconsFor)) { icons = AppCatalog.buildIcons(context, AppCatalog.parse(catalog).keySet()); iconsFor = catalog; }
            address = IconServer.serve(icons);
            if (address != null) AppCatalog.save(context, "iconsfor_" + deviceId, hash);
        }
        String payload = (address != null ? AppCatalog.ICON_LINE + "\t" + address + "\n" : "") + catalog;
        // A stable id lets the tablet keep parts across rounds and fill in any that were dropped.
        // The id comes from the list alone (not the one-time icon key), so parts dropped in one
        // round are completed by the next one.
        sendParts(handlerObject, device, send, ctor, context, AppCatalog.MARKER, payload, hash, now);
        info("Sent the app list (" + AppCatalog.parse(catalog).size() + " apps" + (address != null ? ", icons offered" : "") + ") to " + deviceId);
    }

    /**
     * Sends a payload in parts as hidden XNotify messages. The text goes straight into the
     * extras: Notification.Builder cuts content text to about 1 KB.
     */
    private static void sendParts(Object handlerObject, Object device, Method send,
            java.lang.reflect.Constructor<StatusBarNotification> ctor, Context context, String kind,
            String payload, String id, long when) throws Exception {
        List<String> parts = AppCatalog.chunks(payload);
        for (int i = 0; i < parts.size(); i++) {
            Notification n = new Notification.Builder(context, "xnotify").setContentTitle(AppCatalog.partTitle(kind, i, parts.size(), id))
                    .setSmallIcon(android.R.drawable.stat_notify_more).build();
            n.extras.putCharSequence(Notification.EXTRA_TEXT, parts.get(i));
            StatusBarNotification sbn = ctor.newInstance(AppCatalog.SELF, AppCatalog.SELF, 0x584E + i, "xnotify_" + kind,
                    android.os.Process.myUid(), 0, n, android.os.Process.myUserHandle(), null, when + i);
            send.invoke(handlerObject, sbn, device);
            Thread.sleep(700);
        }
    }

    /** Right after the sync service starts, offer the app list to every subscribed device. */
    private void sendCatalogSoon(ClassLoader loader) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> new Thread(() -> {
            try {
                Context context = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                Class<?> handler = Class.forName(HANDLER, false, loader);
                Object instance = handler.getMethod("getInstance", Context.class).invoke(null, context);
                sendCatalogToAll(instance, context);
                watchPackages(instance, context);
                logLocalData(loader, context);
                wakeModule(context);
            } catch (Throwable t) {
                error("App list not sent at start", t);
            }
        }).start(), 20_000);
    }

    private static final java.util.Map<String, Long> requestedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The tablet asks the phone for its subscriptions whenever the app picker opens or its
     * refresh button is pressed; answer with a fresh app list and icons too.
     */
    private void hookCatalogRequest(ClassLoader loader) throws Exception {
        Class<?> message = Class.forName(PKG + "trans.NotifTransSvcMessage", false, loader);
        Method server = null;
        for (Method m : message.getDeclaredMethods())
            if (m.getName().equals("handleOnServerSubscribe") && m.getParameterCount() == 3) server = m;
        if (server == null) throw new NoSuchMethodException("handleOnServerSubscribe");
        server.setAccessible(true);
        hook(server).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            String deviceId = (String) chain.getArg(0);
            Object entity = chain.getArg(2);
            Object method = entity == null ? null : call(entity, "getMethodName");
            if (deviceId != null && reported.add("req:" + deviceId + ":" + method)) info("Request '" + method + "' from " + deviceId);
            if (deviceId != null && ("query".equals(method) || "sub".equals(method))) {
                long now = System.currentTimeMillis();
                Long last = requestedAt.put(deviceId, now);
                if (last == null || now - last > 5_000) {
                    new Thread(() -> {
                        try {
                            Thread.sleep(1_500);
                            Context context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                            Object handlerInstance = Class.forName(HANDLER, false, loader).getMethod("getInstance", Context.class).invoke(null, context);
                            Object subscriptions = field(handlerInstance, "mSubscriptionManager");
                            boolean known = false;
                            for (Object device : (List<?>) subscriptions.getClass().getMethod("getSubDeviceAppList").invoke(subscriptions))
                                if (deviceId.equals(call(device, "getDeviceId"))) { known = true; maybeSendCatalog(handlerInstance, device, context, true, true); }
                            if (!known) info("App list not sent: " + deviceId + " has no subscription on this phone yet");
                        } catch (Throwable t) {
                            error("App list request not answered", t);
                        }
                    }).start();
                }
            }
            return result;
        });
    }

    private void sendCatalogToAll(Object handlerInstance, Context context) throws Exception {
        Object subscriptions = field(handlerInstance, "mSubscriptionManager");
        if (subscriptions == null) return;
        for (Object device : (List<?>) subscriptions.getClass().getMethod("getSubDeviceAppList").invoke(subscriptions))
            maybeSendCatalog(handlerInstance, device, context, true, false);
    }

    /** Installing, updating or removing an app refreshes the tablet's list within about ten seconds. */
    private void watchPackages(Object handlerInstance, Context context) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable refresh = () -> new Thread(() -> {
            try {
                catalog = null;
                sendCatalogToAll(handlerInstance, context);
            } catch (Throwable t) {
                error("App list refresh failed", t);
            }
        }).start();
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent intent) {
                main.removeCallbacks(refresh);
                main.postDelayed(refresh, 10_000);
            }
        }, filter, Context.RECEIVER_EXPORTED);
    }

    private static boolean subscribed(Object device, String pkg) throws Exception {
        for (Object app : (List<?>) call(device, "getSubscriptionAppInfoList"))
            if (pkg.equals(call(app, "getPackageName"))) return true;
        return false;
    }

    private static boolean forwardable(StatusBarNotification sbn) {
        if (sbn == null || EXCLUDED.contains(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & SKIP_FLAGS) != 0 || !sbn.isClearable()) return false;
        if (n.category != null && EXCLUDED_CATEGORIES.contains(n.category)) return false;
        // Media playback controls.
        return !n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }

    private static boolean lockedOrScreenOff(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        return keyguard != null && power != null && (keyguard.isKeyguardLocked() || !power.isInteractive());
    }

    // --- Receiving tablet ----------------------------------------------------------------------

    private void hookReceiver(ClassLoader loader) throws Exception {
        hookReceiveFilter(loader);
        hookCatalogReceive(loader);
        try { hookPresentation(loader); } catch (Throwable t) { error("Notification look hook unavailable", t); }
        info("Accepting forwarded notifications from every app");
    }

    /**
     * The receiving side drops apps it has not subscribed to. Accept every app from a device
     * this one does subscribe to; the listed apps still follow the user's choice, and the
     * same-account check before this one stays in place.
     */
    private void hookReceiveFilter(ClassLoader loader) throws Exception {
        Class<?> processor = Class.forName(PROCESSOR, false, loader);
        // Apps outside Xiaomi's list have no display type and are dropped; show them as plain
        // notifications (type 1, the same as Xiaomi uses for its own apps without mirroring).
        for (Method m : processor.getDeclaredMethods()) {
            if (!m.getName().equals("getNotificationShowType") || m.getReturnType() != int.class) continue;
            m.setAccessible(true);
            hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object type = chain.proceed();
                return Integer.valueOf(-1).equals(type) ? 1 : type;
            });
        }
        Method miPackages = Class.forName(APP_PKG_ID, false, loader).getDeclaredMethod("getMiPackageList", Context.class);
        Method target = processor.getDeclaredMethod("isAppSubscribe", String.class, String.class);
        target.setAccessible(true);
        hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            String deviceId = (String) chain.getArg(0);
            String pkg = (String) chain.getArg(1);
            if (AppCatalog.SELF.equals(pkg)) return true;
            if (Boolean.TRUE.equals(result) || !enabled()) return result;
            Object owner = chain.getThisObject();
            Context context = context(owner);
            Object subscriptions = field(owner, "mSubscriptionManager");
            if (context == null || subscriptions == null || pkg == null) return result;
            if (subscriptions.getClass().getMethod("getSubDeviceApp", String.class).invoke(subscriptions, deviceId) == null)
                return result;
            @SuppressWarnings("unchecked")
            List<String> listed = (List<String>) miPackages.invoke(null, context);
            if (listed != null && listed.contains(pkg)) return result;
            // Installed here too: the app notifies on its own, a forwarded copy would duplicate it.
            if (dedupe() && label(context, pkg) != null) return result;
            return true;
        });
    }

    /** What the tablet is currently showing, so the posted notification can be reshaped. */
    private record Shown(String pkg, String title, String device) { }
    private static final ThreadLocal<Shown> showing = new ThreadLocal<>();

    /**
     * Forwarded apps outside Xiaomi's list look like their own notifications: the app's icon,
     * its own title and text, and the phone's name (any phone or tablet) on the top line.
     * Xiaomi's listed apps (SMS, calls...) keep Xiaomi's layout.
     */
    private void hookPresentation(ClassLoader loader) throws Exception {
        Class<?> controller = Class.forName(PKG + "handler.NotificationController", false, loader);
        Method fromPackage = Class.forName(APP_PKG_ID, false, loader).getDeclaredMethod("fromPackageName", Context.class, String.class);
        Method show = null;
        for (Method m : controller.getDeclaredMethods())
            if (m.getName().equals("showNotification") && m.getParameterCount() == 1) show = m;
        if (show == null) throw new NoSuchMethodException("showNotification");
        hook(show).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object data = chain.getArg(0);
            Context context = context(chain.getThisObject());
            String pkg = data == null ? null : (String) call(data, "appIdentifier");
            boolean unlisted = context != null && pkg != null && !AppCatalog.SELF.equals(pkg)
                    && "UNKNOWN".equals(((Enum<?>) fromPackage.invoke(null, context, pkg)).name());
            if (!unlisted) return chain.proceed();
            Object source = call(data, "sourceDeviceInfo");
            showing.set(new Shown(pkg, (String) call(data, "title"), source == null ? null : (String) call(source, "deviceName")));
            try { return chain.proceed(); } finally { showing.remove(); }
        });
        Method notify = android.app.NotificationManager.class.getMethod("notify", String.class, int.class, android.app.Notification.class);
        hook(notify).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Shown shown = showing.get();
            Object[] args = chain.getArgs().toArray();
            if (shown == null || !(args[2] instanceof Notification original)) return chain.proceed();
            Context context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            Notification.Builder builder = Notification.Builder.recoverBuilder(context, original);
            // The phone leads the title with the app name for older receivers; the icon says it now.
            String title = shown.title();
            String label = appLabel(context, shown.pkg());
            if (title != null && label != null && title.startsWith(label + ": ")) title = title.substring(label.length() + 2);
            // HyperOS hides the sub text on the lock screen, so the source device leads the title.
            if (title != null && original.extras.getCharSequence(Notification.EXTRA_TITLE) != null)
                builder.setContentTitle(shown.device() != null ? shown.device() + " · " + title : title);
            // HyperOS message notifications draw "hyperOs.msgProfile" as the big round icon and the
            // posting app (Interconnectivity) as the small badge, as Xiaomi does for SMS and calls.
            android.graphics.drawable.Drawable icon = AppCatalog.icon(context, shown.pkg(), 192);
            if (icon instanceof android.graphics.drawable.BitmapDrawable b) {
                builder.setLargeIcon(android.graphics.drawable.Icon.createWithBitmap(b.getBitmap()));
                android.os.Bundle extra = new android.os.Bundle();
                extra.putParcelable("hyperOs.msgProfile", b.getBitmap());
                builder.addExtras(extra);
            }
            args[2] = builder.build();
            return chain.proceed(args);
        });
        info("Forwarded notifications show the app icon and the source device");
    }

    /** App name from this device, else from the phone's list. */
    private static String appLabel(Context context, String pkg) {
        String own = label(context, pkg);
        if (own != null) return own;
        java.io.File dir = context.getFilesDir();
        java.io.File[] lists = dir.listFiles((d, n) -> n.startsWith("xnotify_apps_"));
        if (lists != null) for (java.io.File f : lists) {
            try {
                String name = AppCatalog.parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)).get(pkg);
                if (name != null) return name;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean fetchingIcons = new java.util.concurrent.atomic.AtomicBoolean();

    /** Keep the phone's app list for the picker instead of showing it as a notification. */
    private void hookCatalogReceive(ClassLoader loader) throws Exception {
        Class<?> presenter = Class.forName(PKG + "handler.NotificationPresenter", false, loader);
        Method handle = null;
        for (Method m : presenter.getDeclaredMethods())
            if (m.getName().equals("handleNotification") && m.getParameterCount() == 1) handle = m;
        if (handle == null) throw new NoSuchMethodException("handleNotification");
        hook(handle).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object data = chain.getArg(0);
            if (data == null || !AppCatalog.SELF.equals(call(data, "appIdentifier"))) return chain.proceed();
            Object title = call(data, "title");
            if (title instanceof String t && t.startsWith(AppCatalog.MARKER + "|")) {
                Context context = context(chain.getThisObject());
                String deviceId = (String) call(call(data, "sourceDeviceInfo"), "deviceId");
                String payload = (String) call(data, "message");
                if (context != null && deviceId != null && payload != null) {
                    String full = AppCatalog.receivePart(context, deviceId, t, payload);
                    String address = AppCatalog.iconAddress(full);
                    if (full != null && address != null && AppCatalog.missingIcons(context, AppCatalog.parse(full).keySet())
                            && fetchingIcons.compareAndSet(false, true)) {
                        new Thread(() -> {
                          try {
                            String icons = IconServer.fetch(address);
                            if (icons == null) { info("Icons not fetched from the phone"); return; }
                            AppCatalog.saveIcons(context, icons);
                            info("Fetched " + AppCatalog.parse(icons).size() + " icons over Wi-Fi");
                          } finally { fetchingIcons.set(false); }
                        }).start();
                    }
                    if (full != null) info("Received the app list of " + deviceId + " (" + AppCatalog.parse(full).size() + " apps)");
                }
            }
            return null;
        });
    }

    // --- Tablet app picker ---------------------------------------------------------------------

    /**
     * Xiaomi's per-phone app picker only offers seven Chinese apps. Add every app of that phone
     * (from the list it sent); ticking one subscribes to it through Xiaomi's own mechanism.
     */
    private void hookAppPicker(ClassLoader loader) throws Exception {
        Class<?> fragment = Class.forName(PKG + "setting.SubscriptionAppFragment", false, loader);
        Class<?> boxType = Class.forName(PKG + "setting.preference.SubscriptionCheckBoxPreference", false, loader);
        Method init = fragment.getDeclaredMethod("initPreference");
        Method setup = fragment.getDeclaredMethod("setupCheckBoxPreference", boxType, String.class);
        setup.setAccessible(true);
        init.setAccessible(true);
        hook(init).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            Context context = (Context) invoke(owner, "getContext");
            Object device = field(owner, "mDeviceInfo");
            Object sms = invoke(owner, "findPreference", "check_box_pref_key_app_sms");
            if (context == null || device == null || sms == null) {
                info("App picker skipped: context=" + (context != null) + " device=" + (device != null) + " sms=" + (sms != null));
                return result;
            }
            Object group = invoke(sms, "getParent");
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> wired = (java.util.Map<String, String>) field(owner, "mSubscriptionCheckBoxMap");
            String deviceId = (String) call(device, "getDeviceId");
            java.util.Map<String, String> apps = AppCatalog.parse(AppCatalog.load(context, deviceId));
            // Xiaomi fills this comment row only for tablet-to-tablet sync; for a phone it is an empty gap.
            Object comment = invoke(owner, "findPreference", "pref_key_list_comment");
            if (comment != null && (int) call(device, "getDeviceType") != 2) invoke(comment, "setVisible", false);
            android.app.Activity activity = (android.app.Activity) invoke(owner, "requireActivity");
            String info = listInfo(context, deviceId, apps.size());
            activity.getWindow().getDecorView().post(() -> addRefreshIcon(activity, owner, info));
            if (apps.isEmpty()) {
                Object hint = Class.forName("androidx.preference.Preference", false, loader).getConstructor(Context.class).newInstance(context);
                invoke(hint, "setKey", "xnotify_hint");
                invoke(hint, "setTitle", Texts.of(context).hintTitle);
                invoke(hint, "setSummary", Texts.of(context).hintSummary);
                invoke(hint, "setSelectable", false);
                addToGroup(group, hint);
                return result;
            }
            // Same icon size as Xiaomi's own rows (Phone, Messaging).
            int iconPx = Math.round(40 * context.getResources().getDisplayMetrics().density);
            Object ownIcon = invoke(sms, "getIcon");
            if (ownIcon instanceof android.graphics.drawable.Drawable d && d.getIntrinsicWidth() > 0) iconPx = d.getIntrinsicWidth();
            for (java.util.Map.Entry<String, String> app : apps.entrySet()) {
                if (wired != null && wired.containsValue(app.getKey())) continue;
                Object box = boxType.getConstructor(Context.class).newInstance(context);
                invoke(box, "setKey", "xnotify_app_" + app.getKey());
                invoke(box, "setTitle", app.getValue());
                android.graphics.drawable.Drawable icon = AppCatalog.icon(context, app.getKey(), iconPx);
                if (icon != null) invoke(box, "setIcon", icon);
                invoke(box, "setPersistent", false);
                if (label(context, app.getKey()) != null) invoke(box, "setSummary", Texts.of(context).alsoHere);
                addToGroup(group, box);
                setup.invoke(owner, box, app.getKey());
            }
            info("Added " + apps.size() + " phone apps to the picker");
            return result;
        });
    }

    /** "174 apps · last updated …", shown under the screen title. */
    private static String listInfo(Context context, String deviceId, int count) {
        Texts texts = Texts.of(context);
        java.io.File list = new java.io.File(context.getFilesDir(), "xnotify_apps_" + deviceId.replaceAll("[^A-Za-z0-9]", "_"));
        String when = list.isFile()
                ? java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, context.getResources().getConfiguration().getLocales().get(0)).format(new java.util.Date(list.lastModified()))
                : texts.never;
        return texts.summary(count, when);
    }

    /**
     * Top right of the app picker: "turn all off" and refresh (asks the phone through Xiaomi's own
     * subscription query, which XNotify on the phone answers with its app list and icons, then
     * reopens the screen). The list size and age go under the title, and a search box above the
     * list filters the apps by name.
     */
    private void addRefreshIcon(android.app.Activity activity, Object fragment, String info) {
        try {
            android.view.View bar = find(activity.getWindow().getDecorView(), "miuix.appcompat.internal.app.widget.ActionBarView");
            if (bar == null) { info("Refresh icon skipped: no action bar"); return; }
            Texts texts = Texts.of(activity);
            float dp = activity.getResources().getDisplayMetrics().density;
            int pad = Math.round(8 * dp);
            int color = textColor(activity);

            android.widget.ImageView off = new android.widget.ImageView(activity);
            off.setImageDrawable(allOffGlyph(activity, color));
            off.setContentDescription(texts.allOff);
            off.setPadding(pad, pad, pad, pad);
            off.setOnClickListener(v -> confirmAllOff(activity, fragment, texts));

            android.widget.ImageView icon = new android.widget.ImageView(activity);
            icon.setImageDrawable(refreshGlyph(activity));
            icon.setContentDescription(texts.refresh);
            icon.setPadding(pad, pad, pad, pad);
            icon.setOnClickListener(v -> {
                try {
                    invoke(fragment, "querySubscribeInfo");
                    bar.getClass().getMethod("setSubtitle", CharSequence.class).invoke(bar, texts.requesting);
                    v.postDelayed(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
                    }, 8_000);
                } catch (Throwable t) {
                    error("Refresh failed", t);
                }
            });

            android.widget.LinearLayout actions = new android.widget.LinearLayout(activity);
            actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
            actions.addView(off);
            actions.addView(icon);
            bar.getClass().getMethod("setEndView", android.view.View.class).invoke(bar, actions);
            bar.getClass().getMethod("setSubtitle", CharSequence.class).invoke(bar, info);
            addSearch(activity, fragment, texts, color);
        } catch (Throwable t) {
            error("Refresh icon unavailable", t);
        }
    }

    /** The picker's app rows (Xiaomi's and XNotify's) with their package names. */
    private static java.util.Map<Object, String> appRows(Object fragment) throws Exception {
        java.util.Map<Object, String> rows = new java.util.LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> wired = (java.util.Map<String, String>) field(fragment, "mSubscriptionCheckBoxMap");
        if (wired == null) return rows;
        for (java.util.Map.Entry<String, String> e : wired.entrySet()) {
            Object row = invoke(fragment, "findPreference", e.getKey());
            if (row != null) rows.put(row, e.getValue());
        }
        return rows;
    }

    /** A search box above the list; typing hides the apps whose name does not match. */
    private void addSearch(android.app.Activity activity, Object fragment, Texts texts, int color) throws Exception {
        android.view.View list = activity.findViewById(android.R.id.list_container);
        if (list == null || !(list.getParent() instanceof android.widget.FrameLayout holder)) { info("Search skipped: no list"); return; }
        android.view.View recycler = find(list, "androidx.recyclerview.widget.RecyclerView");
        if (recycler == null) { info("Search skipped: no recycler"); return; }
        float dp = activity.getResources().getDisplayMetrics().density;
        // Line the box up with the list's rounded card.
        int side = Math.round(16 * dp);
        if (recycler instanceof android.view.ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++)
                if (g.getChildAt(i).getClass().getName().contains("HyperCellLayout")) { side = g.getChildAt(i).getLeft(); break; }

        android.widget.EditText box = new android.widget.EditText(activity);
        box.setHint(texts.search);
        box.setSingleLine(true);
        box.setTextColor(color);
        box.setHintTextColor((color & 0x00FFFFFF) | 0x80000000);
        box.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), 0);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor((color & 0x00FFFFFF) | 0x14000000);
        shape.setCornerRadius(24 * dp);
        box.setBackground(shape);
        android.graphics.drawable.Drawable glass = searchGlyph(activity, (color & 0x00FFFFFF) | 0x99000000);
        box.setCompoundDrawablesRelativeWithIntrinsicBounds(glass, null, null, null);
        box.setCompoundDrawablePadding(Math.round(10 * dp));

        android.widget.FrameLayout strip = new android.widget.FrameLayout(activity);
        strip.setBackgroundColor(windowColor(activity));
        int height = Math.round(48 * dp), gap = Math.round(8 * dp);
        android.widget.FrameLayout.LayoutParams boxParams = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height);
        boxParams.setMargins(side, gap, side, gap);
        strip.addView(box, boxParams);
        android.widget.FrameLayout.LayoutParams stripParams = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height + 2 * gap);
        holder.addView(strip, stripParams);
        // Xiaomi pads and shifts the list below the title bar without a layout event, so check
        // before each frame (cheap, and it never skips one): keep the strip where the first row
        // starts and the rows below the strip. The overscroll bounce is left out, so the strip
        // stays still while scrolling.
        int extra = height + 2 * gap;
        int[] applied = {-1}, base = {0};
        recycler.getViewTreeObserver().addOnPreDrawListener(() -> {
            int current = recycler.getPaddingTop();
            if (current != applied[0]) {
                boolean atTop = !recycler.canScrollVertically(-1);
                base[0] = current;
                applied[0] = current + extra;
                recycler.setPadding(recycler.getPaddingLeft(), applied[0], recycler.getPaddingRight(), recycler.getPaddingBottom());
                // The list keeps its scroll position when padded; show the first row again.
                if (atTop) recycler.post(() -> recycler.scrollBy(0, -100_000));
            }
            int[] at = new int[2], from = new int[2];
            holder.getLocationOnScreen(from);
            recycler.getLocationOnScreen(at);
            int bounce = Math.round(list.getTranslationY() + recycler.getTranslationY()) - list.getScrollY();
            int top = at[1] - bounce + base[0] - from[1];
            if (stripParams.topMargin != top) { stripParams.topMargin = top; strip.setLayoutParams(stripParams); }
            return true;
        });

        box.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence q, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence q, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable q) {
                String query = q.toString().trim().toLowerCase(java.util.Locale.ROOT);
                try {
                    for (Object row : appRows(fragment).keySet()) {
                        Object title = invoke(row, "getTitle");
                        boolean match = query.isEmpty() || (title != null && title.toString().toLowerCase(java.util.Locale.ROOT).contains(query));
                        invoke(row, "setVisible", match);
                    }
                } catch (Throwable t) {
                    error("Search failed", t);
                }
            }
        });
    }

    /** Asks first, then unsubscribes every app that is on, through Xiaomi's own switch handling. */
    private void confirmAllOff(android.app.Activity activity, Object fragment, Texts texts) {
        try {
            java.util.List<Object[]> on = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Object, String> row : appRows(fragment).entrySet())
                if (Boolean.TRUE.equals(invoke(row.getKey(), "isChecked"))) on.add(new Object[]{row.getKey(), row.getValue()});
            if (on.isEmpty()) {
                android.widget.Toast.makeText(activity, texts.noneOn, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Method unsubscribe = null;
            for (Method m : fragment.getClass().getDeclaredMethods())
                if (m.getName().equals("handleUnSubscribe") && m.getParameterCount() == 2) unsubscribe = m;
            if (unsubscribe == null) throw new NoSuchMethodException("handleUnSubscribe");
            unsubscribe.setAccessible(true);
            Method run = unsubscribe;
            boolean night = (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            new android.app.AlertDialog.Builder(activity, night
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle(texts.allOffTitle)
                    .setMessage(String.format(texts.allOffMessage, on.size()))
                    .setNegativeButton(texts.cancel, null)
                    .setPositiveButton(texts.allOff, (d, w) -> {
                        for (Object[] row : on) {
                            try { run.invoke(fragment, row[0], row[1]); } catch (Throwable t) { error("Turn off failed for " + row[1], t); }
                        }
                        info("Turned off " + on.size() + " apps");
                    })
                    .show();
        } catch (Throwable t) {
            error("Turn all off unavailable", t);
        }
    }

    /** The screen's own background, so the search strip blends in (dark or light theme). */
    private static int windowColor(android.app.Activity activity) {
        android.graphics.drawable.Drawable bg = activity.getWindow().getDecorView().getBackground();
        if (bg instanceof android.graphics.drawable.ColorDrawable cd) return cd.getColor() | 0xFF000000;
        boolean night = (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static int textColor(Context context) {
        android.content.res.TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        int color = a.getColor(0, 0xFF000000);
        a.recycle();
        return color;
    }

    /** A switch in the off position: "turn all off". */
    private static android.graphics.drawable.Drawable allOffGlyph(Context context, int color) {
        int px = Math.round(26 * context.getResources().getDisplayMetrics().density);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(android.graphics.Paint.Style.STROKE);
        stroke.setStrokeWidth(px * 0.08f);
        float top = px * 0.3f, bottom = px * 0.7f, r = (bottom - top) / 2;
        c.drawRoundRect(px * 0.08f, top, px * 0.92f, bottom, r, r, stroke);
        android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        c.drawCircle(px * 0.08f + r, px * 0.5f, r * 0.55f, fill);
        b.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        return new android.graphics.drawable.BitmapDrawable(context.getResources(), b);
    }

    private static android.graphics.drawable.Drawable searchGlyph(Context context, int color) {
        int px = Math.round(20 * context.getResources().getDisplayMetrics().density);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(android.graphics.Paint.Style.STROKE);
        stroke.setStrokeWidth(px * 0.1f);
        stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        c.drawCircle(px * 0.42f, px * 0.42f, px * 0.28f, stroke);
        c.drawLine(px * 0.63f, px * 0.63f, px * 0.88f, px * 0.88f, stroke);
        b.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        return new android.graphics.drawable.BitmapDrawable(context.getResources(), b);
    }

    private static android.view.View find(android.view.View view, String className) {
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass())
            if (c.getName().equals(className)) return view;
        if (view instanceof android.view.ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = find(g.getChildAt(i), className);
                if (found != null) return found;
            }
        return null;
    }

    /** A plain circular-arrow refresh glyph in the screen's text color, like Xiaomi's action icons. */
    private static android.graphics.drawable.Drawable refreshGlyph(Context context) {
        android.content.res.TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        int color = a.getColor(0, 0xFF000000);
        a.recycle();
        int px = Math.round(26 * context.getResources().getDisplayMetrics().density);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(android.graphics.Paint.Style.STROKE);
        stroke.setStrokeWidth(px * 0.09f);
        stroke.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        float inset = px * 0.16f;
        android.graphics.RectF arc = new android.graphics.RectF(inset, inset, px - inset, px - inset);
        c.drawArc(arc, -60, 300, false, stroke);
        double end = Math.toRadians(-60);
        float cx = arc.centerX() + (float) (Math.cos(end) * arc.width() / 2), cy = arc.centerY() + (float) (Math.sin(end) * arc.height() / 2);
        android.graphics.Path head = new android.graphics.Path();
        float h = px * 0.17f;
        head.moveTo(cx - h * 0.2f, cy - h);
        head.lineTo(cx + h * 0.9f, cy + h * 0.15f);
        head.lineTo(cx - h * 0.6f, cy + h * 0.55f);
        head.close();
        android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        c.drawPath(head, fill);
        b.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        return new android.graphics.drawable.BitmapDrawable(context.getResources(), b);
    }

    /**
     * PreferenceGroup.addPreference is obfuscated in this app; its void single-Preference
     * wrapper is the one method of that shape on the class.
     */
    private static void addToGroup(Object group, Object preference) throws Exception {
        for (Class<?> c = group.getClass(); c != null; c = c.getSuperclass()) {
            if (!c.getName().equals("androidx.preference.PreferenceGroup")) continue;
            Class<?> prefType = Class.forName("androidx.preference.Preference", false, c.getClassLoader());
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 1 && p[0] == prefType
                        && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    m.invoke(group, preference);
                    return;
                }
            }
        }
        invoke(group, "addPreference", preference);
    }

    /** Calls a public or declared method by name with matching argument count. */
    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] types = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < args.length && ok; i++) {
                    Class<?> t = types[i].isPrimitive() ? box(types[i]) : types[i];
                    ok = args[i] == null || t.isInstance(args[i]);
                }
                if (!ok) continue;
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Class<?> box(Class<?> t) {
        if (t == boolean.class) return Boolean.class;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        return t;
    }

    /** App name, when the same app is installed on this device. */
    private static String label(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }


    // --- Helpers -------------------------------------------------------------------------------

    private static Context context(Object owner) {
        if (owner == null) return null;
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object value = f.get(owner);
                        if (value != null) return (Context) value;
                    } catch (IllegalAccessException ignored) { }
                }
            }
        }
        return null;
    }

    private static Object field(Object owner, String name) {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    private void info(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    private void error(String message, Throwable t) {
        Log.e(TAG, message, t);
        log(Log.ERROR, TAG, message, t);
    }
}
