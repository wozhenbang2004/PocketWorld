package dev.pocketengine.phone.setup;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Native owner settings metadata only. This does not mint model action references or launch apps. */
public final class OwnerPhoneApps {
    private static final int LIMIT = 200;
    private static final long WINDOW_MS = 120_000;
    public record Selection(String label, String packageName, int userId, long profileSerial, int uid,
                            long versionCode, String installIdentity, boolean currentProfile, long listedAt) { }
    public record Listing(List<Selection> apps, boolean complete) { }
    private final Context context;

    public OwnerPhoneApps(Context context) { this.context = context.getApplicationContext(); }

    public Listing list() throws Exception {
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        UserManager users = context.getSystemService(UserManager.class);
        if (launcher == null || users == null) throw new SecurityException("owner_app_profiles_unavailable");
        LinkedHashMap<String, Selection> copies = new LinkedHashMap<>();
        boolean complete = true;
        long listedAt = SystemClock.uptimeMillis();
        for (UserHandle user : launcher.getProfiles()) {
            try {
                long serial = users.getSerialNumberForUser(user);
                if (serial < 0 || !unlocked(users, user)) { complete = false; continue; }
                for (LauncherActivityInfo activity : launcher.getActivityList(null, user)) {
                    try {
                        Selection selected = selection(activity, user, serial, listedAt);
                        String key = selected.packageName() + ":" + selected.userId();
                        if (copies.containsKey(key)) continue;
                        if (copies.size() >= LIMIT) { complete = false; break; }
                        copies.put(key, selected);
                    } catch (SecurityException unavailable) { complete = false; }
                }
            } catch (SecurityException unavailable) { complete = false; }
        }
        ArrayList<Selection> out = new ArrayList<>(copies.values());
        out.sort(java.util.Comparator.comparing(Selection::label).thenComparing(Selection::packageName)
                .thenComparingLong(Selection::profileSerial));
        return new Listing(List.copyOf(out), complete);
    }

    /** Re-read the same accessible profile and installation immediately before an owner policy write. */
    public void requireCurrent(Selection selected) throws Exception {
        long now = SystemClock.uptimeMillis();
        if (selected == null || now < selected.listedAt() || now - selected.listedAt() >= WINDOW_MS)
            throw new SecurityException("owner_app_selection_expired");
        LauncherApps launcher = context.getSystemService(LauncherApps.class);
        UserManager users = context.getSystemService(UserManager.class);
        if (launcher == null || users == null) throw new SecurityException("owner_app_profiles_unavailable");
        UserHandle expectedUser = UserHandle.getUserHandleForUid(selected.uid());
        for (UserHandle user : launcher.getProfiles()) {
            if (!user.equals(expectedUser) || users.getSerialNumberForUser(user) != selected.profileSerial()
                    || !unlocked(users, user) || !launcher.isPackageEnabled(selected.packageName(), user)) continue;
            for (LauncherActivityInfo activity : launcher.getActivityList(selected.packageName(), user)) {
                Selection actual = selection(activity, user, selected.profileSerial(), selected.listedAt());
                if (sameInstallation(selected, actual)) return;
            }
        }
        throw new SecurityException("owner_app_installation_changed");
    }

    static boolean sameInstallation(Selection expected, Selection actual) {
        return expected.packageName().equals(actual.packageName()) && expected.userId() == actual.userId()
                && expected.profileSerial() == actual.profileSerial() && expected.uid() == actual.uid()
                && expected.versionCode() == actual.versionCode() && expected.installIdentity().equals(actual.installIdentity());
    }

    private Selection selection(LauncherActivityInfo activity, UserHandle user, long serial, long listedAt) throws Exception {
        var app = activity.getApplicationInfo();
        if (app == null || app.uid < 0 || !app.enabled || !UserHandle.getUserHandleForUid(app.uid).equals(user))
            throw new SecurityException("owner_app_uid_changed");
        String pkg = activity.getComponentName().getPackageName();
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(app.sourceDir, PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || archive.signingInfo == null || !pkg.equals(archive.packageName()))
            throw new SecurityException("owner_app_installation_unavailable");
        Signature[] raw = archive.signingInfo.getApkContentsSigners();
        if (raw == null || raw.length == 0) throw new SecurityException("owner_app_signer_unavailable");
        ArrayList<String> signers = new ArrayList<>();
        for (Signature signature : raw) signers.add(hash(signature.toByteArray()));
        signers.sort(String::compareTo);
        String identity = hash((pkg + ":" + archive.getLongVersionCode() + ":" + String.join(",", signers)
                + ":" + app.sourceDir + ":" + java.util.Arrays.toString(app.splitSourceDirs)
                + ":" + activity.getFirstInstallTime()).getBytes(StandardCharsets.UTF_8));
        String label = String.valueOf(activity.getLabel()).replaceAll("[\\p{Cntrl}\\u202a-\\u202e\\u2066-\\u2069]", " ");
        if (label.length() > 160) label = label.substring(0, 160);
        return new Selection(label, pkg, app.uid / 100000, serial, app.uid, archive.getLongVersionCode(), identity,
                user.equals(android.os.Process.myUserHandle()), listedAt);
    }

    private static boolean unlocked(UserManager users, UserHandle user) {
        return user.equals(android.os.Process.myUserHandle()) ? users.isUserUnlocked() : users.isUserUnlocked(user);
    }
    private static String hash(byte[] bytes) throws Exception {
        return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
