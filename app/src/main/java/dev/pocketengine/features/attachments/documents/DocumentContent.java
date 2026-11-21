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
        private final boolean worksheetNavigation;
        public Request(int startUnit, int startChunk, int maxUnits, int maxChars, int maxChunks) {
            this(startUnit,startChunk,maxUnits,maxChars,maxChunks,"auto");
        }
        public Request(int startUnit, int startChunk, int maxUnits, int maxChars, int maxChunks,String extraction) {
            this(startUnit,startChunk,maxUnits,maxChars,maxChunks,extraction,false);
        }
        private Request(int startUnit, int startChunk, int maxUnits, int maxChars, int maxChunks,String extraction,boolean worksheetNavigation) {
            if (startUnit < 1 || startUnit > 1000000 || startChunk < 0 || startChunk > 1000000
                    || maxUnits < 1 || maxUnits > 32 || maxChars < 2048 || maxChars > 65536
                    || maxChunks < 1 || maxChunks > 256) throw new IllegalArgumentException("Invalid document range or limits");
            if(!"auto".equals(extraction)&&!"text".equals(extraction)&&!"ocr".equals(extraction))throw new IllegalArgumentException("Unsupported extraction mode");
            this.startUnit=startUnit; this.startChunk=startChunk; this.maxUnits=maxUnits;
            this.maxChars=maxChars; this.maxChunks=maxChunks;
            this.extraction=extraction; this.worksheetNavigation=worksheetNavigation;
        }
        public static Request defaults() { return new Request(1,0,8,12000,48); }
        /** Opt in only for the original owner reader; the actual workbook parser must still confirm XLSX. */
        public static Request originalOfficeView(int unit,int chunk,boolean word) {
            return new Request(unit,chunk,word?16:1,16000,128,"text",true);
        }
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
    public static final class Worksheet {
        public final int unit;
        public final String name;
        private Worksheet(int unit,String name) { this.unit=unit; this.name=name; }
    }
    public static final class Result {
        public final String format, status, coverage;
        public final List<Chunk> chunks;
        public final List<String> warnings;
        public final int totalUnits, nextUnit, nextChunk;
        /** Same-worksheet start of the preceding natural range, or -1 when it was not proven. */
        public final int previousWorksheetChunk;
        /** Bounded visible worksheet names in original order, only for the original reader. */
        public final List<Worksheet> worksheets;
        public final boolean truncated;
        Result(String format,String status,String coverage,List<Chunk> chunks,List<String> warnings,
                int totalUnits,int nextUnit,int nextChunk) {
            this(format,status,coverage,chunks,warnings,totalUnits,nextUnit,nextChunk,-1,Collections.emptyList());
        }
        private Result(String format,String status,String coverage,List<Chunk> chunks,List<String> warnings,
                int totalUnits,int nextUnit,int nextChunk,int previousWorksheetChunk,List<Worksheet> worksheets) {
            this.format=format; this.status=status; this.coverage=coverage;
            this.chunks=Collections.unmodifiableList(new ArrayList<>(chunks));
            this.warnings=Collections.unmodifiableList(new ArrayList<>(warnings));
            this.totalUnits=totalUnits; this.nextUnit=nextUnit; this.nextChunk=nextChunk;
            this.previousWorksheetChunk=previousWorksheetChunk;
            this.worksheets=worksheets.isEmpty()?Collections.emptyList():Collections.unmodifiableList(new ArrayList<>(worksheets));
            this.truncated=nextUnit>0;
        }
    }
    static final class Collector {
        private static final int MAX_WORKSHEET_CHOICES=64;
        final Request request;
        final List<Chunk> chunks=new ArrayList<>();
        final List<String> warnings=new ArrayList<>();
        int chars,totalUnits,nextUnit,nextChunk,lastUnit,unitChunk;
        private WorksheetRange precedingWorksheetRange;
        private final List<Worksheet> worksheets=new ArrayList<>();
        Collector(Request request) { this.request=request; }
        void trackPreviousWorksheetRange() {
            if(request.worksheetNavigation&&request.maxUnits==1&&request.startChunk>0)precedingWorksheetRange=new WorksheetRange();
        }
        private boolean rangeFull(int count,int characters,int incoming) {
            return count>=request.maxChunks||characters+incoming>request.maxChars;
        }
        private final class WorksheetRange {
            int start,count,characters,previous=-1;
            void observe(int index,int length) {
                boolean end=rangeFull(count,characters,length);
                if(index==request.startChunk){if(end)previous=start;return;}
                if(end){start=index;count=0;characters=0;}
                count++;characters+=length;
            }
        }
        void unit(int unit) { totalUnits=Math.max(totalUnits,unit); }
        void worksheet(int unit,String name,String state) {
            if(!request.worksheetNavigation||request.maxUnits!=1||unit>MAX_WORKSHEET_CHOICES||
                    (state!=null&&!"visible".equals(state))||name==null||name.length()>128)return;
            boolean visible=false;
            for(int i=0;i<name.length();i++){
                char ch=name.charAt(i);
                if(Character.isISOControl(ch)||(ch>=0x2028&&ch<=0x202e)||(ch>=0x2066&&ch<=0x2069))return;
                if(!Character.isWhitespace(ch)&&!Character.isSpaceChar(ch)&&Character.getType(ch)!=Character.FORMAT)visible=true;
            }
            if(visible)worksheets.add(new Worksheet(unit,name));
        }
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
                // The existing sheet parse already sees these pieces. No previous text is kept or reread.
                if(precedingWorksheetRange!=null&&unit==request.startUnit&&index<=request.startChunk)
                    precedingWorksheetRange.observe(index,value.length());
                if(unit<request.startUnit || (unit==request.startUnit && index<request.startChunk))continue;
                if(nextUnit>0)continue;
                if(unit>=request.startUnit+request.maxUnits || rangeFull(chunks.size(),chars,value.length())) {
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
            int previous="xlsx".equals(format)&&precedingWorksheetRange!=null&&!chunks.isEmpty()&&
                    chunks.get(0).unit==request.startUnit&&chunks.get(0).chunk==request.startChunk?precedingWorksheetRange.previous:-1;
            return new Result(format,status,coverage,chunks,warnings,totalUnits,nextUnit,nextChunk,previous,
                    "xlsx".equals(format)&&totalUnits<=MAX_WORKSHEET_CHOICES?worksheets:Collections.emptyList());
        }
    }
    public static Map<String,String> location(String... fields) {
        Map<String,String> map=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2) map.put(fields[i],fields[i+1]);
        return map;
    }
}
