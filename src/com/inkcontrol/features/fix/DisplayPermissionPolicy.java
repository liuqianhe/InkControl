package com.inkcontrol.features.fix;

/** Pure policy; Binder identity and PackageManager facts are supplied at the stub entry. */
final class DisplayPermissionPolicy {
    enum Access { READ, ACTIVITY, TEMP_MODE, NOTIFY_VIDEO, NOTIFY_CAMERA, LOCKSCREEN, PRIVILEGED }

    interface Identity {
        boolean platformSigned(int uid);
        boolean ownsPackage(int uid, String pkg);
        boolean installedUid(int uid);
        boolean lyricAuthorized(int uid);
    }

    static Access access(String method) {
        if ("getDisplayType".equals(method) || "getEpdMode".equals(method)
                || "getEinkEffect".equals(method) || "getTopPackage".equals(method)
                || "getAllAppMode".equals(method)) return Access.READ;
        if ("setEpdDisplayMode".equals(method) || "updateEpdMode".equals(method)) return Access.TEMP_MODE;
        if ("begigResumeActivity".equals(method)) return Access.ACTIVITY;
        if ("notifyVideoOn".equals(method)) return Access.NOTIFY_VIDEO;
        if ("notifyCameraOn".equals(method)) return Access.NOTIFY_CAMERA;
        if (method.startsWith("addBitmapToPresentationDisplay")
                || "addViewToPresentationDisplay".equals(method)
                || "removeViewOnPresentationDisplay".equals(method)) return Access.LOCKSCREEN;
        return Access.PRIVILEGED;
    }

    static boolean allowed(String method, int uid, String topPackage, Object[] args, Identity id) {
        Access access = access(method);
        if (access == Access.READ) return true; // Retain stock read API compatibility.
        if (uid == 0 || uid == 1000 || id.platformSigned(uid)) return true;
        // 此固件的 ActivityThread 直接以应用 UID 上报生命周期；禁止冒用其他包名。
        if (access == Access.ACTIVITY)
            return args.length == 3 && args[0] instanceof String && id.ownsPackage(uid, (String) args[0]);
        if (access == Access.NOTIFY_VIDEO || access == Access.NOTIFY_CAMERA) {
            int target = (Integer) args[1];
            if (target < 0 || !id.installedUid(target)) return false;
            // Native system daemons have no APK signature. These grants are notification-only.
            if (uid == target) return !(Boolean) args[0] || id.ownsPackage(uid, topPackage);
            return access == Access.NOTIFY_VIDEO ? uid == 1013 : uid == 1047;
        }
        if (access == Access.TEMP_MODE) {
            if (!id.ownsPackage(uid, topPackage)) return false;
            if ("updateEpdMode".equals(method)) return true;
            int mode = (Integer) args[0];
            return mode >= 1 && mode <= 9; // Cannot inject flags or change persisted app settings.
        }
        if (access == Access.LOCKSCREEN && id.lyricAuthorized(uid)) {
            int priority = (Integer) args["removeViewOnPresentationDisplay".equals(method) ? 0 : 1];
            if (priority != 0) return false;
            if ("removeViewOnPresentationDisplay".equals(method)) return true;
            int mode = (Integer) args[2];
            return mode >= 1 && mode <= 9; // No privileged display flags via the lyric grant.
        }
        return false;
    }
}
