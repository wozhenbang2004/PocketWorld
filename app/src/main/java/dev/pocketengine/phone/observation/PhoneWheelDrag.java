package dev.pocketengine.phone.observation;

import java.math.BigDecimal;
import java.math.MathContext;
import org.json.JSONArray;
import org.json.JSONObject;

/** Proposes one gesture from current rows. It neither dispatches input nor grants coordinate authority. */
public final class PhoneWheelDrag {
    private static final int MAX_UNCHANGED=3;
    private PhoneWheelDrag() { }

    /** previousAttempt may only come from this run's settled, accepted, known-effect action. */
    public static JSONObject plan(JSONObject actual,JSONObject wheel,String target,String direction,JSONObject previousAttempt)throws Exception {
        return planColumn(actual,PhoneWheelValue.column(actual,wheel),nodeKey(wheel),wheel.optBoolean("enabled",true),target,direction,previousAttempt);
    }
    static JSONObject planVisual(JSONObject actual,JSONObject bounds,String regionId,String target,String direction,JSONObject previousAttempt)throws Exception {
        return planColumn(actual,PhoneWheelValue.visualColumn(actual,bounds),"visual_region:"+regionId,true,target,direction,previousAttempt);
    }
    private static JSONObject planColumn(JSONObject actual,JSONObject column,String nodeKey,boolean enabled,String target,String direction,JSONObject previousAttempt)throws Exception {
        if(target==null||target.isBlank()||target.length()>100||!("forward".equals(direction)||"backward".equals(direction)))throw new IllegalArgumentException("wheel_drag_arguments_invalid");
        JSONObject reading=PhoneWheelValue.summary(column);
        if(!enabled)return unavailable("wheel_disabled",reading);
        if(!reading.optBoolean("readable"))return unavailable(reading.optString("reason","value_unavailable"),reading);
        String value=reading.getString("value");
        if(PhoneWheelValue.matches(value,target))return unavailable("target_observed",reading).put("reached",true);
        if(actual.optBoolean("changedDuringRead"))return unavailable("wheel_geometry_changed",reading);
        if(!column.optBoolean("columnReady"))return unavailable(column.optString("columnReason","column_unavailable"),reading);
        String observationId=actual.getString("observationId"),scope=PhoneTreeSequence.scope(actual);
        if(nodeKey.isEmpty())return unavailable("wheel_native_anchor_unavailable",reading);
        if(previousAttempt!=null){
            if(!scope.equals(previousAttempt.optString("scope"))||!nodeKey.equals(previousAttempt.optString("nodeKey"))
                    ||!target.equals(previousAttempt.optString("target")))return unavailable("wheel_attempt_scope_changed",reading);
            if(observationId.equals(previousAttempt.optString("observationId")))return unavailable("wheel_readback_not_refreshed",reading);
        }

        JSONArray items=column.getJSONArray("items");int selected=column.getInt("selectedIndex");double rowHeight=column.getDouble("rowHeight");
        int visibleTarget=unique(items,target);
        if(visibleTarget==-2)return unavailable("target_visible_more_than_once",reading);
        NumericInterval interval=interval(items);
        double targetRows,shift;String decision;
        if(visibleTarget>=0){
            targetRows=visibleTarget-selected;
            shift=items.getJSONObject(selected).getDouble("centerY")-items.getJSONObject(visibleTarget).getDouble("centerY");
            decision="visible_target_position";
        }else{
            Double numericRows=interval==null?null:interval.rows(value,target);
            if(numericRows!=null){targetRows=numericRows;decision="consistent_visible_numeric_interval";}
            else{targetRows="forward".equals(direction)?1:-1;decision="explicit_direction_single_row";}
            shift=-clamp(targetRows,-3,3)*rowHeight;
        }
        if(shift==0||!Double.isFinite(shift)||!Double.isFinite(targetRows))return unavailable("wheel_motion_unavailable",reading);

        int unchanged=0,oscillations=0,corrections=0,polarity=1;
        double gain=1;Double observedRows=null;boolean changed=false,moreRowsThanPlanned=false,directionCorrected=false;
        String previousValue="",movementBasis="none";
        if(previousAttempt!=null){
            previousValue=previousAttempt.optString("currentValue");changed=!PhoneWheelValue.matches(previousValue,value);
            unchanged=changed?0:previousAttempt.optInt("unchangedAttempts")+1;
            if(unchanged>=MAX_UNCHANGED)return unavailable("no_progress_observed",reading).put("unchangedAttempts",unchanged);
            if(changed&&PhoneWheelValue.matches(previousAttempt.optString("priorValue"),value))oscillations=previousAttempt.optInt("oscillations")+1;
            if(oscillations>=3)return unavailable("wheel_values_oscillating",reading).put("oscillations",oscillations);
            gain=clamp(previousAttempt.optDouble("gain",1),.55,1.6);
            polarity=previousAttempt.optInt("motionPolarity",1)==-1?-1:1;
            corrections=Math.max(0,previousAttempt.optInt("directionCorrections"));
            JSONArray before=previousAttempt.optJSONArray("visibleValues");
            if(changed&&before!=null){
                int from=uniqueValues(before,previousValue),to=uniqueValues(before,value);
                if(from>=0&&to>=0){observedRows=(double)(to-from);movementBasis="previous_visible_values";}
            }
            if(changed&&observedRows==null&&interval!=null&&interval.increment.toPlainString().equals(previousAttempt.optString("numericIncrement"))){
                observedRows=interval.rows(previousValue,value);if(observedRows!=null)movementBasis="consistent_numeric_readback";
            }
            double previousShift=previousAttempt.optDouble("dy");
            if(observedRows!=null&&observedRows!=0&&Double.isFinite(observedRows)&&previousShift!=0){
                int observedPolarity=Math.signum(observedRows)==Math.signum(-previousShift)?1:-1;
                if(observedPolarity!=polarity){
                    if(corrections>=1)return unavailable("wheel_movement_direction_inconsistent",reading);
                    polarity=observedPolarity;corrections++;directionCorrected=true;
                }
                double measured=Math.abs(previousShift)/(Math.abs(observedRows)*rowHeight);
                gain=clamp((gain+clamp(measured,.4,2))/2,.55,1.6);
                moreRowsThanPlanned=Math.abs(observedRows)>Math.abs(previousAttempt.optDouble("requestedRows"))+0.5;
            }
        }

        JSONObject bounds=column.getJSONObject("bounds");double top=bounds.getDouble("top"),bottom=bounds.getDouble("bottom");
        double margin=Math.max(3,Math.min(rowHeight*.2,(bottom-top)*.12));
        double low=Math.max(1,top+margin),high=Math.min(999,bottom-margin),space=high-low;
        if(space<8)return unavailable("wheel_drag_space_unavailable",reading);
        double distance=Math.min(space,Math.max(8,Math.abs(shift)*gain*(1+.35*unchanged)));
        double dy=Math.copySign(distance,shift)*polarity;
        double middle=clamp(items.getJSONObject(selected).getDouble("centerY"),low+distance/2,high-distance/2);
        int x=(int)Math.round(clamp(column.getDouble("dragX"),Math.max(1,bounds.getDouble("left")+1),Math.min(999,bounds.getDouble("right")-1)));
        int y0=(int)Math.round(middle-dy/2),y1=(int)Math.round(middle+dy/2);
        if(Math.abs(y1-y0)<8||x<=bounds.getDouble("left")||x>=bounds.getDouble("right")
                ||y0<low-.5||y0>high+.5||y1<low-.5||y1>high+.5)return unavailable("wheel_drag_space_unavailable",reading);
        int duration=unchanged==2?450:unchanged==1?650:moreRowsThanPlanned||directionCorrected?1100:
                (int)clamp(650+Math.abs(dy)/rowHeight*140,750,1200);
        JSONArray path=new JSONArray().put(new JSONArray().put(x).put(y0)).put(new JSONArray().put(x).put(y1));
        JSONObject gesture=new JSONObject().put("observationId",observationId).put("paths",new JSONArray().put(path)).put("durationMs",duration);
        JSONArray values=new JSONArray();for(int i=0;i<items.length();i++)values.put(items.getJSONObject(i).getString("text"));
        JSONObject movement=new JSONObject().put("sourceObservationId",previousAttempt==null?JSONObject.NULL:previousAttempt.optString("observationId"))
                .put("observationId",observationId).put("previousValue",previousAttempt==null?JSONObject.NULL:previousValue)
                .put("currentValue",value).put("valueChanged",previousAttempt==null?JSONObject.NULL:changed)
                .put("observedRows",observedRows==null?JSONObject.NULL:observedRows).put("basis",movementBasis)
                .put("moreRowsThanPlanned",moreRowsThanPlanned).put("directionCorrected",directionCorrected)
                .put("causedByPreviousActionVerified",false);
        JSONObject attempt=new JSONObject().put("observationId",observationId).put("scope",scope).put("nodeKey",nodeKey).put("target",target)
                .put("currentValue",value).put("priorValue",previousValue).put("visibleValues",values).put("selectedIndex",selected)
                .put("rowHeight",rowHeight).put("numericIncrement",interval==null?"":interval.increment.toPlainString())
                .put("requestedRows",-shift/rowHeight).put("targetRows",targetRows).put("dy",y1-y0).put("durationMs",duration)
                .put("gain",gain).put("motionPolarity",polarity).put("directionCorrections",corrections)
                .put("unchangedAttempts",unchanged).put("oscillations",oscillations)
                .put("observedMovement",movement).put("inputSentByPlanner",false);
        JSONObject geometry=new JSONObject().put("bounds",new JSONObject(bounds.toString())).put("rowHeight",rowHeight)
                .put("columnSource",column.getString("columnSource")).put("selectedIndex",selected).put("visibleItems",items);
        return new JSONObject().put("available",true).put("reason","single_drag_proposed").put("reading",reading)
                .put("decision",decision).put("geometry",geometry).put("gesture",gesture).put("attempt",attempt)
                .put("observedMovement",movement).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
    }

