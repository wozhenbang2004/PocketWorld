package dev.pocketengine.features.proxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatCompletionStreamTest {
    private static final String KEY="upstream-key";
    private static String chunk(String text)throws Exception {return chunk(0,new JSONObject().put("content",text),null);}
    private static String chunk(int choice,JSONObject delta,String finish)throws Exception {
        return new JSONObject().put("object","chat.completion.chunk").put("id","fixture")
                .put("choices",new JSONArray().put(new JSONObject().put("index",choice).put("delta",delta).put("finish_reason",finish==null?JSONObject.NULL:finish))).toString();
    }
    private static String event(String json){return "data: "+json+"\n\n";}
    private static List<String> relay(String wire)throws Exception {
        List<String> output=new ArrayList<>();ChatCompletionStream.relay(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),KEY,output::add);return output;
    }
    private static JSONObject delta(String event)throws Exception{return new JSONObject(event).getJSONArray("choices").getJSONObject(0).getJSONObject("delta");}
    @Test public void supportsUtf8MultilineDataBomCrLfCommentsAndUsage()throws Exception {
        String json=chunk("你好🙂");int split=json.indexOf(',');
        String usage="{\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"total_tokens\":5}}";
        List<String> output=relay("\uFEFF: heartbeat "+KEY+"\r\nid: "+KEY+"\r\nretry: 1\r\n\r\n"+
                "data: "+json.substring(0,split+1)+"\r\ndata: "+json.substring(split+1)+"\r\n\r\n"+
                "event: message\rdata: "+usage+"\r\rdata: [DONE]\r\r");
        assertEquals(3,output.size());assertEquals("你好🙂",delta(output.get(0)).getString("content"));
        assertEquals(0,new JSONObject(output.get(1)).getJSONArray("choices").length());assertEquals("[DONE]",output.get(2));
        assertFalse(output.toString().contains(KEY));
    }
    @Test public void emitsFirstEventBeforeRequestingTheNextEventEvenForCrOnly()throws Exception {
        byte[] first=("data: "+chunk("first")+"\r\r").getBytes(StandardCharsets.UTF_8),last="data: [DONE]\r\r".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean delivered=new AtomicBoolean();
        InputStream gated=new InputStream(){int at;public int read()throws IOException{if(at<first.length)return first[at++]&255;if(!delivered.get())throw new IOException("buffered until end");int i=at++-first.length;return i<last.length?last[i]&255:-1;}
            public int read(byte[] b,int off,int len)throws IOException{int n=0;while(n<len){if(at==first.length&&n>0)break;int next=read();if(next<0)break;b[off+n++]=(byte)next;}return n==0?-1:n;}};
        List<String> output=new ArrayList<>();ChatCompletionStream.relay(gated,KEY,data->{output.add(data);delivered.set(true);});assertEquals(2,output.size());
    }
    @Test public void redactsEveryPartitionWithoutChangingEventOrTextOrder()throws Exception {
        for(int split=1;split<KEY.length();split++){
            List<String> output=relay(event(chunk("before "+KEY.substring(0,split)))+event(chunk(KEY.substring(split)+" after"))+event("[DONE]"));
            assertEquals(3,output.size());assertEquals("before [upstream credential hidden] after",delta(output.get(0)).getString("content")+delta(output.get(1)).getString("content"));
            assertFalse(output.toString().contains(KEY));
        }
    }
    @Test public void randomFragmentsPreserveTheSameRedactedText()throws Exception {
        Random random=new Random(741);String original="s up upstream-key x upstream upstream-keyupstream-key u done";
        for(int round=0;round<100;round++){
            StringBuilder wire=new StringBuilder();int events=0;
            for(int at=0;at<original.length();){int end=Math.min(original.length(),at+1+random.nextInt(9));wire.append(event(chunk(original.substring(at,end))));at=end;events++;}
            wire.append(event("[DONE]"));List<String> output=relay(wire.toString());assertEquals(events+1,output.size());StringBuilder joined=new StringBuilder();
            for(String item:output)if(!item.equals("[DONE]"))joined.append(delta(item).getString("content"));
            assertEquals(original.replace(KEY,"[upstream credential hidden]"),joined.toString());
        }
    }
    @Test public void preservesToolArgumentFragmentsAndChoiceIsolation()throws Exception {
        JSONObject firstTool=new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",7).put("id","call_x").put("type","function").put("function",new JSONObject().put("name","lookup").put("arguments","{\"value\":\"upstream-"))));
        JSONObject secondTool=new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("index",7).put("function",new JSONObject().put("arguments","key\"}"))));
        List<String> output=relay(event(chunk(2,firstTool,null))+event(chunk(1,new JSONObject().put("content","independent"),null))+event(chunk(2,secondTool,"tool_calls"))+event("[DONE]"));
        assertEquals(4,output.size());assertEquals(1,new JSONObject(output.get(1)).getJSONArray("choices").getJSONObject(0).getInt("index"));
        JSONObject a=delta(output.get(0)).getJSONArray("tool_calls").getJSONObject(0),b=delta(output.get(2)).getJSONArray("tool_calls").getJSONObject(0);
        assertEquals(7,b.getInt("index"));assertEquals("{\"value\":\"[upstream credential hidden]\"}",a.getJSONObject("function").getString("arguments")+b.getJSONObject("function").getString("arguments"));
    }
    @Test public void incompleteCredentialPrefixIsReleasedAtChoiceFinishAndDone()throws Exception {
        List<String> output=relay(event(chunk("upstream-"))+event(chunk(0,new JSONObject(),"stop"))+event("[DONE]"));
        assertEquals("upstream-",delta(output.get(0)).getString("content"));assertEquals(3,output.size());
        output=relay(event(chunk("u"))+event("[DONE]"));assertEquals("u",delta(output.get(0)).getString("content"));
    }
    @Test public void fieldsWithNewlinesStayInsideJsonAndNoSseControlIsForwarded()throws Exception {
        String text="a\n\ndata: [DONE]\nevent: attack\n";
        List<String> output=relay(": "+KEY+"\nid: attacker\nunknown: value\n\n"+event(chunk(text))+event("[DONE]"));
        assertFalse(output.get(0).contains("\n"));assertEquals(text,delta(output.get(0)).getString("content"));
    }
    @Test public void malformedTruncatedAndErrorStreamsFailWithoutDone()throws Exception {
        for(String bad:new String[]{event("not json"),event("{\"error\":{\"message\":\""+KEY+"\"}}"),"event: error\ndata: {}\n\n",event(chunk("valid")),"data: "+chunk("unterminated"),event("[DONE]"),event("{\"object\":\"other\",\"choices\":[]}")}){
            List<String> output=new ArrayList<>();try{ChatCompletionStream.relay(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)),KEY,output::add);fail("should reject");}catch(IOException expected){}
            assertFalse(output.contains("[DONE]"));assertFalse(output.toString().contains(KEY));
        }
    }
    @Test public void nestedJsonAndTrailingObjectsAreRejectedBeforeRecursiveProcessing()throws Exception {
        try{relay(event("{\"x\":"+"[".repeat(70)+"0"+"]".repeat(70)+"}"));fail();}catch(IOException expected){}
        try{relay(event(chunk("safe")+" {}")+event("[DONE]"));fail();}catch(IOException expected){}
    }
    @Test public void malformedUtf8AndOversizedEventsAreRejected()throws Exception {
        byte[] invalid={'d','a','t','a',':',' ',(byte)0xc3,0x28,'\n','\n'};
        try{ChatCompletionStream.relay(new ByteArrayInputStream(invalid),KEY,data->fail());fail();}catch(IOException expected){}
        try{relay("data: "+"x".repeat(ChatCompletionStream.MAX_EVENT_BYTES)+"\n\n");fail();}catch(IOException expected){}
    }
    @Test public void aggregateAndLookbehindAreBounded()throws Exception {
        String comment=":"+"x".repeat(1000)+"\n\n";
        try{relay(comment.repeat(ChatCompletionStream.MAX_STREAM_BYTES/1000+1));fail();}catch(IOException expected){}
        String usage=event("{\"object\":\"chat.completion.chunk\",\"choices\":[]}");
        List<String> output=new ArrayList<>();
        try{ChatCompletionStream.relay(new ByteArrayInputStream((event(chunk("u"))+usage.repeat(257)+event("[DONE]")).getBytes(StandardCharsets.UTF_8)),KEY,output::add);fail();}catch(IOException expected){}
        assertTrue(output.isEmpty());
    }
    @Test public void unsupportedFieldsLogprobsAndResumedFinishedChoiceFailClosed()throws Exception {
        JSONObject logprobs=new JSONObject(chunk("safe"));logprobs.getJSONArray("choices").getJSONObject(0).put("logprobs",new JSONObject().put("content",new JSONArray()));
        for(String bad:new String[]{event(chunk(0,new JSONObject().put("audio",new JSONObject()),null)),event(logprobs.toString()),event(chunk(0,new JSONObject(),"stop"))+event(chunk("again"))}){
            try{relay(bad+event("[DONE]"));fail();}catch(IOException expected){}
        }
    }
}
