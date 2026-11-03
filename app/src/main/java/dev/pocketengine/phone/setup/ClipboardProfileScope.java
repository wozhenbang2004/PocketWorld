package dev.pocketengine.phone.setup;

import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.access.PhoneOwnerPackages;

/** Host ClipboardManager content can be consumed only by a proven target in that same Android user. */
public final class ClipboardProfileScope {
    private ClipboardProfileScope() { }
    public static void require(PhoneOwnerPackages actual,String targetPackage,int hostUser){
        if(actual==null||!actual.known()||actual.owners().isEmpty()||hostUser<0||hostUser>99999
                ||targetPackage==null||!targetPackage.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*"))
            throw new Unavailable("clipboard_profile_identity_unavailable");
        boolean found=false,host=false,other=false;
        for(AppCopyKey key:actual.owners())if(key.packageName().equals(targetPackage)){found=true;if(key.userId()==hostUser)host=true;else other=true;}
        if(!found)throw new Unavailable("clipboard_profile_identity_unavailable");
        if(!host||other)throw new Unavailable("clipboard_profile_mismatch_or_ambiguous");
    }
    public static final class Unavailable extends SecurityException {
        public final String code;
        public Unavailable(String code){super(code);this.code=code;}
        public Unavailable(String code,Throwable cause){super(code,cause);this.code=code;}
    }
}
