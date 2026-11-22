package dev.pocketengine.store;

import android.util.JsonToken;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact first-party reminder provenance. Only the selected Book rows' required values are decoded. */
public final class OriginalReminderSourceFields {
    private static final Set<String> RULE=Set.of("id","kind","revision","schedule","nativeOrigin","sourceInvalidated","sourceCheckPending");
    private static final Set<String> PROPOSAL=Set.of("id","identity","origin","draft","digest","status","ruleCreationEntered",
            "ruleCreationState","ruleId","sourceInvalidated","sourceCheckPending","executionAuthorized");
    private static final Set<String> RECEIPT=Set.of("id","ruleId","kind","revision","instanceKey","occurrenceAt","nativeOrigin",
            "status","sourceInvalidated","sourceCheckPending");
    private OriginalReminderSourceFields(){}
    public record Occurrence(String id,int revision,long at){}
    public record Selected(JSONObject rule,JSONObject proposal,JSONObject receipt){}

    /** Caller holds the original AutomationBook lock. No JSON tree or unrelated bodies are loaded. */
    public static Selected read(File file,int maxBytes,String id,Occurrence occurrence,BooleanSupplier current)throws Exception{
        if(!current.getAsBoolean()||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.size(file.toPath())>maxBytes)return null;
        try(InputStream source=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS);
            MigrationJsonInput in=new MigrationJsonInput(new InputStreamReader(new BookBytes(source,maxBytes,current),StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),256)){
            JSONObject rule=null,proposal=null,receipt=null;boolean version=false;
            Set<String> keys=new HashSet<>();in.beginObject();
            while(in.hasNext()){
                String key=in.boundedName(128);if(!keys.add(key)||keys.size()>64)throw invalid();
                switch(key){
                    case "version" -> {version=in.peek()==JsonToken.NUMBER&&"1".equals(in.nextNumber());if(!version)return null;}
                    case "rules" -> rule=selected(in,id,RULE,100);
                    case "proposals" -> proposal=selected(in,id,PROPOSAL,100);
                    case "receipts" -> {if(occurrence==null)in.skipUnselectedValue();else receipt=selected(in,occurrence.id(),RECEIPT,1000);}
                    default -> in.skipUnselectedValue();
                }
            }
            in.endObject();
            return in.peek()==JsonToken.END_DOCUMENT&&version&&rule!=null&&proposal!=null&&
                    (occurrence==null||receipt!=null)&&current.getAsBoolean()?new Selected(rule,proposal,receipt):null;
        }
    }
    private static JSONObject selected(MigrationJsonInput in,String id,Set<String> fields,int limit)throws Exception{
        JSONObject result=null;int count=0;in.beginArray();
        while(in.hasNext()){
            if(++count>limit)throw invalid();
            JSONObject row=null;Set<String> keys=new HashSet<>();in.beginObject();
            while(in.hasNext()){
                String key=in.boundedName(128);if(!keys.add(key)||keys.size()>128)throw invalid();
                if("id".equals(key)){
                    if(in.peek()!=JsonToken.STRING)throw invalid();
                    String actual=in.boundedString(240);
                    // Native producers put the ID before payload fields. Older/malformed
                    // rows with no selected proof remain unavailable, never guessed.
                    if(id.equals(actual)){
                        if(result!=null)throw invalid();
                        row=new JSONObject().put("id",actual);
                    }
                }else if(row!=null&&fields.contains(key)){
                    SmallValue value=new SmallValue("draft".equals(key)?16384:2048);in.copyValue(value,null,0);
                    row.put(key,new JSONArray("["+value.text+"]").get(0));
                }else in.skipUnselectedValue();
            }
            in.endObject();if(row!=null)result=row;
        }
        in.endArray();return result;
    }
    private static IOException invalid(){return new IOException("原提醒记录超过读取范围或身份不可核对");}
    private static final class SmallValue extends Writer{
        private final int maximum;private final StringBuilder text=new StringBuilder();
        SmallValue(int maximum){this.maximum=maximum;}
        @Override public void write(char[] chars,int start,int length)throws IOException{
            if(length>maximum-text.length())throw invalid();text.append(chars,start,length);
        }
        @Override public void flush(){}
        @Override public void close(){}
    }
    private static final class BookBytes extends FilterInputStream{
        private long left;private final BooleanSupplier current;
        BookBytes(InputStream source,long maximum,BooleanSupplier current){super(source);left=maximum;this.current=current;}
        private void check()throws IOException{if(!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid();}
        @Override public int read()throws IOException{check();int value=super.read();if(value>=0&&--left<0)throw invalid();return value;}
        @Override public int read(byte[] data,int off,int len)throws IOException{
            check();int read=super.read(data,off,(int)Math.min(len,left+1));if(read>0&&(left-=read)<0)throw invalid();return read;
        }
    }
}
