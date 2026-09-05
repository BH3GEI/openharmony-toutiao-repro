/*
 * libwlveltrack.so -- the JNI half of android.view.VelocityTracker.
 *
 * This adapter ships android.view.VelocityTracker in framework.jar but no
 * implementation for its seven native methods, so the very first touch that
 * reaches a scrolling container dies:
 *
 *   java.lang.UnsatisfiedLinkError: No implementation found for
 *       long android.view.VelocityTracker.nativeInitialize(int)
 *     at android.view.VelocityTracker.obtain(VelocityTracker.java:230)
 *     at android.widget.HorizontalScrollView.initOrResetVelocityTracker(:540)
 *     at android.widget.HorizontalScrollView.onInterceptTouchEvent(:641)
 *     at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2654)
 *
 * ViewRootImpl's input stages swallow that error, which is why touch looked
 * like it was being delivered and then silently doing nothing.  Every
 * scrollable container is affected -- the channel row (HorizontalScrollView)
 * and the feed (RecyclerView) both call VelocityTracker.obtain().
 *
 * Scope: this is bookkeeping only.  It hands out a real per-tracker handle so
 * obtain/recycle/finalize pair up correctly, and reports zero velocity.  That
 * is enough for taps, drags and scrolls, which follow the touch position; only
 * *flings* need a real velocity, and they simply come out as "no fling".
 * Computing a true velocity means reading the MotionEvent back through JNI on
 * the UI thread inside touch dispatch, which is a much larger blast radius for
 * a feature nothing here needs yet.
 *
 * Binding: VelocityTracker is a boot-classpath class, so ART would normally
 * resolve its natives only against libraries registered under the *boot* class
 * loader -- and this adapter will not let us put one there.  Its
 * libnativeloader carries a hardcoded three-entry allowlist
 * (libopenjdk.so / libicu_jni.so / libjavacore.so); anything else is rejected
 * with "system library is absent from the adapter manifest".
 *
 * So we do not rely on symbol lookup at all.  This library is loaded into the
 * *app* namespace (which is permissive, and is how libwlalooper.so gets in) and
 * JNI_OnLoad calls RegisterNatives on android.view.VelocityTracker.  Explicit
 * registration overrides lookup and is indifferent to which loader we came
 * from -- see ActivityManagerRouting.loadVelocityTrackerShim().
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

/* MotionEvent.AXIS_X / AXIS_Y */
#define AXIS_X 0
#define AXIS_Y 1

#define WLVT_MAGIC 0x574C5654u  /* 'WLVT' */

struct wlvt {
    unsigned int magic;
    jint strategy;
};

#define EXPORT __attribute__((visibility("default")))

EXPORT jlong Java_android_view_VelocityTracker_nativeInitialize(
        JNIEnv *env, jclass clazz, jint strategy)
{
    struct wlvt *t = (struct wlvt *) malloc(sizeof(struct wlvt));
    if (t == NULL) {
        /* Java only checks for use, not for null, but a zero handle would make
         * every later call a no-op anyway, which is the safe degradation. */
        return 0;
    }
    t->magic = WLVT_MAGIC;
    t->strategy = strategy;
    return (jlong) (intptr_t) t;
}

EXPORT void Java_android_view_VelocityTracker_nativeDispose(
        JNIEnv *env, jclass clazz, jlong ptr)
{
    struct wlvt *t = (struct wlvt *) (intptr_t) ptr;
    if (t == NULL || t->magic != WLVT_MAGIC) {
        return;
    }
    t->magic = 0;
    free(t);
}

EXPORT void Java_android_view_VelocityTracker_nativeClear(
        JNIEnv *env, jclass clazz, jlong ptr)
{
    (void) ptr;
}

EXPORT void Java_android_view_VelocityTracker_nativeAddMovement(
        JNIEnv *env, jclass clazz, jlong ptr, jobject event)
{
    (void) ptr;
    (void) event;
}

EXPORT void Java_android_view_VelocityTracker_nativeComputeCurrentVelocity(
        JNIEnv *env, jclass clazz, jlong ptr, jint units, jfloat maxVelocity)
{
    (void) ptr;
    (void) units;
    (void) maxVelocity;
}

EXPORT jfloat Java_android_view_VelocityTracker_nativeGetVelocity(
        JNIEnv *env, jclass clazz, jlong ptr, jint axis, jint pointerId)
{
    (void) ptr;
    (void) axis;
    (void) pointerId;
    return 0.0f;
}

EXPORT jboolean Java_android_view_VelocityTracker_nativeIsAxisSupported(
        JNIEnv *env, jclass clazz, jint axis)
{
    return (axis == AXIS_X || axis == AXIS_Y) ? JNI_TRUE : JNI_FALSE;
}

/* ---------------------------------------------------------------------- */

static const JNINativeMethod kMethods[] = {
    { "nativeInitialize",             "(I)J",
      (void *) Java_android_view_VelocityTracker_nativeInitialize },
    { "nativeDispose",                "(J)V",
      (void *) Java_android_view_VelocityTracker_nativeDispose },
    { "nativeClear",                  "(J)V",
      (void *) Java_android_view_VelocityTracker_nativeClear },
    { "nativeAddMovement",            "(JLandroid/view/MotionEvent;)V",
      (void *) Java_android_view_VelocityTracker_nativeAddMovement },
    { "nativeComputeCurrentVelocity", "(JIF)V",
      (void *) Java_android_view_VelocityTracker_nativeComputeCurrentVelocity },
    { "nativeGetVelocity",            "(JII)F",
      (void *) Java_android_view_VelocityTracker_nativeGetVelocity },
    { "nativeIsAxisSupported",        "(I)Z",
      (void *) Java_android_view_VelocityTracker_nativeIsAxisSupported },
};

EXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    jclass cls;
    jint rc;

    (void) reserved;

    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_VERSION_1_6;   /* nothing we can do; leave the natives unbound */
    }

    cls = (*env)->FindClass(env, "android/view/VelocityTracker");
    if (cls == NULL) {
        (*env)->ExceptionClear(env);
        return JNI_VERSION_1_6;
    }

    rc = (*env)->RegisterNatives(env, cls, kMethods,
                                 (jint) (sizeof(kMethods) / sizeof(kMethods[0])));
    if (rc != JNI_OK) {
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, cls);

    /* The Java side re-checks by calling VelocityTracker.obtain(), so a silent
     * failure here still shows up as a self-test failure in the log. */
    return JNI_VERSION_1_6;
}
