package dev.pocketengine.phone.setup;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, explicit owner setup only. Never reads text outside a system Settings pairing dialog. */
public final class SystemWirelessPairing {
    private static final Pattern ADDRESS = Pattern.compile("((?:[0-9]{1,3}\\.){3}[0-9]{1,3})\\s*:\\s*([0-9]{1,5})");
    private static final Pattern CODE = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])");
    private SystemWirelessPairing() { }

    public record Credential(String host, int port, String code) { }

    /** The process must also verify that the visible package is a system Settings application. */
    public static boolean trustedSettings(Context context, String packageName) {
        if (packageName == null || packageName.length() > 160) return false;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        if (!normalized.contains("settings") || normalized.startsWith(context.getPackageName().toLowerCase(Locale.ROOT))) return false;
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(packageName, 0);
            return (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException absent) { return false; }
    }

    /** Requires an unambiguous code and endpoint from the same currently visible system window. */
    public static Credential parseVisibleText(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 96) return null;
        StringBuilder all = new StringBuilder();
        for (String value : values) {
            if (value == null || value.length() > 240 || all.length() + value.length() > 8192) return null;
            all.append(value.toLowerCase(Locale.ROOT)).append(' ');
        }
        String window = all.toString();
        boolean pairing = window.contains("配对码") || window.contains("pairing code") || window.contains("pair with code");
        boolean wireless = window.contains("wi-fi") || window.contains("wifi") || window.contains("无线") || window.contains("wlan");
        if (!pairing || !wireless) return null;
        Set<String> codes = new HashSet<>(), addresses = new HashSet<>();
        for (String value : values) {
            String item = value == null ? "" : value.trim();
            Matcher endpoint = ADDRESS.matcher(item);
            while (endpoint.find()) addresses.add(endpoint.group(1) + ":" + endpoint.group(2));
            if (item.matches("[0-9]{6}") || item.toLowerCase(Locale.ROOT).contains("pairing code")
                    || item.contains("配对码")) {
                Matcher code = CODE.matcher(item);
                while (code.find()) {
                    // A six-digit number inside an address or a port is not a pairing code.
                    if (!ADDRESS.matcher(item).find()) codes.add(code.group(1));
                }
            }
        }
        if (codes.size() != 1 || addresses.size() != 1) return null;
        String address = addresses.iterator().next();
        int colon = address.lastIndexOf(':');
        int port;
        try { port = Integer.parseInt(address.substring(colon + 1)); }
        catch (NumberFormatException invalid) { return null; }
        if (port < 1 || port > 65535) return null;
        return new Credential(address.substring(0, colon), port, codes.iterator().next());
    }
}
