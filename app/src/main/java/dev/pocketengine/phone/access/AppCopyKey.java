package dev.pocketengine.phone.access;

/** Actual Android package/profile identity. A profile number is never an authority grant. */
public record AppCopyKey(String packageName,int userId) {
    public AppCopyKey {
        if(packageName==null||packageName.length()>250||!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||userId<0||userId>99999)
            throw new IllegalArgumentException("invalid_app_copy");
    }
    public String wire(){return packageName+"@"+userId;}
}
