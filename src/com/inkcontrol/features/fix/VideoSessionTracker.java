package com.inkcontrol.features.fix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Serialized by the EPD mLock. Only original VIDEO_ON/CAMERA_ON events change counts. */
final class VideoSessionTracker {
    static final int VIDEO = 0;
    static final int CAMERA = 1;
    private static final class Activity {
        final int uid;
        final String pkg, name;
        boolean visible;
        Activity(int uid, String pkg, String name, boolean visible) {
            this.uid = uid; this.pkg = pkg; this.name = name; this.visible = visible;
        }
    }
    private static final class Session {
        final int uid, kind;
        Object owner;
        Session(int uid, int kind, Object owner) { this.uid = uid; this.kind = kind; this.owner = owner; }
    }
    private final Map<Object, Activity> activities = new HashMap<>();
    private final List<Session> sessions = new ArrayList<>();
    private String topPackage = "", topActivity = "";

    void top(String pkg, String activity) {
        topPackage = pkg == null ? "" : pkg;
        topActivity = activity == null ? "" : activity;
    }

    void activity(Object token, int uid, String pkg, String name, boolean visible) {
        activities.put(token, new Activity(uid, pkg, name, visible));
        // Start notifications may precede the resume callback. Bind only unowned sessions.
        if (visible && pkg.equals(topPackage) && name.equals(topActivity)) {
            for (Session s : sessions) if (s.uid == uid && s.owner == null) s.owner = token;
        }
    }

    void destroyed(Object token) {
        activities.remove(token);
        for (Iterator<Session> i = sessions.iterator(); i.hasNext();) {
            if (token.equals(i.next().owner)) i.remove();
        }
    }

    void notify(int uid, int kind, boolean on) {
        if (on) {
            Object owner = null;
            for (Map.Entry<Object, Activity> e : activities.entrySet()) {
                Activity a = e.getValue();
                if (a.uid == uid && a.visible && a.pkg.equals(topPackage) && a.name.equals(topActivity)) {
                    owner = e.getKey(); break;
                }
            }
            sessions.add(new Session(uid, kind, owner));
        } else {
            // The legacy API supplies no player token. Match UID/type, never remove other UIDs/types.
            for (Iterator<Session> i = sessions.iterator(); i.hasNext();) {
                Session s = i.next();
                if (s.uid == uid && s.kind == kind) { i.remove(); break; }
            }
        }
    }

    boolean visible(int kind, DisplayPermissionPolicy.Identity id) {
        for (Session s : sessions) {
            if (s.kind != kind) continue;
            Activity a = activities.get(s.owner);
            if (a != null) {
                if (a.visible) return true;
            } else if (id.ownsPackage(s.uid, topPackage)) return true;
        }
        return false;
    }

    int count(int uid, int kind) {
        int count = 0;
        for (Session s : sessions) if (s.uid == uid && s.kind == kind) count++;
        return count;
    }

    String summary() { return "sessions=" + sessions.size() + " activities=" + activities.size(); }
}
