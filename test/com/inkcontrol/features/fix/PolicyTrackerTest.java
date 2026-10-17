package com.inkcontrol.features.fix;

import java.util.Random;

public final class PolicyTrackerTest {
    static int checks;
    static final DisplayPermissionPolicy.Identity ID = new DisplayPermissionPolicy.Identity() {
        public boolean platformSigned(int uid) { return uid == 10050; }
        public boolean ownsPackage(int uid, String pkg) {
            return (uid == 10001 && "video.a".equals(pkg)) || (uid == 10002 && "video.b".equals(pkg))
                    || (uid == 10003 && "com.inkcontrol".equals(pkg));
        }
        public boolean installedUid(int uid) { return uid >= 10001 && uid <= 10003; }
        public boolean lyricAuthorized(int uid) { return uid == 10003; }
    };
    static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    static boolean allow(String method, int uid, String top, Object... args) {
        return DisplayPermissionPolicy.allowed(method, uid, top, args, ID);
    }
    public static void main(String[] args) {
        for (String method : new String[] {"getEpdMode", "getDisplayType", "getEinkEffect", "getTopPackage", "getAllAppMode"})
            check(allow(method, 10001, "video.b"), "stock read " + method);
        for (String method : new String[] {"addAppMode", "removeAppMode", "adjustEinkEffect", "setDisplayType",
                "updateShutdownWallPaper", "setEinkKeyguardMode", "setGestureUnlock", "setEinkThemeMode",
                "forceClear", "notifyShutdown", "startWakingUp", "onScreenChanged",
                "notifyResumedPackage", "updateExteralPaper", "finishBoot", "futureMutation"}) {
            check(!allow(method, 10001, "video.a", "video.a", 3), "ordinary mutation denied " + method);
            check(allow(method, 0, "", new Object()), "root " + method);
            check(allow(method, 1000, "", new Object()), "system " + method);
            check(allow(method, 10050, "", new Object()), "platform " + method);
            check(!allow(method, 10003, "com.inkcontrol", new Object()), "lyric mutation denied " + method);
        }
        check(allow("begigResumeActivity", 10001, "video.b", "video.a", "Activity", false), "own ActivityThread resume");
        check(!allow("begigResumeActivity", 10001, "video.a", "video.b", "Activity", false), "spoofed activity package denied");
        check(!allow("begigResumeActivity", 110001, "video.a", "video.a", "Activity", false), "cross-user activity denied");
        check(!allow("begigResumeActivity", 10001, "video.a", "video.a"), "malformed activity denied");
        check(allow("setEpdDisplayMode", 10001, "video.a", 3), "own foreground mode");
        check(!allow("setEpdDisplayMode", 10001, "video.b", 3), "other foreground denied");
        check(!allow("setEpdDisplayMode", 110001, "video.a", 3), "different user UID denied");
        check(!allow("setEpdDisplayMode", 10001, "video.a", 0x406), "flags denied");
        check(!allow("setEpdDisplayMode", 10001, "video.a", 0), "auto global mode denied");
        check(allow("updateEpdMode", 10001, "video.a"), "own restore mode");
        check(!allow("updateEpdMode", 10001, "video.b"), "background restore denied");
        check(allow("notifyVideoOn", 10001, "video.a", true, 10001), "self start");
        check(!allow("notifyVideoOn", 10001, "video.b", true, 10001), "background app start denied");
        check(allow("notifyVideoOn", 10001, "video.b", false, 10001), "self background stop");
        check(!allow("notifyVideoOn", 10001, "video.a", true, 10002), "spoof target UID denied");
        check(allow("notifyVideoOn", 1013, "video.b", true, 10001), "media notification grant");
        check(!allow("notifyCameraOn", 1013, "video.b", true, 10001), "media cannot delegate camera");
        check(allow("notifyCameraOn", 1047, "video.b", true, 10001), "camera notification grant");
        check(!allow("notifyVideoOn", 1047, "video.b", true, 10001), "camera cannot delegate video");
        check(!allow("notifyVideoOn", 1013, "", true, -1), "negative target denied");
        check(!allow("notifyVideoOn", 1013, "", true, 12345), "unknown target denied");
        check(!allow("setEpdDisplayMode", 1013, "video.a", 3), "media cannot set mode");
        check(allow("addBitmapToPresentationDisplayWithString", 10003, "", null, 0, 3, 0, false, null), "lyric lockscreen");
        check(!allow("addBitmapToPresentationDisplayWithString", 10003, "", null, 1, 3, 0, false, null), "lyric priority denied");
        check(!allow("addBitmapToPresentationDisplayWithString", 10003, "", null, 0, 0x403, 0, false, null), "lyric flags denied");
        check(!allow("addBitmapToPresentationDisplayWithString", 10001, "com.inkcontrol", null, 0, 3, 0, false, null), "forged lyric name denied");
        check(allow("removeViewOnPresentationDisplay", 10003, "", 0), "lyric own priority removal");

        VideoSessionTracker t = new VideoSessionTracker();
        Object a = new Object(), b = new Object(), camera = new Object();
        t.top("video.a", "A"); t.activity(a, 10001, "video.a", "A", true);
        t.notify(10001, 0, true); t.notify(10001, 0, true);
        check(t.count(10001, 0) == 2, "same UID parallel count");
        t.notify(10001, 0, false);
        check(t.visible(0, ID), "one stop preserves other player");
        t.top("video.b", "B"); t.activity(b, 10002, "video.b", "B", true); t.notify(10002, 0, true);
        t.top("video.b", "Camera"); t.activity(camera, 10002, "video.b", "Camera", true); t.notify(10002, 1, true);
        t.notify(10001, 0, false);
        check(t.visible(0, ID) && t.visible(1, ID), "other UID video and camera survive");
        t.notify(99999, 0, false);
        check(t.count(10002, 0) == 1, "unknown off cannot clear known UID");
        t.activity(b, 10002, "video.b", "B", false);
        t.top("video.b", "Other");
        check(!t.visible(0, ID), "same package page switch drops hidden video reason");
        check(t.visible(1, ID), "visible camera survives switch");
        t.activity(b, 10002, "video.b", "B", true);
        check(t.visible(0, ID), "PiP or resumed video reason restored");
        t.destroyed(b);
        check(t.count(10002, 0) == 0 && t.count(10002, 1) == 1, "destruction clears only instance-owned sessions");
        t.destroyed(camera);
        check(t.count(10002, 1) == 0, "camera destruction clears owned session");
        // Separate instances within one UID must not share a permanent class-name marker.
        t = new VideoSessionTracker();
        t.top("video.a", "A"); t.activity(a, 10001, "video.a", "A", true); t.notify(10001, 0, true);
        t.top("video.a", "B"); t.activity(b, 10001, "video.a", "B", true); t.notify(10001, 0, true);
        t.destroyed(a);
        check(t.count(10001, 0) == 1 && t.visible(0, ID), "destroy one instance preserves other");
        t.notify(10001, 0, false);
        check(!t.visible(0, ID), "playback end clears final reason");
        t = new VideoSessionTracker(); t.top("video.a", "A"); t.notify(10001, 0, true);
        t.activity(a, 10001, "video.a", "A", true); t.destroyed(a);
        check(t.count(10001, 0) == 0, "notification-before-resume binds for cleanup");

        t = new VideoSessionTracker();
        int[][] expected = new int[3][2]; Random r = new Random(9);
        for (int i = 0; i < 10000; i++) {
            int u = r.nextInt(3), kind = r.nextInt(2); boolean on = r.nextBoolean();
            expected[u][kind] = on ? expected[u][kind] + 1 : Math.max(0, expected[u][kind] - 1);
            t.notify(10001 + u, kind, on);
            check(t.count(10001 + u, kind) == expected[u][kind], "random UID/type isolation " + i);
        }
        System.out.println("PASS PolicyTrackerTest checks=" + checks);
    }
}