    private static String nodeKey(JSONObject wheel)throws Exception {
        String structure=wheel.optString("structuralFingerprint");
        return structure.isEmpty()||wheel.optJSONArray("path")==null?"":wheel.getInt("windowId")+":"+wheel.getJSONArray("path")+":"+structure;
    }
    private static int unique(JSONArray items,String value)throws Exception {
        int found=-1;for(int i=0;i<items.length();i++)if(PhoneWheelValue.matches(items.getJSONObject(i).getString("text"),value)){if(found>=0)return -2;found=i;}return found;
    }
    private static int uniqueValues(JSONArray values,String value)throws Exception {
        int found=-1;for(int i=0;i<values.length();i++)if(PhoneWheelValue.matches(values.getString(i),value)){if(found>=0)return -2;found=i;}return found;
    }
    private static NumericInterval interval(JSONArray items)throws Exception {
        if(items.length()<3)return null;
        PhoneWheelValue.Numeric first=PhoneWheelValue.numeric(items.getJSONObject(0).getString("text")),previous=first;
        if(first==null)return null;BigDecimal increment=null;
        for(int i=1;i<items.length();i++){
            PhoneWheelValue.Numeric next=PhoneWheelValue.numeric(items.getJSONObject(i).getString("text"));
            if(!first.sameFormat(next))return null;BigDecimal difference=next.value.subtract(previous.value);
            if(difference.signum()==0||increment!=null&&difference.compareTo(increment)!=0)return null;
            increment=difference;previous=next;
        }
        return new NumericInterval(first,increment.stripTrailingZeros());
    }
    private static final class NumericInterval {
        final PhoneWheelValue.Numeric format;final BigDecimal increment;
        NumericInterval(PhoneWheelValue.Numeric format,BigDecimal increment){this.format=format;this.increment=increment;}
        Double rows(String from,String to){
            PhoneWheelValue.Numeric a=PhoneWheelValue.numeric(from),b=PhoneWheelValue.numeric(to);
            if(!format.sameFormat(a)||!format.sameFormat(b))return null;
            BigDecimal difference=b.value.subtract(a.value);
            if(difference.remainder(increment).signum()!=0)return null;
            double result=difference.divide(increment,MathContext.DECIMAL64).doubleValue();return Double.isFinite(result)?result:null;
        }
    }
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
    private static JSONObject unavailable(String reason,JSONObject reading)throws Exception {
        return new JSONObject().put("available",false).put("reason",reason).put("reading",reading)
                .put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
    }
}
