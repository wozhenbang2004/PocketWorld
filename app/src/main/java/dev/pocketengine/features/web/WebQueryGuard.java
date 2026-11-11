package dev.pocketengine.features.web;

import java.util.regex.Pattern;

/** Bounded public query admission. A model argument never constitutes consent to export secrets. */
public final class WebQueryGuard {
    private static final Pattern SECRET=Pattern.compile(
            "(?i)(?:\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|AIza[A-Za-z0-9_-]{20,})\\b"
            +"|\\b(?:bearer|authorization|password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret)\\s*[:= ]\\s*\\S{12,}"
            +"|\\beyJ[A-Za-z0-9_-]{16,}\\.eyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{10,}\\b)");
    private static final Pattern CONTACT=Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]{1,64}@[A-Z0-9.-]{1,128}\\.[A-Z]{2,20}\\b");
    private static final Pattern BULK_SECRET=Pattern.compile(
            "(?<![a-zA-Z0-9])[A-Za-z0-9_+/=-]{80,}(?![a-zA-Z0-9])");
    private WebQueryGuard(){}
    public static String requirePublic(String raw,int max)throws Exception {
        if(raw==null||raw.isBlank()||raw.length()>max||raw.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("公开检索词为空、过长或含控制字符");
        String query=raw.trim();
        if(SECRET.matcher(query).find()||CONTACT.matcher(query).find()||BULK_SECRET.matcher(query).find())
            throw new SecurityException("公开检索词疑似包含个人联系方式或秘密凭据，未发送给外部检索服务");
        return query;
    }
}
