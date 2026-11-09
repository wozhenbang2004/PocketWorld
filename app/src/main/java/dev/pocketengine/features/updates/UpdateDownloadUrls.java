package dev.pocketengine.features.updates;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/** Public, unauthenticated GitHub Release downloads only. Never log CDN queries. */
final class UpdateDownloadUrls {
    static final int MAX_REDIRECTS = 5;
    private static final String ROOT = "/wozhenbang2004/PocketWorld/releases/";
    private UpdateDownloadUrls() {}

    static String initial(String value) throws Exception {
        URI uri = safe(value);
        if (!"github.com".equalsIgnoreCase(uri.getHost()) || uri.getRawQuery() != null)
            throw new SecurityException("更新入口不是指定的 GitHub 仓库");
        requireReleasePath(uri);
        return uri.toASCIIString();
    }

    static String redirect(String current, String location, int followed, Set<String> visited) throws Exception {
        if (followed >= MAX_REDIRECTS || location == null || location.length() > 8192)
            throw new SecurityException("更新下载重定向次数或地址无效");
        URI uri = safe(new URI(current).resolve(location).toString());
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("github.com")) {
            if (uri.getRawQuery() != null) throw new SecurityException("GitHub 更新入口不得携带参数");
            requireReleasePath(uri);
        } else if (!host.equals("release-assets.githubusercontent.com") && !host.equals("objects.githubusercontent.com")) {
            throw new SecurityException("更新下载重定向至未允许的主机");
        }
        String next = uri.toASCIIString();
        if (!visited.add(next)) throw new SecurityException("更新下载出现重定向循环");
        return next;
    }

    private static URI safe(String value) throws Exception {
        if (value == null || value.length() > 8192 || value.indexOf('\\') >= 0)
            throw new SecurityException("更新地址无效");
        URI uri = new URI(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
            uri.getRawUserInfo() != null || uri.getRawFragment() != null ||
            (uri.getPort() != -1 && uri.getPort() != 443))
            throw new SecurityException("更新地址必须是无凭据 HTTPS 地址");
        return uri;
    }

    private static void requireReleasePath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.contains("%") || path.contains("//") || !uri.normalize().getRawPath().equals(path) ||
            !(path.startsWith(ROOT + "download/") || path.equals(ROOT + "latest/download/release.json")))
            throw new SecurityException("更新地址不属于指定仓库的发布产物");
    }
}
