package dev.pocketengine.features.automation;

import dev.pocketengine.core.RunControl;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Short native recheck for an already reviewed original run; it never waits or dispatches. */
public final class AutomationRunGate {
    private AutomationRunGate(){}
    public static void bind(RunControl control,JSONObject request,AutomationConditions.Source conditions)throws Exception {bind(control,request,conditions,System::currentTimeMillis);}
    public static void bind(RunControl control,JSONObject request,AutomationConditions.Source conditions,LongSupplier clock)throws Exception {
        if(!Boolean.TRUE.equals(request.opt("modelObservationConsent")))throw new SecurityException("此目标没有模型观察授权");
        long scheduled=AutomationPolicy.integer(request,"scheduledAt",1,AutomationPolicy.MAX_TIME),deadline=AutomationPolicy.integer(request,"validUntil",1,AutomationPolicy.MAX_TIME);
        if(deadline<=scheduled)throw new SecurityException("原执行窗口无效");
        JSONObject policy=AutomationPolicy.rule(request.getJSONObject("runPolicy"),"UTC");
        control.setAuthorityCheck(()->{
            long now=clock.getAsLong();if(now>=deadline)throw new SecurityException("本目标执行窗口已结束");
            if(now<scheduled)control.waitForCondition("not_due",deadline);
            if(AutomationPolicy.quietEnd(policy,now)>now)control.waitForCondition("quiet_hours",deadline);
            if(!AutomationConditions.evaluate(policy,conditions,now).getBoolean("allowed"))control.waitForCondition("run_conditions",deadline);
        });
    }
}
