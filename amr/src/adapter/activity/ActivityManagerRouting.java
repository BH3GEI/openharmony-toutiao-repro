package adapter.activity;

import android.app.ContentProviderHolder;
import android.app.IApplicationThread;
import android.content.pm.ProviderInfo;
import android.os.RemoteException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Provider-aware / component-aware IActivityManager for the Westlake adapter.
 *
 * Two AMS behaviours the plain stub was missing, both of which the Toutiao plugin
 * frameworks (Mira, Tinker) depend on during Application startup:
 *
 * 1. getContentProvider() for an authority the app itself declares.
 *    AOSP builds the Application object before installContentProviders() runs, but apps
 *    call ContentResolver on their own authorities from Application.<init>/
 *    attachBaseContext.  The stub answered those with a synthetic OH DataShare bridge;
 *    ActivityThread registered that bridge in mProviderMap, so when the real provider
 *    was installed moments later AOSP saw the authority taken ("already published") and
 *    dropped it -- the dead bridge owned the authority forever.  Hand back the real
 *    ProviderInfo with provider == null instead, and ActivityThread.installProvider()
 *    instantiates it in-process, exactly as AMS does on a device.
 *
 * 2. ComponentInfo.processName on query results.
 *    The adapter's PackageManager leaves it null; on a device it always defaults to the
 *    application's process.  Mira keys a TreeMap by it
 *    (PluginActivityManagerProvider.c -> TreeMap.get(activityInfo.processName)), so a
 *    null there throws NPE and kills the thread the main thread is waiting on.
 *    Wrap ActivityThread.sPackageManager and fill the field in when it comes back null.
 */
public class ActivityManagerRouting extends ActivityManagerAdapter {

    private static volatile boolean sPmWrapped;
    private static volatile boolean sBackFillReported;

    public ActivityManagerRouting() {
        super();
        System.err.println("[WL-AMR] provider-aware IActivityManager active");
        ensureStubServices();
    }

    @Override
    public void attachApplication(IApplicationThread app, long startSeq)
            throws RemoteException {
        ensureStubServices();
        ensurePackageManagerWrapped();
        startViewTreeDumper();
        super.attachApplication(app, startSeq);
    }

    /* ------------------------------------------------------------------
     * View-tree dump
     *
     * The MainActivity window composites as plain white and neither of the
     * usual tools can say why: `uitest dumpLayout` only walks SceneBoard's
     * ArkUI tree (the Android views are not in it, and the app's WindowScene
     * does not even appear), and `hidumper -s WindowManagerService` lists only
     * SceneBoard's own windows because the adapter attaches app windows as
     * scene sessions.  So dump the Android hierarchy from inside the process.
     *
     * Everything is reflective: this class is compiled against a handful of
     * hand-written stubs, not a real android.jar.
     * ------------------------------------------------------------------ */

    private static volatile boolean sDumperStarted;

