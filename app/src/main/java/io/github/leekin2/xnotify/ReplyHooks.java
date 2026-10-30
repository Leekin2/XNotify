package io.github.leekin2.xnotify;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * HyperOS System UI has its own reply box (miui_remote_input) and action buttons, but only uses
 * them for notifications it draws itself. Notifications drawn with Android's templates, which is
 * every MessagingStyle app (Telegram, WhatsApp, SMS...), get the AOSP reply box and pill buttons.
 * Use Xiaomi's look for those too. (Formerly the separate XReply module.)
 */
final class ReplyHooks {
    private static final String TAG = "XNotify";
    /** The old standalone module; when it is still active its own hooks already do this. */
    static final String XREPLY = "io.github.leekin2.xreply";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String SB = "com.android.systemui.statusbar.";
    private static final String ROW = SB + "notification.row.ExpandableNotificationRow";
    private static final String REMOTE_INPUT_VIEW = SB + "policy.RemoteInputView";
    private static final String INJECTOR = SB + "notification.policy.RemoteInputViewInjectorImp";
    private static final String WRAPPER = SB + "notification.row.wrapper.NotificationViewWrapper";
    private static final String WRAPPER_INJECTOR = SB + "notification.row.wrapper.NotificationViewWrapperInjector";

    /** System UI's layout ids, resolved on first use: [AOSP remote_input, miui_remote_input]. */
    private volatile int[] layouts;

    private final XposedInterface xposed;

    ReplyHooks(XposedInterface xposed) {
        this.xposed = xposed;
    }

    void install(ClassLoader loader) {
        try { hookReplyBox(loader); } catch (Throwable t) { error("Reply box hook unavailable", t); }
        try { hookActionButtons(loader); } catch (Throwable t) { error("Action button hook unavailable", t); }
        try { hookSentReply(loader); } catch (Throwable t) { error("Sent reply hook unavailable", t); }
        try { hookCountPill(); } catch (Throwable t) { error("Count pill hook unavailable", t); }
        try { hookBubbleButton(loader); } catch (Throwable t) { error("Bubble button hook unavailable", t); }
    }

    private XposedInterface.HookBuilder hook(Method method) {
        return xposed.hook(method);
    }

    // --- Reply box -----------------------------------------------------------------------------

