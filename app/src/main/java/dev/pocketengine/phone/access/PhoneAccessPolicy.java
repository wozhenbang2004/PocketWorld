package dev.pocketengine.phone.access;

import java.util.Map;
import java.util.Set;

/** Native owner settings, evaluated live. Screen sharing never grants automation. */
public interface PhoneAccessPolicy {
    enum Use { AUTOMATION,OWNER_SHARE }
    record Rule(boolean automation,boolean ownerShare) { }
    record State(long revision,boolean available,Map<AppCopyKey,Rule> rules) {
        public State {if(revision<0)throw new IllegalArgumentException("invalid_policy_revision");rules=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(rules));}
        public void require(Set<AppCopyKey> owners,boolean identityKnown,Use use) {
            if(!available)throw new Denied("app_policy_unavailable");
            if(!identityKnown)throw new Denied("app_owner_unknown");
            for(AppCopyKey key:owners){Rule rule=rules.get(key);if(rule!=null&&!(use==Use.AUTOMATION?rule.automation():rule.ownerShare()))throw new Denied("app_access_denied");}
        }
    }
    State current();
    final class Denied extends SecurityException {public final String code;public Denied(String code){super(code);this.code=code;}}
}