    private static void startViewTreeDumper() {
        if (sDumperStarted) return;
        sDumperStarted = true;
        /*
         * Run off a plain daemon thread, NOT the main Handler.  MainActivity's
         * startup wedges the main looper ~12s in -- which is exactly why the
         * window never draws -- so a main-thread dumper stops reporting at the
         * moment it becomes interesting.  From here we can still print the main
         * thread's stack and see what it is stuck on.
         */
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long[] whenMs = { 30000L, 60000L, 85000L, 110000L };
                long slept = 0;
                for (int i = 0; i < whenMs.length; i++) {
                    try { Thread.sleep(whenMs[i] - slept); } catch (InterruptedException e) { return; }
                    slept = whenMs[i];
                    dumpMainThreadStack(i + 1);
                    dumpAllWindows(i + 1);
                }
            }
        }, "wl-main-probe");
        t.setDaemon(true);
        t.start();
        System.err.println("[WL-VIEWTREE] off-main probe armed (4 passes)");
    }

    private static void dumpMainThreadStack(int pass) {
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                Thread th = e.getKey();
                if (!"main".equals(th.getName())) continue;
                System.err.println("[WL-MAINSTACK] ==== pass " + pass + " main state="
                        + th.getState() + " ====");
                StackTraceElement[] st = e.getValue();
                int limit = st.length > 45 ? 45 : st.length;
                for (int i = 0; i < limit; i++) {
                    System.err.println("[WL-MAINSTACK]   at " + st[i]);
                }
                if (st.length > limit) {
                    System.err.println("[WL-MAINSTACK]   ... " + (st.length - limit) + " more");
                }
            }
        } catch (Throwable t) {
            System.err.println("[WL-MAINSTACK] pass " + pass + " failed: " + t);
        }
    }

    private static void dumpAllWindows(int pass) {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            List<?> views = (List<?>) readField(wmg, inst, "mViews");
            List<?> params = (List<?>) readField(wmg, inst, "mParams");
            System.err.println("[WL-VIEWTREE] ==== pass " + pass + ": "
                    + (views == null ? -1 : views.size()) + " window(s) ====");
            if (views == null) return;
            for (int i = 0; i < views.size(); i++) {
                Object lp = (params != null && i < params.size()) ? params.get(i) : null;
                System.err.println("[WL-VIEWTREE] --- window[" + i + "] params=" + describeLp(lp));
                dumpView(views.get(i), 0, 0);
            }
            driveConsentDialog(views);
        } catch (Throwable t) {
            System.err.println("[WL-VIEWTREE] pass " + pass + " failed: " + t);
        }
    }

    private static String describeLp(Object lp) {
        if (lp == null) return "null";
        try {
            Class<?> c = lp.getClass();
            return "w=" + readIntField(c, lp, "width") + " h=" + readIntField(c, lp, "height")
                    + " type=" + readIntField(c, lp, "type")
                    + " flags=0x" + Integer.toHexString(readIntField(c, lp, "flags"))
                    + " title=" + lp;
        } catch (Throwable t) {
            return "<" + t + ">";
        }
    }

    /** Depth-first, but capped: a Toutiao feed is thousands of views. */
    private static void dumpView(Object v, int depth, int index) {
        if (v == null || depth > 12) return;
        try {
            Class<?> viewCls = Class.forName("android.view.View");
            int vis = (Integer) viewCls.getMethod("getVisibility").invoke(v);
            int w = (Integer) viewCls.getMethod("getWidth").invoke(v);
            int h = (Integer) viewCls.getMethod("getHeight").invoke(v);
            int left = (Integer) viewCls.getMethod("getLeft").invoke(v);
            int top = (Integer) viewCls.getMethod("getTop").invoke(v);
            boolean shown = (Boolean) viewCls.getMethod("isShown").invoke(v);
            String text = "";
            try {
                Class<?> tv = Class.forName("android.widget.TextView");
                if (tv.isInstance(v)) {
                    Object cs = tv.getMethod("getText").invoke(v);
                    if (cs != null) {
                        text = " text=\"" + cs.toString() + "\"";
                        if (text.length() > 60) text = text.substring(0, 60) + "...\"";
                    }
                }
            } catch (Throwable ignore) { }

            StringBuilder sb = new StringBuilder("[WL-VIEWTREE] ");
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append('#').append(index).append(' ')
              .append(v.getClass().getName())
              .append(" vis=").append(vis == 0 ? "VISIBLE" : (vis == 4 ? "INVISIBLE" : "GONE"))
              .append(" shown=").append(shown)
              .append(" @").append(left).append(',').append(top)
              .append(' ').append(w).append('x').append(h)
              .append(text);
            System.err.println(sb.toString());

            Class<?> vg = Class.forName("android.view.ViewGroup");
            if (vg.isInstance(v)) {
                int n = (Integer) vg.getMethod("getChildCount").invoke(v);
                Method getChildAt = vg.getMethod("getChildAt", int.class);
                int limit = n > 40 ? 40 : n;
                for (int i = 0; i < limit; i++) {
                    dumpView(getChildAt.invoke(v, i), depth + 1, i);
                }
                if (n > limit) {
                    StringBuilder pad = new StringBuilder("[WL-VIEWTREE] ");
                    for (int i = 0; i <= depth; i++) pad.append("  ");
                    System.err.println(pad + "... " + (n - limit) + " more child(ren)");
                }
            }
        } catch (Throwable t) {
            System.err.println("[WL-VIEWTREE] dumpView failed at depth " + depth + ": " + t);
        }
    }

    /*
     * Consent-dialog assist.
     *
     * Toutiao gates startup behind a privacy-consent PopupWindow.  The adapter
     * creates that window's scene session with a null ability token
     * (`createSession ... tokenAddr=0x0` -> the degenerate `session=1`), which has
     * neither a surface nor an input channel: the dialog is invisible on screen
     * AND cannot be tapped, so the app waits on consent forever and the whole
     * process composites white.
     *
     * NOTE: this dispatches the "同意" button in-process.  It is an automation
     * step performed because the adapter cannot present the dialog to a human --
     * it is NOT a human consent event, and it should be removed once
     * PopupWindows get real sessions.
     */
    private static volatile boolean sConsentDriven;

    private static void driveConsentDialog(List<?> views) {
        if (sConsentDriven) return;
        for (int i = 0; i < views.size(); i++) {
            Object hit = findViewWithText(views.get(i), "同意", 0);
            if (hit != null) {
                try {
                    Class<?> viewCls = Class.forName("android.view.View");
                    Object target = hit;
                    // The label itself is often not the click target; walk up to
                    // the first ancestor that is clickable.
                    for (int up = 0; up < 4; up++) {
                        boolean clickable = (Boolean) viewCls.getMethod("isClickable").invoke(target);
                        if (clickable) break;
                        Object parent = viewCls.getMethod("getParent").invoke(target);
                        if (parent == null || !viewCls.isInstance(parent)) break;
                        target = parent;
                    }
                    System.err.println("[WL-CONSENT] adapter cannot render or route input to the"
                            + " consent PopupWindow (session=1, no surface/input);"
                            + " dispatching performClick() on " + target.getClass().getName()
                            + " -- automation, not a human consent event");
                    Object ok = viewCls.getMethod("performClick").invoke(target);
                    System.err.println("[WL-CONSENT] performClick returned " + ok);
                    sConsentDriven = true;
                } catch (Throwable t) {
                    System.err.println("[WL-CONSENT] failed: " + t);
                }
                return;
            }
        }
    }

    private static Object findViewWithText(Object v, String want, int depth) {
        if (v == null || depth > 14) return null;
        try {
            Class<?> tv = Class.forName("android.widget.TextView");
            if (tv.isInstance(v)) {
                Object cs = tv.getMethod("getText").invoke(v);
                if (cs != null && want.contentEquals(cs.toString())) return v;
            }
            Class<?> vg = Class.forName("android.view.ViewGroup");
            if (vg.isInstance(v)) {
                int n = (Integer) vg.getMethod("getChildCount").invoke(v);
                Method getChildAt = vg.getMethod("getChildAt", int.class);
                for (int i = 0; i < n; i++) {
                    Object r = findViewWithText(getChildAt.invoke(v, i), want, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object readField(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static int readIntField(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getField(name);
        f.setAccessible(true);
        return f.getInt(o);
    }

    @Override
    public ContentProviderHolder getContentProvider(IApplicationThread caller,
            String callingPackage, String name, int userId, boolean stable)
            throws RemoteException {
        ensurePackageManagerWrapped();
        ProviderInfo own = findDeclaredProvider(name);
        if (own != null) {
            ContentProviderHolder holder = new ContentProviderHolder(own);
            holder.noReleaseNeeded = true;
            // Must be flagged local.  ActivityThread.acquireProvider() treats
            // (provider == null && !mLocal) as "another process is publishing it" and
            // blocks for CONTENT_PROVIDER_READY_TIMEOUT_MILLIS -- 20s per acquisition,
            // while the caller (Mira) holds PluginPackageManager.class.  With mLocal set
            // it goes straight to installProvider() and instantiates in-process.
            holder.mLocal = true;
            System.err.println("[WL-AMR] own authority " + name + " -> local install");
            return holder;
        }
        return super.getContentProvider(caller, callingPackage, name, userId, stable);
    }

    // ---- 1. app's own providers -------------------------------------------------

    /** Look the authority up in the ProviderInfo list this process was bound with. */
    private static ProviderInfo findDeclaredProvider(String authority) {
        if (authority == null || authority.length() == 0) {
            return null;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method current = atClass.getMethod("currentActivityThread");
            Object activityThread = current.invoke((Object) null);
            if (activityThread == null) {
                return null;
            }
            Field boundField = atClass.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            Object bound = boundField.get(activityThread);
            if (bound == null) {
                return null;
            }
            Field providersField = bound.getClass().getDeclaredField("providers");
            providersField.setAccessible(true);
            Object raw = providersField.get(bound);
            if (!(raw instanceof List)) {
                return null;
            }
            for (Object item : (List<?>) raw) {
                if (!(item instanceof ProviderInfo)) {
                    continue;
                }
                ProviderInfo info = (ProviderInfo) item;
                if (info.authority == null) {
                    continue;
                }
                String[] auths = info.authority.split(";");
                for (int i = 0; i < auths.length; i++) {
                    if (authority.equals(auths[i].trim())) {
                        return info;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to the default stub path
        }
        return null;
    }

    // ---- 2b. AppBindData.processName --------------------------------------------
    //
    // Toutiao gates a large amount of startup (ALog, most lego init tasks) on
    // ToolUtils.isMainProcessByActivityThread(), which reflects
    // ActivityThread.currentProcessName() -> mBoundApplication.processName and compares it
    // with the package name.  If that field is empty the app decides it is a secondary
    // process and skips the init, and SplashActivity.onResume() then NPEs on the
    // uninitialised ALog instance.  Make sure the field carries the real process name.

    private static volatile boolean sProcessNameChecked;

    private static void ensureProcessNameVisible() {
        if (sProcessNameChecked) {
            return;
        }
        sProcessNameChecked = true;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke((Object) null);
            if (at == null) {
                return;
            }
            Field boundField = atClass.getDeclaredField("mBoundApplication");
            boundField.setAccessible(true);
            Object bound = boundField.get(at);
            if (bound == null) {
                sProcessNameChecked = false;   // bind not started yet; re-check later
                return;
            }
            Field pnField = bound.getClass().getDeclaredField("processName");
            pnField.setAccessible(true);
            Object cur = pnField.get(bound);
            String pkg = null;
            Object appInfo = readFieldValue(bound, "appInfo");
            if (appInfo != null) {
                Object v = readFieldValue(appInfo, "processName");
                if (v instanceof String && ((String) v).length() > 0) {
                    pkg = (String) v;
                } else {
                    v = readFieldValue(appInfo, "packageName");
                    if (v instanceof String && ((String) v).length() > 0) {
                        pkg = (String) v;
                    }
                }
            }
            if (!(cur instanceof String) || ((String) cur).length() == 0) {
                if (pkg != null) {
                    pnField.set(bound, pkg);
                    System.err.println("[WL-AMR] AppBindData.processName was empty -> " + pkg);
                }
            }
            System.err.println("[WL-AMR] currentProcessName=" + pnField.get(bound)
                    + " appInfo.processName=" + pkg);
        } catch (Throwable t) {
            System.err.println("[WL-AMR] processName check failed: " + t);
        }
    }

    // ---- 2c. LayoutParams flag masking on the window session --------------------
    //
    // WindowSessionAdapter fail-closes: any LayoutParams flag outside its
    // SUPPORTED_LAYOUT_FLAGS whitelist makes addToDisplay() return ADD_INVALID_TYPE, and
    // ViewRootImpl.setView() then throws InvalidDisplayException -- so the Activity never
    // gets a window and no frame is ever drawn.  Toutiao's splash asks for
    // FLAG_TRANSLUCENT_STATUS / FLAG_TRANSLUCENT_NAVIGATION / FLAG_DIM_BEHIND, which are
    // purely decorative.  Clear exactly the bits the adapter does not implement so the
    // window is created; nothing else about the request changes.

    private static volatile boolean sWindowSessionWrapped;
    private static int sSupportedFlags;

    private static int supportedLayoutFlags() {
        if (sSupportedFlags != 0) {
            return sSupportedFlags;
        }
        try {
            Class<?> wsa = Class.forName("adapter.window.WindowSessionAdapter");
            Field f = wsa.getDeclaredField("SUPPORTED_LAYOUT_FLAGS");
            f.setAccessible(true);
            sSupportedFlags = f.getInt(null);
        } catch (Throwable ignored) {
        }
        if (sSupportedFlags == 0) {
            // Mirror of the adapter whitelist, in case the field moves.
            sSupportedFlags = 0x01000000 | 0x00000100 | 0x00010000 | 0x00800000
                    | 0x80000000 | 0x00000400 | 0x00000080 | 0x00080000
                    | 0x00200000 | 0x00400000;
        }
        return sSupportedFlags;
    }

    private static void ensureWindowSessionWrapped() {
        if (sWindowSessionWrapped) {
            return;
        }
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Field f = wmg.getDeclaredField("sWindowSession");
            f.setAccessible(true);
            Object cur = f.get(null);
            if (cur == null) {
                wmg.getMethod("getWindowSession").invoke((Object) null);
                cur = f.get(null);
            }
            if (cur == null) {
                return;   // window stack not ready yet; try again on a later call
            }
            if (Proxy.isProxyClass(cur.getClass())
                    && Proxy.getInvocationHandler(cur) instanceof FlagMaskHandler) {
                sWindowSessionWrapped = true;
                return;
            }
            Class<?> iws = Class.forName("android.view.IWindowSession");
            Object wrapper = Proxy.newProxyInstance(iws.getClassLoader(),
                    new Class<?>[] { iws }, new FlagMaskHandler(cur));
            f.set(null, wrapper);
            sWindowSessionWrapped = true;
            System.err.println("[WL-AMR] IWindowSession wrapped; supported flags=0x"
                    + Integer.toHexString(supportedLayoutFlags()));
        } catch (Throwable t) {
            System.err.println("[WL-AMR] window session wrap failed: " + t);
        }
    }

    private static final class FlagMaskHandler implements InvocationHandler {
        private final Object mTarget;

        FlagMaskHandler(Object target) {
            mTarget = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String mName = method.getName();
            Object windowArg = (args != null && args.length > 0) ? args[0] : null;
            Object attrsArg = (args != null && args.length > 1) ? args[1] : null;

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    maskFlags(args[i], mName);
                    fixupNullToken(args[i], mName);
                    dropHwAccelForSubWindow(args[i], mName);
                }
            }

            neutralizeSubWindow(windowArg, attrsArg, mName);

            Object result;
            try {
                result = method.invoke(mTarget, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }

            if ("relayout".equals(mName) && result instanceof Integer) {
                int ty = subWindowType(windowArg, attrsArg);
                if (ty >= FIRST_SUB_WINDOW) {
                    int original = (Integer) result;
                    result = Integer.valueOf(0);
                    System.err.println("[WL-AMR] relayout: sub-window type=" + ty
                            + " -> neutralized relayoutResult (" + original + " -> 0)");
                    if (args.length > 10 && args[10] != null) {
                        try {
                            Method releaseM = findMethod(args[10].getClass(), "release");
                            if (releaseM != null) {
                                releaseM.setAccessible(true);
                                releaseM.invoke(args[10]);
                                System.err.println("[WL-AMR] relayout: sub-window type=" + ty
                                        + " -> released outSurfaceControl!");
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            invalidateSubWindowSurface(windowArg, attrsArg, mName);

            return result;
        }

        /*
         * Make a sub-window's Surface report invalid, so ViewRootImpl never draws it.
         *
         * destroyHardwareRenderer() + setWindowStopped(true) stop the *hardware*
         * path, which did kill the old
         *     ASSERT FAILED [skia] mEglSurface == EGL_NO_SURFACE -> abort() -> exit 134
         * but ViewRootImpl then falls back to drawSoftware(), and because the popup's
         * degenerate session=1 Surface has no buffer producer, lockCanvas() never
         * actually locks and the finally-block blows up on the main thread:
         *
         *   java.lang.IllegalStateException: Surface was not locked
         *     at android.view.Surface.unlockSwCanvasAndPost(Surface.java:507)
         *     at android.view.ViewRootImpl.drawSoftware(ViewRootImpl.java:5046)
         *     at android.view.ViewRootImpl.performTraversals(...)
         *
         * mStopped does not reliably suppress an already-scheduled traversal, but
         * ViewRootImpl.draw() opens with an unconditional
         *     if (!surface.isValid()) { return false; }
         * in every AOSP version.  Releasing mSurface hits that gate, so the popup is
         * skipped before any lock/unlock happens -- while the Activity's own window
         * (type < 1000) keeps drawing normally.
         *
         * Done *after* the real call so the adapter cannot hand a fresh Surface back
         * underneath us.
         */
        private static void invalidateSubWindowSurface(Object window, Object layoutParams,
                String where) {
            int ty = subWindowType(window, layoutParams);
            if (ty < FIRST_SUB_WINDOW || window == null) return;
            try {
                Object vri = viewRootOf(window);
                if (vri == null) return;

                Field sf = findField(vri.getClass(), "mSurface");
                if (sf != null) {
                    sf.setAccessible(true);
                    Object surface = sf.get(vri);
                    if (surface != null) {
                        Method isValid = findMethod(surface.getClass(), "isValid");
                        boolean valid = true;
                        if (isValid != null) {
                            isValid.setAccessible(true);
                            valid = (Boolean) isValid.invoke(surface);
                        }
                        if (valid) {
                            Method rel = findMethod(surface.getClass(), "release");
                            if (rel != null) {
                                rel.setAccessible(true);
                                rel.invoke(surface);
                                System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                                        + " -> released ViewRootImpl.mSurface; draw() will now"
                                        + " short-circuit on !isValid() instead of throwing"
                                        + " \"Surface was not locked\" in drawSoftware()");
                            }
                        }
                    }
                }
                /* belt and braces: without this performDraw() can still be entered
                 * once via the (!mStopped || mReportNextDraw) path. */
                Field rnd = findField(vri.getClass(), "mReportNextDraw");
                if (rnd != null) {
                    rnd.setAccessible(true);
                    if (rnd.getBoolean(vri)) rnd.setBoolean(vri, false);
                }
            } catch (Throwable t) {
                System.err.println("[WL-AMR] " + where + ": invalidateSubWindowSurface failed: " + t);
            }
        }

        /*
         * Back-fill LayoutParams.token for windows that arrive without one.
         *
         * Toutiao shows its privacy-consent gate as a PopupWindow (type=2,
         * TYPE_APPLICATION).  AOSP fills such a window's token from
         * WindowManagerImpl.mDefaultToken, which this adapter never sets, so the
         * request reaches the adapter's window bridge with a null token:
         *
         *   [WESTLAKE-WMC] createSession ... ability=MainActivity tokenAddr=0x0
         *   [WESTLAKE-QID] session=1 producer ... nodeName=PopupWindow:e14a9e5
         *
         * A null token yields the degenerate session id 1, which has neither a
         * surface nor an input channel.  The popup is therefore invisible *and*
         * untouchable -- the app blocks on consent forever -- and its surface-less
         * render node also wedges the process-wide HWUI thread:
         *
         *   ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE
         *       msg=drawRenderNode called on a context with no surface!
         *
         * which is why every window in the process then composites as plain white.
         * Reuse the token of a window that already got a real session.
         */
        private static volatile Object sLastGoodToken;

        private static void fixupNullToken(Object arg, String where) {
            if (arg == null
                    || !"android.view.WindowManager$LayoutParams".equals(
                            arg.getClass().getName())) {
                return;
            }
            try {
                Field tokenField = findField(arg.getClass(), "token");
                if (tokenField == null) {
                    return;
                }
                tokenField.setAccessible(true);
                Field typeF = findField(arg.getClass(), "type");
                int ty = -1;
                if (typeF != null) { typeF.setAccessible(true); ty = typeF.getInt(arg); }
                Object token = tokenField.get(arg);
                System.err.println("[WL-WSDIAG] " + where + " type=" + ty
                        + " token=" + (token == null ? "null" : token.getClass().getName()));
                /*
                 * The adapter resolves a window to an OH scene session through a map
                 * keyed by *activity* tokens.  A PopupWindow's LayoutParams.token is
                 * not an activity token -- PopupWindow takes it from
                 * View.getApplicationWindowToken(), i.e. the owning ViewRootImpl's
                 * window token, a plain android.os.Binder.  The lookup misses, the
                 * adapter falls back to `createSession ... tokenAddr=0x0` and the
                 * window lands on the degenerate session=1 with no surface and no
                 * input channel.  Swap in a token the map actually knows.
                 */
                /* Sub-windows (type >= FIRST_SUB_WINDOW) are *supposed* to carry the
                 * parent's ViewRootImpl$W, not an activity token -- substituting there
                 * is wrong and does not help anyway (the popup still lands on
                 * session=1).  Only fix real application windows. */
                Object activityToken = (ty >= 1000) ? null : currentActivityToken(token);
                if (activityToken != null && activityToken != token) {
                    tokenField.set(arg, activityToken);
                    System.err.println("[WL-AMR] " + where + ": window type=" + ty
                            + " carried a non-activity token (" 
                            + (token == null ? "null" : token.getClass().getName())
                            + "); substituted the top activity token so the adapter's"
                            + " session map resolves it instead of falling back to session=1");
                }
                if (token != null) {
                    sLastGoodToken = token;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        /** Topmost ActivityThread activity token, or null if `have` is already one. */
        private static Object currentActivityToken(Object have) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread == null) return null;
                Field f = at.getDeclaredField("mActivities");
                f.setAccessible(true);
                Object map = f.get(thread);
                if (!(map instanceof Map)) return null;
                Map<?, ?> m = (Map<?, ?>) map;
                if (have != null && m.containsKey(have)) return null;   // already fine
                Object last = null;
                for (Object k : m.keySet()) last = k;
                return last;
            } catch (Throwable t) {
                return null;
            }
        }

        /*
         * Drop FLAG_HARDWARE_ACCELERATED on sub-windows.
         *
         * A PopupWindow (TYPE_APPLICATION_PANEL, 1000) never gets a real OH scene
         * session on this adapter -- it falls back to the degenerate `session=1`,
         * which has no surface.  ViewRootImpl still drives HWUI for it, so the
         * render thread hits
         *   ASSERT FAILED [skia] cond=mEglSurface == EGL_NO_SURFACE
         *       msg=drawRenderNode called on a context with no surface!
         *   abort() hwui hijack
         * and the whole process exits 134 -- which is what kills Toutiao ~70s into
         * MainActivity, right when the fallback first frame would appear.
         *
         * Without the flag ViewRootImpl draws that window through the software
         * pipeline, which needs no EGL surface.  A popup drawn in software is
         * perfectly fine; the Activity's own window keeps hardware acceleration.
         */
        private static int getLayoutParamsType(Object arg) {
            if (arg == null || !"android.view.WindowManager$LayoutParams".equals(arg.getClass().getName())) {
                return -1;
            }
            try {
                Field typeF = findField(arg.getClass(), "type");
                if (typeF != null) {
                    typeF.setAccessible(true);
                    return typeF.getInt(arg);
                }
            } catch (Throwable ignored) {}
            return -1;
        }

        /*
         * Sub-window registry.
         *
         * ViewRootImpl.relayoutWindow() only sends LayoutParams when they actually
         * changed -- every subsequent relayout passes attrs == null to save IPC.  So a
         * type check on the *current* call misses the follow-up relayouts: the first
         * one (with attrs, type=1000) was neutralised, the second (attrs == null) fell
         * through, the adapter reported SURFACE_CHANGED, ViewRootImpl scheduled a
         * redraw and drawSoftware() threw "Surface was not locked" on the main thread.
         *
         * Remember which IWindow objects are sub-windows the first time we see their
         * type, so every later call is recognised regardless of attrs.  Weak keys so a
         * dismissed popup does not pin its ViewRootImpl.
         */
        private static final Set<Object> sSubWindows =
                Collections.synchronizedSet(
                        Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

        /** Sub-window type for this call, or -1.  Registers on first identification. */
        private static int subWindowType(Object window, Object layoutParams) {
            int ty = getLayoutParamsType(layoutParams);
            if (ty >= FIRST_SUB_WINDOW) {
                if (window != null) sSubWindows.add(window);
                return ty;
            }
            if (window == null) return -1;
            if (sSubWindows.contains(window)) return FIRST_SUB_WINDOW;
            // attrs == null and not yet registered: ask the ViewRootImpl itself.
            try {
                Object vri = viewRootOf(window);
                if (vri != null) {
                    Field waf = findField(vri.getClass(), "mWindowAttributes");
                    if (waf != null) {
                        waf.setAccessible(true);
                        int t = getLayoutParamsType(waf.get(vri));
                        if (t >= FIRST_SUB_WINDOW) {
                            sSubWindows.add(window);
                            return t;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return -1;
        }

        private static Object viewRootOf(Object window) {
            try {
                Field vf = findField(window.getClass(), "mViewAncestor");
                if (vf == null) return null;
                vf.setAccessible(true);
                Object wr = vf.get(window);
                if (wr instanceof java.lang.ref.WeakReference) {
                    return ((java.lang.ref.WeakReference<?>) wr).get();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static void neutralizeSubWindow(Object window, Object layoutParams, String where) {
            int ty = subWindowType(window, layoutParams);
            if (ty < FIRST_SUB_WINDOW) return;
            try {
                if (window != null) {
                    Field vf = findField(window.getClass(), "mViewAncestor");
                    if (vf != null) {
                        vf.setAccessible(true);
                        Object wr = vf.get(window);
                        if (wr instanceof java.lang.ref.WeakReference) {
                            Object vri = ((java.lang.ref.WeakReference<?>) wr).get();
                            if (vri != null) {
                                Method dm = findMethod(vri.getClass(), "destroyHardwareRenderer");
                                if (dm != null) {
                                    dm.setAccessible(true);
                                    dm.invoke(vri);
                                    System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                                            + " -> destroyed HardwareRenderer on ViewRootImpl!");
                                }
                                Method stopM = findMethod(vri.getClass(), "setWindowStopped", boolean.class);
                                if (stopM != null) {
                                    stopM.setAccessible(true);
                                    stopM.invoke(vri, true);
                                    System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                                            + " -> setWindowStopped(true) on ViewRootImpl!");
                                }
                                Field rndField = findField(vri.getClass(), "mReportNextDraw");
                                if (rndField != null) {
                                    rndField.setAccessible(true);
                                    rndField.setBoolean(vri, false);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                System.err.println("[WL-AMR] neutralizeSubWindow failed: " + t);
            }
        }

        private static final int FLAG_HARDWARE_ACCELERATED = 0x01000000;
        private static final int FIRST_SUB_WINDOW = 1000;

        private static void dropHwAccelForSubWindow(Object arg, String where) {
            if (arg == null
                    || !"android.view.WindowManager$LayoutParams".equals(
                            arg.getClass().getName())) {
                return;
            }
            try {
                Field typeF = findField(arg.getClass(), "type");
                Field flagsF = findField(arg.getClass(), "flags");
                if (typeF == null || flagsF == null) return;
                typeF.setAccessible(true); flagsF.setAccessible(true);
                int ty = typeF.getInt(arg);
                if (ty < FIRST_SUB_WINDOW) return;
                int flags = flagsF.getInt(arg);
                if ((flags & FLAG_HARDWARE_ACCELERATED) == 0) return;
                flagsF.setInt(arg, flags & ~FLAG_HARDWARE_ACCELERATED);
                System.err.println("[WL-AMR] " + where + ": sub-window type=" + ty
                        + " -> cleared FLAG_HARDWARE_ACCELERATED (no OH session => no EGL"
                        + " surface => drawRenderNode would abort the process)");
            } catch (Throwable ignored) {
            }
        }

        private static void maskFlags(Object arg, String where) {
            if (arg == null
                    || !"android.view.WindowManager$LayoutParams".equals(
                            arg.getClass().getName())) {
                return;
            }
            try {
                Field flagsField = findField(arg.getClass(), "flags");
                if (flagsField == null) {
                    return;
                }
                flagsField.setAccessible(true);
                int flags = flagsField.getInt(arg);
                int unsupported = flags & ~supportedLayoutFlags();
                if (unsupported != 0) {
                    flagsField.setInt(arg, flags & ~unsupported);
                    System.err.println("[WL-AMR] " + where + ": cleared unsupported window flags 0x"
                            + Integer.toHexString(unsupported) + " (was 0x"
                            + Integer.toHexString(flags) + ")");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- 3. missing system-service stubs ----------------------------------------
    //
    // ApplicationPackageManager caches context.getSystemService(PermissionManager.class)
    // in its constructor.  That fetcher does
    // IPermissionManager.Stub.asInterface(ServiceManager.getServiceOrThrow("permissionmgr")),
    // which the adapter has no route for, so mPermissionManager stays null and
    // Activity.shouldShowRequestPermissionRationale() NPEs on the main thread during
    // SplashActivity.onResume().  Park a defaults-only binder in ServiceManager.sCache --
    // the same channel the adapter already uses for 'user' and 'mount'.

    private static volatile boolean sStubsInstalled;

    private static void ensureStubServices() {
        if (sStubsInstalled) {
            return;
        }
        synchronized (ActivityManagerRouting.class) {
            if (sStubsInstalled) {
                return;
            }
            sStubsInstalled = true;
            registerBinderStub("permissionmgr", "android.permission.IPermissionManager");
            registerBinderStub("legacy_permission", "android.permission.ILegacyPermissionManager");
        }
    }

    private static void registerBinderStub(String serviceName, String interfaceName) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Field cacheField = smClass.getDeclaredField("sCache");
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(null);
            if (!(cacheObj instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cache = (Map<String, Object>) cacheObj;
            if (cache.get(serviceName) != null) {
                return;
            }
            Class<?> ifaceClass = Class.forName(interfaceName);
            ClassLoader cl = ifaceClass.getClassLoader();
            Class<?> binderClass = Class.forName("android.os.IBinder");

            final Object[] holder = new Object[2];
            Object iface = Proxy.newProxyInstance(cl, new Class<?>[] { ifaceClass },
                    new DefaultsHandler(holder, 1));
            Object binder = Proxy.newProxyInstance(cl, new Class<?>[] { binderClass },
                    new DefaultsHandler(holder, 0));
            holder[0] = binder;
            holder[1] = iface;

            cache.put(serviceName, binder);
            System.err.println("[WL-AMR] stub service '" + serviceName + "' registered ("
                    + interfaceName + ")");
        } catch (Throwable t) {
            System.err.println("[WL-AMR] stub service '" + serviceName + "' failed: " + t);
        }
    }

    /** Returns benign defaults; wires asBinder()/queryLocalInterface() to each other. */
    private static final class DefaultsHandler implements InvocationHandler {
        private final Object[] mPair;   // [0] = binder, [1] = interface
        private final int mKind;        // 0 = binder, 1 = interface

        DefaultsHandler(Object[] pair, int kind) {
            mPair = pair;
            mKind = kind;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String n = method.getName();
            if ("queryLocalInterface".equals(n)) {
                return mPair[1];
            }
            if ("asBinder".equals(n)) {
                return mKind == 0 ? proxy : mPair[0];
            }
            if ("isBinderAlive".equals(n) || "pingBinder".equals(n)) {
                return Boolean.TRUE;
            }
            if ("toString".equals(n)) {
                return "WL-STUB";
            }
            if ("hashCode".equals(n)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(n)) {
                return Boolean.valueOf(args != null && args.length == 1 && args[0] == proxy);
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == float.class) {
            return Float.valueOf(0f);
        }
        if (type == double.class) {
            return Double.valueOf(0d);
        }
        if (List.class.isAssignableFrom(type)) {
            return new java.util.ArrayList<Object>();
        }
        return null;
    }

    // ---- 2. processName back-fill on PackageManager results ----------------------

    private static void ensurePackageManagerWrapped() {
        if (sPmWrapped) {
            return;
        }
        synchronized (ActivityManagerRouting.class) {
            if (sPmWrapped) {
                return;
            }
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Field pmField = atClass.getDeclaredField("sPackageManager");
                pmField.setAccessible(true);
                Object real = pmField.get(null);
                if (real == null) {
                    return;
                }
                if (Proxy.isProxyClass(real.getClass())
                        && Proxy.getInvocationHandler(real) instanceof PmHandler) {
                    sPmWrapped = true;
                    return;
                }
                Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
                Object wrapper = Proxy.newProxyInstance(ipm.getClassLoader(),
                        new Class<?>[] { ipm }, new PmHandler(real));
                pmField.set(null, wrapper);
                sPmWrapped = true;
                System.err.println("[WL-AMR] IPackageManager wrapped for processName back-fill");
            } catch (Throwable t) {
                System.err.println("[WL-AMR] PM wrap failed: " + t);
            }
        }
    }

    private static final class PmHandler implements InvocationHandler {
        private final Object mTarget;

        PmHandler(Object target) {
            mTarget = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            ensureProcessNameVisible();
            ensureWindowSessionWrapped();
            Object result;
            try {
                result = method.invoke(mTarget, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
            String name = method.getName();
            if (name.startsWith("query") || name.startsWith("resolve")
                    || name.startsWith("get")) {
                try {
                    int fixed = backFill(result, 0);
                    if (fixed > 0 && !sBackFillReported) {
                        sBackFillReported = true;
                        System.err.println("[WL-AMR] pm." + name
                                + " processName back-filled x" + fixed
                                + " (further back-fills silent)");
                    }
                } catch (Throwable t) {
                    System.err.println("[WL-AMR] backFill(" + name + ") threw " + t);
                }
            }
            return result;
        }
    }

    /** Walk any PackageManager result and back-fill ComponentInfo.processName. */
    private static int backFill(Object result, int depth) {
        if (result == null || depth > 3) {
            return 0;
        }
        int fixed = 0;

        if (result instanceof Object[]) {
            Object[] arr = (Object[]) result;
            for (int i = 0; i < arr.length; i++) {
                fixed += backFill(arr[i], depth + 1);
            }
            return fixed;
        }
        if (result instanceof List) {
            for (Object item : (List<?>) result) {
                fixed += backFill(item, depth + 1);
            }
            return fixed;
        }

        String cls = result.getClass().getName();

        // IPackageManager hands lists back as ParceledListSlice.
        if (cls.indexOf("ParceledListSlice") >= 0) {
            try {
                Object inner = result.getClass().getMethod("getList").invoke(result);
                return backFill(inner, depth + 1);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        if ("android.content.pm.ResolveInfo".equals(cls)) {
            fixed += fixComponent(readField(result, "activityInfo"));
            fixed += fixComponent(readField(result, "serviceInfo"));
            fixed += fixComponent(readField(result, "providerInfo"));
            return fixed;
        }
        if ("android.content.pm.PackageInfo".equals(cls)) {
            fixed += backFill(readField(result, "activities"), depth + 1);
            fixed += backFill(readField(result, "services"), depth + 1);
            fixed += backFill(readField(result, "receivers"), depth + 1);
            fixed += backFill(readField(result, "providers"), depth + 1);
            return fixed;
        }
        return fixComponent(result);
    }

    /** ComponentInfo.processName defaults to the application's process on a device. */
    private static int fixComponent(Object component) {
        if (component == null) {
            return 0;
        }
        Field processName = findField(component.getClass(), "processName");
        if (processName == null) {
            return 0;
        }
        try {
            processName.setAccessible(true);
            Object cur = processName.get(component);
            if (cur instanceof String && ((String) cur).length() > 0) {
                return 0;
            }
            String replacement = null;
            Object appInfo = readField(component, "applicationInfo");
            if (appInfo != null) {
                Object v = readFieldValue(appInfo, "processName");
                if (v instanceof String && ((String) v).length() > 0) {
                    replacement = (String) v;
                }
                if (replacement == null) {
                    v = readFieldValue(appInfo, "packageName");
                    if (v instanceof String && ((String) v).length() > 0) {
                        replacement = (String) v;
                    }
                }
            }
            if (replacement == null) {
                Object v = readFieldValue(component, "packageName");
                if (v instanceof String && ((String) v).length() > 0) {
                    replacement = (String) v;
                }
            }
            if (replacement == null) {
                // Adapter-built ComponentInfo can have neither applicationInfo nor
                // packageName set.  Every component of this app runs in this process, so
                // the bound process name is the correct default.
                replacement = currentProcessName();
            }
            if (replacement != null) {
                processName.set(component, replacement);
                return 1;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String sProcessName;

    private static String currentProcessName() {
        if (sProcessName != null) {
            return sProcessName;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object v = atClass.getMethod("currentProcessName").invoke((Object) null);
            if (v instanceof String && ((String) v).length() > 0) {
                sProcessName = (String) v;
            }
        } catch (Throwable ignored) {
        }
        if (sProcessName == null) {
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Object v = atClass.getMethod("currentPackageName").invoke((Object) null);
                if (v instanceof String && ((String) v).length() > 0) {
                    sProcessName = (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return sProcessName;
    }

    private static Object readField(Object target, String name) {
        return readFieldValue(target, name);
    }

    private static Object readFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = findField(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }
}