    private void hookReplyBox(ClassLoader loader) throws Exception {
        // NotificationContentView.applyRemoteInput inflates remote_input for Android templates and
        // miui_remote_input otherwise; remote_input is inflated nowhere else.
        Method inflate = LayoutInflater.class.getDeclaredMethod("inflate", int.class, ViewGroup.class, boolean.class);
        hook(inflate).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            int id = (int) chain.getArg(0);
            int[] ids = layouts((LayoutInflater) chain.getThisObject());
            if (ids == null || id != ids[0]) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            args[0] = ids[1];
            return chain.proceed(args);
        });

        // Animations, margins and the box background follow this check; keep them on Xiaomi's side.
        Method showMiuiStyle = Class.forName(INJECTOR, false, loader)
                .getDeclaredMethod("showMiuiStyle", Class.forName(ROW, false, loader));
        hook(showMiuiStyle).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> true);

        // With the Xiaomi style the box fades out the action row by System UI's own id, which
        // Android's templates do not have; fall back to the framework's row.
        Class<?> remoteInputView = Class.forName(REMOTE_INPUT_VIEW, false, loader);
        Method actions = remoteInputView.getDeclaredMethod("getActionsContainerLayout");
        int frameworkRow = Resources.getSystem().getIdentifier("actions_container_layout", "id", "android");
        hook(actions).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object found = chain.proceed();
            if (found != null || frameworkRow == 0) return found;
            ViewGroup parent = (ViewGroup) ((View) chain.getThisObject()).getParent();
            return parent == null ? null : parent.findViewById(frameworkRow);
        });

        Method finish = remoteInputView.getDeclaredMethod("onFinishInflate");
        Field injectorField = remoteInputView.getDeclaredField("mInjector");
        Field contentField = remoteInputView.getDeclaredField("mContentView");
        Field sendField = remoteInputView.getDeclaredField("mSendButton");
        Field progressField = remoteInputView.getDeclaredField("mProgressBar");
        progressField.setAccessible(true);
        Field editField = remoteInputView.getDeclaredField("mEditText");
        editField.setAccessible(true);
        Field miuiBgField = Class.forName(INJECTOR, false, loader).getDeclaredField("miuiContentBackground");
        for (Field f : new Field[] {injectorField, contentField, sendField, miuiBgField}) f.setAccessible(true);

        // For Android templates System UI paints the AOSP rounded slab behind the box (Xiaomi's
        // own notifications have none) and tints the arrow with the app colour, which disappears
        // on the grey button in the light theme.
        Method tint = remoteInputView.getDeclaredMethod("setBackgroundTintColor", int.class, boolean.class);
        hook(tint).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            view.setBackground(null);
            // HyperOS picks white or black text per notification (lock screen, wallpaper colours),
            // which need not match the system theme; follow the notification's own text.
            boolean light = lightInk(view, night(view.getContext()));
            Object injector = injectorField.get(view);
            if (injector != null) reshapeBox(miuiBgField.get(injector), view.getContext(), light);
            View content = (View) contentField.get(view);
            if (content != null) reshapeBox(content.getBackground(), view.getContext(), light);
            Object edit = editField.get(view);
            if (edit instanceof TextView) {
                ((TextView) edit).setTextColor(light ? 0xE6FFFFFF : 0xE6000000);
                ((TextView) edit).setHintTextColor(light ? 0x99FFFFFF : 0x8C000000);
            }
            Object send = sendField.get(view);
            if (send instanceof ImageView) {
                // Empty box: dim icon on the frosted grey circle; text typed: white on blue.
                ((ImageView) send).setBackground(sendBackground(view.getContext(), light));
                ((ImageView) send).setImageTintList(new ColorStateList(
                        new int[][] {{android.R.attr.state_enabled}, {}},
                        new int[] {0xFFFFFFFF, light ? 0x80FFFFFF : 0x73000000}));
            }
            Object progress = progressField.get(view);
            if (progress instanceof ProgressBar) {
                ((ProgressBar) progress).setIndeterminateTintList(ColorStateList.valueOf(light ? 0xE6FFFFFF : 0xCC000000));
                ((ProgressBar) progress).setBackground(circle(surface(view.getContext(), light, 0x40FFFFFF, 0x33000000, 0x26000000), 0));
            }
            return result;
        });

        // Xiaomi's box with softer edges: a full pill with a grey outline instead of blue, and a
        // larger grey send button.
        hook(finish).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            Context context = view.getContext();
            Object injector = injectorField.get(view);
            if (injector != null) reshapeBox(miuiBgField.get(injector), context, night(context));
            View content = (View) contentField.get(view);
            if (content != null) reshapeBox(content.getBackground(), context, night(context));
            View send = (View) sendField.get(view);
            View progress = (View) progressField.get(view);
            if (send instanceof ImageView) {
                ((ImageView) send).setImageDrawable(planeIcon(context));
                ((ImageView) send).setScaleType(ImageView.ScaleType.CENTER);
            }
            if (send != null) {
                send.setBackground(sendBackground(context, night(context)));
                frost(send, context);
            }
            // While sending, the spinner turns inside the same frosted circle instead of a bare
            // accent-coloured ring next to the box.
            if (progress != null) {
                progress.setBackground(circle(night(context) ? 0x40FFFFFF : 0x26000000, 0));
                frost(progress, context);
            }
            if (content != null && send != null) matchSendToBox(content, send, progress);
            return result;
        });
        info("Reply box: Xiaomi style for every notification");
    }

    private static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void reshapeBox(Object background, Context context, boolean light) {
        if (!(background instanceof GradientDrawable)) return;
        GradientDrawable box = (GradientDrawable) ((GradientDrawable) background).mutate();
        float dp = context.getResources().getDisplayMetrics().density;
        box.setCornerRadius(100 * dp);
        box.setColor(surface(context, light, 0x1AFFFFFF, 0x26000000, 0x0F000000));
        box.setStroke(Math.round(2 * dp), surface(context, light, 0x40FFFFFF, 0x66FFFFFF, 0x29000000));
    }

    /** True when the notification around {@code view} draws light (white) text. */
    private static boolean lightInk(View view, boolean fallback) {
        View title = findTitle(view);
        return title instanceof TextView ? lightInk(((TextView) title).getCurrentTextColor()) : fallback;
    }

    private static boolean lightInk(int color) {
        return Color.luminance(color | 0xFF000000) > 0.5f;
    }

    /** The notification title, searched outward from {@code from}. */
    private static View findTitle(View from) {
        View v = from;
        for (int i = 0; i < 8 && v != null; i++) {
            View title = v.findViewById(android.R.id.title);
            if (title != null) return title;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    /** Xiaomi's own send blue (notification_reply_send_bg_enabled). */
    private static final int SEND_BLUE = 0xFF0D84FF;

    /** Material "send" paper plane, 22dp, tinted by the button state. */
    private static Drawable planeIcon(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        int size = Math.round(22 * dp);
        Path plane = new Path();
        plane.moveTo(2.01f, 21f);
        plane.lineTo(23f, 12f);
        plane.lineTo(2.01f, 3f);
        plane.lineTo(2f, 10f);
        plane.lineTo(17f, 12f);
        plane.lineTo(2f, 14f);
        plane.close();
        // Centre the plane's bounds (x 2..23, y 3..21) exactly on the 24x24 canvas.
        plane.offset(-0.5f, 0f);
        ShapeDrawable icon = new ShapeDrawable(new PathShape(plane, 24, 24));
        icon.setIntrinsicWidth(size);
        icon.setIntrinsicHeight(size);
        icon.getPaint().setColor(0xFFFFFFFF);
        return icon;
    }

    private static StateListDrawable sendBackground(Context context, boolean light) {
        int size = Math.round(40 * context.getResources().getDisplayMetrics().density);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_enabled}, circle(SEND_BLUE, size));
        states.addState(new int[0], circle(surface(context, light, 0x1FFFFFFF, 0x33000000, 0x14000000), size));
        return states;
    }

    /**
     * Fill behind light or dark text. White text in the dark theme sits on a faint white veil.
     * The lock screen keeps white text in the light theme too, on a light card where a white veil
     * vanishes; there a dark veil keeps it readable.
     */
    private static int surface(Context context, boolean lightInk, int onDark, int onLight, int darkInk) {
        if (!lightInk) return darkInk;
        return night(context) ? onDark : onLight;
    }

    /** Centre the box and the send button on one line, the button as tall as the box. */
    private static void matchSendToBox(View content, View send, View progress) {
        ViewGroup.LayoutParams boxParams = content.getLayoutParams();
        if (boxParams instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) boxParams).gravity = Gravity.CENTER_VERTICAL;
            content.setLayoutParams(boxParams);
        }
        // A size change requested from inside a layout pass can be dropped, which left the circle
        // a few pixels short of the box; apply it on the next frame instead.
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(() -> {
            int size = v.getHeight();
            if (size <= 0 || (send.getWidth() == size && send.getHeight() == size)) return;
            square(send, size);
            if (progress != null) {
                int inset = Math.round(size * 0.27f);
                progress.setPadding(inset, inset, inset, inset);
                square(progress, size);
            }
        }));
    }

    private static void square(View view, int size) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return;
        lp.width = size;
        lp.height = size;
        if (lp instanceof FrameLayout.LayoutParams) ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER;
        view.setMinimumWidth(size);
        view.setMinimumHeight(size);
        view.setLayoutParams(lp);
        // The send button's frame may be shorter than the box; let it grow with the button.
        if (view.getParent() instanceof View) {
            View frame = (View) view.getParent();
            ViewGroup.LayoutParams flp = frame.getLayoutParams();
            if (flp != null && flp.height != ViewGroup.LayoutParams.WRAP_CONTENT && flp.height < size && flp.height >= 0) {
                flp.height = size;
                frame.setLayoutParams(flp);
            }
        }
    }

    /**
     * HyperOS' own background blur (the API its lock screen and shade use), clipped to the
     * circle; the translucent grey fill sits on top. Skipped where the ROM lacks it.
     */
    private static void frost(View view, Context context) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        view.setClipToOutline(true);
        try {
            int radius = Math.round(20 * context.getResources().getDisplayMetrics().density);
            View.class.getMethod("setMiBackgroundBlurMode", int.class).invoke(view, 1);
            View.class.getMethod("setMiBackgroundBlurRadius", int.class).invoke(view, radius);
            View.class.getMethod("setMiViewBlurMode", int.class).invoke(view, 1);
        } catch (Throwable ignored) {
            // No HyperOS blur on this ROM: the translucent circle alone.
        }
    }

    private static GradientDrawable circle(int color, int size) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        circle.setSize(size, size);
        return circle;
    }

    // --- Sent reply ----------------------------------------------------------------------------

    /** RemoteInputCoordinator, captured when the notification pipeline attaches it. */
    private volatile Object coordinator;

    /**
     * After a reply System UI spins until the app updates its notification. Since Android 15 the
     * system keeps the notification alive with the reply (FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY)
     * when the app cancels or ignores it, which the CN ROM never does: apps like Telegram that do
     * not repost leave the spinner turning forever and the reply missing. If nothing arrived
     * shortly after sending, add the reply to the notification the way System UI itself does.
     */
    private void hookSentReply(ClassLoader loader) throws Exception {
        Class<?> coordinatorClass = Class.forName(SB + "notification.collection.coordinator.RemoteInputCoordinator", false, loader);
        Method attach = null;
        for (Method m : coordinatorClass.getDeclaredMethods()) if (m.getName().equals("attach")) attach = m;
        if (attach == null) throw new NoSuchMethodException("RemoteInputCoordinator.attach");
        hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            coordinator = chain.getThisObject();
            return result;
        });
        Field rebuilderField = coordinatorClass.getDeclaredField("mRebuilder");
        Field updaterField = coordinatorClass.getDeclaredField("mNotifUpdater");
        rebuilderField.setAccessible(true);
        updaterField.setAccessible(true);

        Class<?> controllerImpl = Class.forName(SB + "policy.RemoteInputViewControllerImpl", false, loader);
        Field entryField = controllerImpl.getDeclaredField("entry");
        Field inputControllerField = controllerImpl.getDeclaredField("remoteInputController");
        entryField.setAccessible(true);
        inputControllerField.setAccessible(true);
        Class<?> entryClass = entryField.getType();
        Field keyField = field(entryClass, "key");
        Field textField = field(entryClass, "remoteInputText");
        Field mimeField = field(entryClass, "remoteInputMimeType");
        Field uriField = field(entryClass, "remoteInputUri");
        Field spinningField = field(inputControllerField.getType(), "mSpinning");

        Class<?> sendTask = Class.forName(SB + "policy.RemoteInputViewControllerImpl$onSendRemoteInputListener$1", false, loader);
        Field outer = sendTask.getDeclaredField("this$0");
        outer.setAccessible(true);
        Handler main = new Handler(Looper.getMainLooper());
        hook(sendTask.getDeclaredMethod("run")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            Object impl = outer.get(chain.getThisObject());
            Object entry = impl == null ? null : entryField.get(impl);
            Object inputController = impl == null ? null : inputControllerField.get(impl);
            if (entry == null || inputController == null) return result;
            String key = (String) keyField.get(entry);
            CharSequence text = (CharSequence) textField.get(entry);
            if (key == null || text == null || text.length() == 0) return result;
            main.postDelayed(() -> {
                try {
                    Map<?, ?> spinning = (Map<?, ?>) spinningField.get(inputController);
                    Object owner = coordinator;
                    // The app answered (WhatsApp reposts with the reply): nothing to do.
                    if (owner == null || spinning == null || !spinning.containsKey(key)) return;
                    Object rebuilder = rebuilderField.get(owner);
                    Object updater = updaterField.get(owner);
                    Method rebuild = method(rebuilder.getClass(), "rebuildWithRemoteInputInserted");
                    Method update = method(updater.getClass(), "onInternalNotificationUpdate");
                    Object sbn = rebuild.invoke(rebuilder, entry, text, false, mimeField.get(entry), uriField.get(entry));
                    update.invoke(updater, sbn, "XNotify: reply sent, app did not update");
                    info("Reply shown in the notification (app did not update it)");
                } catch (Throwable t) {
                    error("Showing the sent reply failed", t);
                }
            }, 1500);
            return result;
        });
        info("Sent replies: shown in the notification when the app does not update it");
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static Method method(Class<?> owner, String name) throws NoSuchMethodException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    // --- Message count pill --------------------------------------------------------------------

    /**
     * Android tints the expand pill with the app's accent once it shows a message count (purple
     * for Telegram); keep the plain grey/white pill it has without a count.
     */
    @SuppressLint({"BlockedPrivateApi", "PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi"}) // runs inside System UI, a platform app
    private void hookCountPill() throws Exception {
        Class<?> button = Class.forName("com.android.internal.widget.NotificationExpandButton");
        Field[] pairs = {
                button.getDeclaredField("mHighlightPillColor"), button.getDeclaredField("mDefaultPillColor"),
                button.getDeclaredField("mHighlightTextColor"), button.getDeclaredField("mDefaultTextColor")};
        for (Field f : pairs) f.setAccessible(true);
        Method update = button.getDeclaredMethod("updateColors");
        hook(update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object self = chain.getThisObject();
            for (int i = 0; i < pairs.length; i += 2) pairs[i].setInt(self, pairs[i + 1].getInt(self));
            return chain.proceed();
        });
        info("Message count pill: default colours");
    }

    // --- Bubble button -------------------------------------------------------------------------

    /** Drop the "open as bubble" button at the end of conversation notifications' action row. */
    private void hookBubbleButton(ClassLoader loader) throws Exception {
        Method show = Class.forName(SB + "notification.row.NotificationContentView", false, loader)
                .getDeclaredMethod("shouldShowBubbleButton");
        hook(show).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> false);

        // Xiaomi's own templates keep a second copy of the button in the wrapper's injector.
        Class<?> template = Class.forName(SB + "notification.row.wrapper.NotificationTemplateViewWrapper", false, loader);
        Field injector = template.getDeclaredField("mInjector");
        injector.setAccessible(true);
        Field button = injector.getType().getDeclaredField("bubbleButton");
        button.setAccessible(true);
        Method updated = template.getDeclaredMethod("onContentUpdated", Class.forName(ROW, false, loader));
        hook(updated).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            Object holder = injector.get(chain.getThisObject());
            View bubble = holder == null ? null : (View) button.get(holder);
            if (bubble != null) bubble.setVisibility(View.GONE);
            return result;
        });
        info("Bubble button hidden");
    }

    private int[] layouts(LayoutInflater inflater) {
        int[] ids = layouts;
        if (ids != null) return ids[0] == 0 ? null : ids;
        Context context = inflater.getContext();
        if (!SYSTEMUI.equals(context.getPackageName())) return null;
        Resources res = context.getResources();
        int aosp = res.getIdentifier("remote_input", "layout", SYSTEMUI);
        int miui = res.getIdentifier("miui_remote_input", "layout", SYSTEMUI);
        ids = aosp != 0 && miui != 0 ? new int[] {aosp, miui} : new int[] {0, 0};
        if (ids[0] == 0) info("miui_remote_input not found; reply box left as is");
        layouts = ids;
        return ids[0] == 0 ? null : ids;
    }

    // --- Action buttons ------------------------------------------------------------------------

    private void hookActionButtons(ClassLoader loader) throws Exception {
        Class<?> wrapper = Class.forName(WRAPPER, false, loader);
        Class<?> row = Class.forName(ROW, false, loader);
        Field viewField = wrapper.getDeclaredField("mView");
        viewField.setAccessible(true);
        Method isNative = Class.forName(WRAPPER_INJECTOR, false, loader).getDeclaredMethod("isNativeNotificationViewWrapper", wrapper);
        isNative.setAccessible(true);
        int actionsId = Resources.getSystem().getIdentifier("actions", "id", "android");
        int containerId = Resources.getSystem().getIdentifier("actions_container", "id", "android");
        if (actionsId == 0) throw new NoSuchFieldException("android:id/actions");

        Method updated = wrapper.getDeclaredMethod("onContentUpdated", row);
        hook(updated).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (!Boolean.TRUE.equals(isNative.invoke(null, self))) return result;
            View content = (View) viewField.get(self);
            View rowView = (View) chain.getArg(0);
            if (content == null || rowView == null) return result;
            // Subclasses finish their own setup after this call; style once they are done.
            content.post(() -> {
                try {
                    View list = content.findViewById(actionsId);
                    if (list instanceof ViewGroup) styleButtons((ViewGroup) list, rowView.getContext());
                    if (containerId != 0) keepClearOfHandle(content.findViewById(containerId));
                } catch (Throwable t) {
                    error("Styling action buttons failed", t);
                }
            });
            return result;
        });
        info("Action buttons: Xiaomi style for every notification");
    }

    /**
     * Pop-ups draw Xiaomi's floating-window handle just under the content. Xiaomi's own templates
     * leave room for it; under Android's the handle touches the buttons and cuts through the
     * reply box (both live in actions_container). The framework resets this margin on every
     * re-apply, so add the gap to whatever it set.
     */
    private static void keepClearOfHandle(View container) {
        if (container == null || !(container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) container.getLayoutParams();
        int gap = Math.round(10 * container.getResources().getDisplayMetrics().density);
        Object applied = container.getTag(TAG_GAP);
        if (applied instanceof Integer && lp.bottomMargin == (Integer) applied) return;
        lp.bottomMargin += gap;
        container.setTag(TAG_GAP, lp.bottomMargin);
        container.setLayoutParams(lp);
    }

    /** View tag key for the bottom margin XNotify last set (an id from the framework's range). */
    private static final int TAG_GAP = 0x7f0f_5ee1;

    /** Xiaomi's notification_action look: grey 20dp rounded chip, its text colour and size. */
    private static void styleButtons(ViewGroup list, Context systemUi) {
        Resources res = systemUi.getResources();
        int bg = res.getIdentifier("notification_action_bg_color", "color", SYSTEMUI);
        int text = res.getIdentifier("notification_action_text_color", "color", SYSTEMUI);
        int size = res.getIdentifier("notification_action_text_size", "dimen", SYSTEMUI);
        int pad = res.getIdentifier("notification_action_padding", "dimen", SYSTEMUI);
        if (bg == 0 || text == 0) return;
        float dp = res.getDisplayMetrics().density;
        // Same as the reply box: follow the notification's own text colour, not the theme.
        View title = findTitle(list);
        boolean light = title instanceof TextView
                ? lightInk(((TextView) title).getCurrentTextColor())
                : lightInk(systemUi.getColor(text));
        int chipColor = surface(systemUi, light, 0x26FFFFFF, 0x33000000, 0x17000000);
        int textColor = light ? 0xE6FFFFFF : 0xCC000000;
        java.util.List<Button> buttons = new java.util.ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i) instanceof Button && list.getChildAt(i).getVisibility() != View.GONE) {
                buttons.add((Button) list.getChildAt(i));
            }
        }
        float textWidth = 0;
        for (Button button : buttons) {
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(20 * dp);
            chip.setColor(chipColor);
            button.setBackground(chip);
            button.setBackgroundTintList(null);
            button.setTextColor(textColor);
            if (size != 0) button.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(size));
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setAllCaps(false);
            textWidth += button.getPaint().measureText(String.valueOf(button.getText()));
        }

        // Xiaomi's chips are wider than Android's. When the row no longer fits beside the text
        // column, Android drops its start indent and the row jumps to the card edge (WhatsApp's
        // three buttons); tighten the chips step by step until it fits again.
        int[][] steps = {{14, 10}, {12, 8}, {10, 8}, {9, 6}, {8, 6}, {7, 5}, {6, 4}};
        int available = list.getWidth() - list.getPaddingLeft() - list.getPaddingRight() - collapsibleIndent(list);
        int horizontal = Math.round(14 * dp);
        int gap = Math.round(10 * dp);
        for (int[] step : steps) {
            horizontal = Math.round(step[0] * dp);
            gap = Math.round(step[1] * dp);
            int used = Math.round(textWidth) + buttons.size() * 2 * horizontal + Math.max(0, buttons.size() - 1) * gap;
            if (available <= 0 || used < available) break;
        }

        int vertical = pad != 0 ? res.getDimensionPixelSize(pad) : Math.round(7 * dp);
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            button.setPadding(horizontal, vertical, horizontal, vertical);
            ViewGroup.LayoutParams lp = button.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lp;
                params.setMarginStart(i == 0 ? 0 : gap);
                params.setMarginEnd(0);
                button.setLayoutParams(params);
            }
        }
    }

    /** The start indent NotificationActionListLayout drops when the row does not fit. */
    private static int collapsibleIndent(View list) {
        try {
            Field f = field(list.getClass(), "mCollapsibleIndentDimen");
            int dimen = f.getInt(list);
            return dimen == 0 ? 0 : list.getResources().getDimensionPixelOffset(dimen);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void info(String message) {
        xposed.log(Log.INFO, TAG, message);
    }

    private void error(String message, Throwable t) {
        xposed.log(Log.ERROR, TAG, message, t);
    }
}
