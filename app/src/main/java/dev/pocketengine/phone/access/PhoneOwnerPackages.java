package dev.pocketengine.phone.access;

import dev.pocketengine.core.RunControl;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A text-free native ownership probe. Missing profile identity must not become the main profile. */
public record PhoneOwnerPackages(Set<AppCopyKey> owners,boolean known) {
    public PhoneOwnerPackages {owners=java.util.Collections.unmodifiableSet(new LinkedHashSet<>(owners));}
    /** Seeing the requested profile beside another copy is not proof of one unambiguous landing. */
    public boolean provesCopy(AppCopyKey requested){if(!known||!owners.contains(requested))return false;for(AppCopyKey actual:owners)if(actual.packageName().equals(requested.packageName())&&!actual.equals(requested))return false;return true;}
    @FunctionalInterface public interface Probe {PhoneOwnerPackages read(RunControl control)throws Exception;}
    /** The helper has no resumed owner on a new display, so its top-level status can be unknown. */
    public static boolean provesEmptyOwnedDisplay(JSONObject actual,int display) {
        if(actual==null||!Boolean.TRUE.equals(actual.opt("allDisplayIdentitiesKnown")))return false;
        String status=actual.optString("status");if(!"observed".equals(status)&&!"unknown".equals(status))return false;
        JSONArray owned=actual.optJSONArray("owned");
        // The existing topology validator rejects missing/incomplete facts and any task on this display.
        return owned!=null&&owned.length()==0&&dev.pocketengine.phone.display.DisplayLandingSafety.failure(null,actual,display)==null;
    }
    public static PhoneOwnerPackages fromOwnership(JSONObject actual,String rows)throws Exception {
        if(actual==null||!"observed".equals(actual.optString("status")))return new PhoneOwnerPackages(Set.of(),false);
        JSONArray source=actual.optJSONArray(rows);if(source==null)return new PhoneOwnerPackages(Set.of(),false);
        Set<AppCopyKey> keys=new LinkedHashSet<>();boolean known=true;
        for(int i=0;i<source.length();i++){JSONObject row=source.optJSONObject(i);if(row==null||!(row.opt("userId") instanceof Number)){known=false;continue;}
            try{keys.add(new AppCopyKey(row.getString("packageName"),row.getInt("userId")));}catch(Exception invalid){known=false;}}
        return new PhoneOwnerPackages(keys,known);
    }
}
