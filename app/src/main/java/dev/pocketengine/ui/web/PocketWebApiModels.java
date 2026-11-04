package dev.pocketengine.ui.web;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** A window's native picker projection and one-use references, never another model store. */
public final class PocketWebApiModels implements AutoCloseable {
    public static final class Selection {
        private final PocketWebApiModels owner;
        private final long generation,revision,expires;
        private final String row;
        private final Boolean favorite;
        private boolean claimed;
        private Selection(PocketWebApiModels owner,long generation,long revision,long expires,JSONObject row,Boolean favorite){
            this.owner=owner;this.generation=generation;this.revision=revision;this.expires=expires;this.row=row.toString();this.favorite=favorite;
        }
        public long selectionRevision(){return revision;}
        public Boolean favorite(){return favorite;}
    }
    private final LongSupplier now;
    private final BooleanSupplier ownerCurrent;
    private final LinkedHashMap<String,Selection> reviews=new LinkedHashMap<>();
    private JSONObject snapshot;
    private long revision=-1,generation;
    private boolean closed;
    public PocketWebApiModels(LongSupplier monotonicMillis,BooleanSupplier ownerCurrent){this.now=Objects.requireNonNull(monotonicMillis);this.ownerCurrent=Objects.requireNonNull(ownerCurrent);}

    /** The caller obtained this exact snapshot with ModelSelectionCoordinator.readStable. */
    public synchronized JSONObject publish(JSONObject nativeSnapshot,long selectionRevision)throws Exception{
        requireOwner();if(selectionRevision<0)throw new IllegalArgumentException("请重新读取模型设置");
        if(!"pocketworld.model-picker.v1".equals(nativeSnapshot.optString("format")))throw new IllegalArgumentException("原模型列表不可用");
        JSONObject copy=new JSONObject(nativeSnapshot.toString());JSONArray rows=copy.getJSONArray("models");var identities=new HashSet<String>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String connection=string(row,"connectionId"),model=string(row,"modelId");
            if(!identities.add(connection+"\u0000"+model))throw new IllegalArgumentException("原模型列表含重复连接身份，请重新读取");
            if(!(row.opt("available") instanceof Boolean)||!(row.opt("favorite") instanceof Boolean))throw new IllegalArgumentException("原模型可用状态不可核对");
            if(row.optBoolean("available")&&(row.optJSONObject("binding")==null||number(row,"connectionRevision")<1))throw new IllegalArgumentException("原模型缺少连接修订");
        }
        snapshot=copy;revision=selectionRevision;generation++;reviews.clear();
        JSONObject out=new JSONObject(copy.toString());JSONArray publicRows=new JSONArray();for(int i=0;i<rows.length();i++)publicRows.put(publicRow(rows.getJSONObject(i)));
        return out.put("models",publicRows).put("selectionRevision",revision).put("viewGeneration",generation);
    }
    public synchronized boolean projectionCurrent(JSONObject value,long selectionRevision){return !closed&&ownerCurrent.getAsBoolean()&&snapshot!=null&&revision==selectionRevision&&value.optLong("selectionRevision",-1)==revision&&value.optLong("viewGeneration",-1)==generation;}

    public synchronized JSONObject review(String connection,String model,long connectionRevision,long selectionRevision)throws Exception{
        JSONObject row=row(connection,model,connectionRevision,selectionRevision);
        JSONObject out=new JSONObject().put("model",publicRow(row)).put("selectionRevision",revision).put("viewGeneration",generation);
        if(!row.optBoolean("available"))return out; // Its original favorite can still be removed.
        reviews.clear();String handle=UUID.randomUUID().toString();reviews.put(handle,capture(row,null));
        return out.put("reviewHandle",handle).put("expiresInMs",120000);
    }
    public synchronized Selection take(String handle,long selectionRevision){
        requireProjection(selectionRevision);Selection selected=reviews.remove(handle);requireCurrent(selected);
        if(selected.favorite!=null||selected.revision!=selectionRevision)throw new IllegalStateException("原模型审核已变化，请重新核对");
        return selected;
    }
    public synchronized Selection favorite(String connection,String model,long connectionRevision,boolean value,long selectionRevision)throws Exception{
        JSONObject row=row(connection,model,connectionRevision,selectionRevision);
        if(!snapshot.optBoolean("preferencesAvailable",false))throw new IllegalStateException("收藏记录暂不可保存，请重新读取");
        if(value&&!row.optBoolean("available")||!value&&!row.optBoolean("favorite"))throw new IllegalStateException("请重新核对这项模型的收藏状态");
        return capture(row,value);
    }
    /** Called in the reserved mutation immediately before the original native service. */
    public synchronized JSONObject claim(Selection selection)throws Exception{
        requireCurrent(selection);if(selection.claimed)throw new IllegalStateException("原模型操作已经领取");
        selection.claimed=true;return new JSONObject(selection.row);
    }
    private Selection capture(JSONObject row,Boolean favorite){return new Selection(this,generation,revision,Math.addExact(now.getAsLong(),120000),row,favorite);}
    private JSONObject row(String connection,String model,long expected,long selectionRevision)throws Exception{
        requireProjection(selectionRevision);JSONArray rows=snapshot.getJSONArray("models");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(connection.equals(row.getString("connectionId"))&&model.equals(row.getString("modelId"))){
            long actual=row.has("connectionRevision")?number(row,"connectionRevision"):0;
            if(actual!=expected)throw new IllegalStateException("原连接修订已变化，请重新读取");return new JSONObject(row.toString());
        }}throw new IllegalStateException("这项模型不在原连接列表，请重新读取");
    }
    private void requireProjection(long selectionRevision){requireOwner();if(snapshot==null||revision!=selectionRevision)throw new IllegalStateException("模型设置已变化，请重新读取原连接列表");}
    private void requireCurrent(Selection selected){requireOwner();if(selected==null||selected.owner!=this||selected.generation!=generation||selected.expires<=now.getAsLong())throw new IllegalStateException("原模型审核已关闭、使用或过期，请重新核对");}
    private void requireOwner(){if(closed||!ownerCurrent.getAsBoolean())throw new IllegalStateException("原模型窗口已关闭，请重新打开");}
    private static long number(JSONObject value,String key){Object raw=value.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0)throw new IllegalArgumentException("原模型修订不可核对");return ((Number)raw).longValue();}
    private static String string(JSONObject value,String key){Object raw=value.opt(key);if(!(raw instanceof String text)||text.isBlank()||text.indexOf('\u0000')>=0)throw new IllegalArgumentException("原模型身份不可核对");return (String)raw;}
    private static JSONObject publicRow(JSONObject row)throws Exception{JSONObject out=new JSONObject(row.toString());out.remove("binding");if(!out.has("connectionRevision"))out.put("connectionRevision",0);return out;}
    @Override public synchronized void close(){closed=true;generation++;snapshot=null;reviews.clear();}
}
