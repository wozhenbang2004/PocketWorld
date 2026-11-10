package dev.pocketengine.phone.access;

import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.phone.observation.AccessibilityForest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Reads package metadata only before content; a resumed task alone cannot authorize another visible window. */
public final class PhoneWindowPackages {
    private PhoneWindowPackages() { }
    public static PhoneOwnerPackages read(PhoneAccessibilityService service,int display,PhoneOwnerPackages identities,AccessibilityForest.Check originalReadCheck)throws Exception {
        return read(service,display,identities,originalReadCheck,false);
    }
    /** Only original-session helper facts may distinguish a new empty display from unreadable app content. */
    public static PhoneOwnerPackages readOwnedDisplay(PhoneAccessibilityService service,int display,JSONObject ownership,AccessibilityForest.Check originalReadCheck)throws Exception {
        boolean empty=PhoneOwnerPackages.provesEmptyOwnedDisplay(ownership,display);
        PhoneOwnerPackages identities=empty?new PhoneOwnerPackages(Set.of(),true):PhoneOwnerPackages.fromOwnership(ownership,"owned");
        return read(service,display,identities,originalReadCheck,empty);
    }
    private static PhoneOwnerPackages read(PhoneAccessibilityService service,int display,PhoneOwnerPackages identities,AccessibilityForest.Check originalReadCheck,boolean emptyOwnedDisplay)throws Exception {
        originalReadCheck.check();if(service==null||service!=PhoneAccessibilityService.get()||service.observationSafetyFailure()!=null||!identities.known())return new PhoneOwnerPackages(Set.of(),false);
        SparseArray<List<AccessibilityWindowInfo>> all=service.getWindowsOnAllDisplays();Set<String> packages=new LinkedHashSet<>();boolean complete=true;
        if(all==null)return new PhoneOwnerPackages(Set.of(),false);
        try{
            List<AccessibilityWindowInfo> windows=all.get(display);
            if(windows==null||windows.isEmpty()){
                originalReadCheck.check();
                return new PhoneOwnerPackages(Set.of(),emptyOwnedDisplay&&service==PhoneAccessibilityService.get()&&service.observationSafetyFailure()==null);
            }
            if(windows.size()>32)return new PhoneOwnerPackages(Set.of(),false);
            for(AccessibilityWindowInfo window:windows){
                originalReadCheck.check();if(window==null||window.getDisplayId()!=display){complete=false;continue;}
                AccessibilityNodeInfo root=window.getRoot();try{if(root==null){complete=false;continue;}CharSequence owner=root.getPackageName();if(owner==null||owner.length()==0||owner.length()>250)complete=false;else if(!ignoreControlOverlay(window.getType(),owner.toString(),service.getPackageName()))packages.add(owner.toString());}finally{if(root!=null)root.recycle();}
            }
            originalReadCheck.check();if(service!=PhoneAccessibilityService.get()||service.observationSafetyFailure()!=null)complete=false;
            return match(packages,complete,identities);
        }finally{for(int i=0;i<all.size();i++){List<AccessibilityWindowInfo> windows=all.valueAt(i);if(windows!=null)for(AccessibilityWindowInfo window:windows)if(window!=null)window.recycle();}}
    }
    /** Only our own owner controls are outside app content; another service's visible overlay still needs policy. */
    public static boolean ignoreControlOverlay(int windowType,String reportedPackage,String ownPackage){
        return windowType==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&ownPackage!=null&&!ownPackage.isEmpty()&&ownPackage.equals(reportedPackage);
    }
    /** Every observed package must be covered by actual native user identities; ambiguity checks all candidates. */
    public static PhoneOwnerPackages match(Set<String> observed,boolean allRootsKnown,PhoneOwnerPackages identities){
        Set<AppCopyKey> owners=new LinkedHashSet<>();boolean known=allRootsKnown&&identities.known()&&!observed.isEmpty();
        for(String pkg:observed){boolean found=false;for(AppCopyKey key:identities.owners())if(key.packageName().equals(pkg)){owners.add(key);found=true;}if(!found)known=false;}
        return new PhoneOwnerPackages(owners,known);
    }
}
