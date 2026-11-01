package dev.pocketengine.features.attachments.documents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, untrusted document content; no task state or executable instructions. */
public final class DocumentContent {
    private DocumentContent() {}
    public static final String PARSER_VERSION = "pocket-document-2";
    public static final class Request {
        public final int startUnit, startChunk, maxUnits, maxChars, maxChunks;
        public final String extraction;
        public Request(int startUnit, int startChunk, int maxUnits, int maxChars, int maxChunks) {
            this(startUnit,startChunk,maxUnits,maxChars,maxChunks,"auto");
        }
        public Request(int startUnit, int startChunk, int maxUnits, int maxChars, int maxChunks,String extraction) {
            if (startUnit < 1 || startUnit > 1000000 || startChunk < 0 || startChunk > 1000000
                    || maxUnits < 1 || maxUnits > 32 || maxChars < 2048 || maxChars > 65536
                    || maxChunks < 1 || maxChunks > 256) throw new IllegalArgumentException("Invalid document range or limits");
            if(!"auto".equals(extraction)&&!"text".equals(extraction)&&!"ocr".equals(extraction))throw new IllegalArgumentException("Unsupported extraction mode");
            this.startUnit=startUnit; this.startChunk=startChunk; this.maxUnits=maxUnits;
            this.maxChars=maxChars; this.maxChunks=maxChunks;
            this.extraction=extraction;
        }
        public static Request defaults() { return new Request(1,0,8,12000,48); }
    }
    public static final class Chunk {
        public final int unit, chunk;
        public final String kind, text;
        public final Map<String,String> locator;
        Chunk(int unit, int chunk, String kind, String text, Map<String,String> locator) {
            this.unit=unit; this.chunk=chunk; this.kind=kind; this.text=text;
            this.locator=Collections.unmodifiableMap(new LinkedHashMap<>(locator));
        }
    }
    public static final class Result {
        public final String format, status, coverage;
        public final List<Chunk> chunks;
        public final List<String> warnings;
        public final int totalUnits, nextUnit, nextChunk;
        public final boolean truncated;
        Result(String format,String status,String coverage,List<Chunk> chunks,List<String> warnings,
                int totalUnits,int nextUnit,int nextChunk) {
            this.format=format; this.status=status; this.coverage=coverage;
            this.chunks=Collections.unmodifiableList(new ArrayList<>(chunks));
            this.warnings=Collections.unmodifiableList(new ArrayList<>(warnings));
            this.totalUnits=totalUnits; this.nextUnit=nextUnit; this.nextChunk=nextChunk;
            this.truncated=nextUnit>0;
        }
    }
    static final class Collector {
        final Request request;
        final List<Chunk> chunks=new ArrayList<>();
        final List<String> warnings=new ArrayList<>();
        int chars,totalUnits,nextUnit,nextChunk,lastUnit,unitChunk;
        Collector(Request request) { this.request=request; }
        void unit(int unit) { totalUnits=Math.max(totalUnits,unit); }
        void warning(String warning) {
            if(warning==null)return;
            warning=warning.substring(0,Math.min(300,warning.length()));
            if (!warnings.contains(warning) && warnings.size()<16) warnings.add(warning);
        }
        void add(int unit,String kind,Map<String,String> locator,String text) {
            unit(unit);
            if(lastUnit!=unit) {lastUnit=unit;unitChunk=0;}
            for(int offset=0;offset<text.length();) {
                int end=Math.min(offset+1024,text.length());
                if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1))) end--;
                int charStart=offset;
                String value=text.substring(offset,end); offset=end; int index=unitChunk++;
                if(unit<request.startUnit || (unit==request.startUnit && index<request.startChunk))continue;
                if(nextUnit>0)continue;
                if(unit>=request.startUnit+request.maxUnits || chunks.size()>=request.maxChunks || chars+value.length()>request.maxChars) {
                    nextUnit=unit;nextChunk=index;continue;
                }
                Map<String,String> precise=new LinkedHashMap<>(locator);
                precise.put("charStart",Integer.toString(charStart));precise.put("charEnd",Integer.toString(end));
                chunks.add(new Chunk(unit,index,kind,value,precise));chars+=value.length();
            }
        }
        Result result(String format,String status,String coverage) {
            if(nextUnit==0 && totalUnits>=request.startUnit+request.maxUnits) {nextUnit=request.startUnit+request.maxUnits;nextChunk=0;}
            if("ok".equals(status)&&chunks.isEmpty()&&request.startUnit>totalUnits)status="empty_range";
            return new Result(format,status,coverage,chunks,warnings,totalUnits,nextUnit,nextChunk);
        }
    }
    public static Map<String,String> location(String... fields) {
        Map<String,String> map=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2) map.put(fields[i],fields[i+1]);
        return map;
    }
}
