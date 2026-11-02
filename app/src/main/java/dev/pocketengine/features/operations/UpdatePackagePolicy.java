package dev.pocketengine.features.operations;

import java.net.URI;
import java.util.Set;
import org.json.JSONObject;
import dev.pocketengine.R;

/** Mandatory metadata and installed-package identity checks, independent of download completion. */
public final class UpdatePackagePolicy {
    private UpdatePackagePolicy() {}
    public static URI source(String raw){
        URI uri=URI.create(raw);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getHost().isBlank()||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||uri.getRawQuery()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw new IllegalArgumentException(LiteralUiText.text(R.string.w8_update_policy_000, "更新源必须是不含凭据、查询或片段的HTTPS地址"));return uri;
    }
    public static JSONObject manifest(JSONObject input,String expectedPackage,long installedVersion,URI source)throws Exception{
        if(!"pocketworld.private-update.v1".equals(input.optString("format"))||!expectedPackage.equals(input.optString("packageName")))throw new SecurityException(LiteralUiText.text(R.string.w8_update_policy_001, "更新包名或清单格式不符"));
        Object version=input.opt("versionCode"),size=input.opt("bytes");
        if(!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()<1||!(size instanceof Integer||size instanceof Long)||((Number)size).longValue()<1||((Number)size).longValue()>300L*1024*1024||!input.optString("sha256").matches("[a-f0-9]{64}"))throw new SecurityException(LiteralUiText.text(R.string.w8_update_policy_002, "更新版本、大小或SHA-256无效"));
        URI download=source(input.getString("downloadUrl"));if(!source.getScheme().equals(download.getScheme())||!source.getHost().equalsIgnoreCase(download.getHost()))throw new SecurityException(LiteralUiText.text(R.string.w8_update_policy_003, "下载地址不属于主人配置的同一私有更新源"));
        String name=input.optString("versionName");if(name.length()>80||name.codePoints().anyMatch(Character::isISOControl))throw new SecurityException(LiteralUiText.text(R.string.w8_update_policy_004, "版本名称无效"));
        return new JSONObject().put("format","pocketworld.private-update.v1").put("packageName",expectedPackage).put("versionCode",version).put("versionName",name).put("bytes",size).put("sha256",input.getString("sha256")).put("downloadUrl",download.toString());
    }
    public static void packageIdentity(JSONObject manifest,String installedPackage,long installedVersion,String archivePackage,long archiveVersion,String archiveVersionName,Set<String> installedSigners,Set<String> archiveSigners)throws Exception{
        if(!installedPackage.equals(archivePackage)||!installedPackage.equals(manifest.getString("packageName"))||archiveVersion<=installedVersion||archiveVersion!=manifest.getLong("versionCode"))throw new SecurityException(LiteralUiText.text(R.string.w8_update_policy_005, "安装包名或版本不符"));
        if(!manifest.optString("versionName").isEmpty()&&!manifest.getString("versionName").equals(archiveVersionName))throw new SecurityException(LiteralUiText.text(R.string.w8_update_policy_006, "安装包版本名称与清单不符"));
        if(installedSigners.isEmpty()||archiveSigners.isEmpty()||!installedSigners.equals(archiveSigners))throw new SecurityException(LiteralUiText.text(R.string.w8_update_policy_007, "安装包签名与已安装应用不同"));
    }
}
