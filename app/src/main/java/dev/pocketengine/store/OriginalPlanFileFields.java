package dev.pocketengine.store;

import android.util.JsonToken;
import dev.pocketengine.features.automation.PlanArtifactVerifier;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free projections of one Book-selected original file and one current native Work. */
public final class OriginalPlanFileFields {
    private static final Pattern UUID=Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Set<String> PLAN_HEADER=Set.of("id","ownerId","spaceId");
    private static final Set<String> STEP_HEADER=Set.of("id","ownerId","spaceId","planId","planIndex","taskId");
    private static final Set<String> STEP_FACT=Set.of("revision","status","executor",
            "completionEvidence","completionEvent","taskAdmission","taskObservation","verification");
    private static final Set<String> EVIDENCE=Set.of("ownerId","spaceId","id","kind","outcome","verificationSource","commitmentId","commitmentRevision","taskId","recordedAt");
    private static final Set<String> ARTIFACT=Set.of("artifactId","sha256","sizeBytes","projectId","path","sourceVersion","sourceRevisionId",
            "operationId","runId","coordinatorEpoch","verificationScope");
    private static final Set<String> EVENT=Set.of("id","taskId","evidenceId");
    private static final Set<String> ADMISSION=Set.of("state","executionMode","ruleId","ruleRevision","commitmentRevision","occurrenceId");
    private static final Set<String> OBSERVATION=Set.of("authority","status","resultVerified","taskId","occurrenceId","runId","coordinatorEpoch");
    private static final Set<String> VERIFICATION=Set.of("kind","projectId","path","requiredHeading","minBytes");

    private OriginalPlanFileFields(){}
    private record PlanRow(JSONObject identity,List<String> steps,boolean hasExecution){}

    /** The Book calls under its original lock; no plan view or task resolver. */
    public static JSONObject selectedBookStep(File file,int maxBytes,PlanArtifactVerifier.OriginalFile held,BooleanSupplier current)throws Exception{
        if(held==null||!current.getAsBoolean()||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)
                ||Files.size(file.toPath())>maxBytes)return null;
        try(InputStream source=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS);
            MigrationJsonInput in=new MigrationJsonInput(new InputStreamReader(new BookBytes(source,maxBytes,current),StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)),256)){
            JSONObject selected=null;PlanRow plan=null;long version=0;int sameWork=0;
            Set<String> seen=new HashSet<>(),sameWorkRows=new HashSet<>();
            in.beginObject();
            while(in.hasNext()){
                String key=in.boundedName(256);
                remember(seen,key);
                switch(key){
                    case "version": {
                        Object value=scalar(in);if(!(value instanceof Long))return null;version=(Long)value;break;
                    }
                    case "plans": {
                        int count=0;in.beginArray();
                        while(in.hasNext()){
                            if(++count>200)return null;
                            PlanRow row=planRow(in,held.planId());
                            if(held.planId().equals(row.identity.opt("id"))){if(plan!=null)return null;plan=row;}
                        }
                        in.endArray();break;
                    }
                    case "commitments": {
                        int count=0;in.beginArray();
                        while(in.hasNext()){
                            if(++count>1000)return null;
                            JSONObject row=stepRow(in,held.stepId());
                            if(held.taskId().equals(row.opt("taskId"))&&privateRow(row)){
                                Object id=row.opt("id");if(!(id instanceof String reference)||!sameWorkRows.add(reference))return null;
                                if(held.planId().equals(row.opt("planId")))sameWork++;
                            }
                            if(held.stepId().equals(row.opt("id"))){if(selected!=null)return null;selected=row;}
                        }
                        in.endArray();break;
                    }
                    default: in.skipUnselectedValue();
                }
            }
            in.endObject();
            if(in.peek()!=JsonToken.END_DOCUMENT||(version!=1&&version!=2)||plan==null||!privateRow(plan.identity)
                    ||!plan.hasExecution||selected==null||sameWork!=1)return null;
            int position=plan.steps.indexOf(held.stepId());
            return position>=0&&Long.valueOf(position).equals(selected.opt("planIndex"))
                    &&plan.steps.stream().filter(sameWorkRows::contains).count()==1&&current.getAsBoolean()?selected:null;
        }
    }

    private static PlanRow planRow(MigrationJsonInput in,String selected)throws Exception{
        JSONObject identity=new JSONObject();List<String> steps=new ArrayList<>();Set<String> seen=new HashSet<>();boolean execution=false;
        in.beginObject();
        while(in.hasNext()){
            String key=in.boundedName(256);remember(seen,key);
            if(PLAN_HEADER.contains(key))identity.put(key,scalar(in));
            else if("stepIds".equals(key)&&selected.equals(identity.opt("id"))&&privateRow(identity)){
                in.beginArray();
                while(in.hasNext()){
                    Object id=scalar(in);
                    if(!(id instanceof String step)||!UUID.matcher(step).matches()||steps.size()>=50||steps.contains(step))
                        throw invalid();
                    steps.add(step);
                }
                in.endArray();
            }else{
                if("execution".equals(key)&&selected.equals(identity.opt("id"))&&privateRow(identity))execution=in.peek()==JsonToken.BEGIN_OBJECT;
                in.skipUnselectedValue();
            }
        }
        in.endObject();return new PlanRow(identity,steps,execution);
    }

    private static JSONObject stepRow(MigrationJsonInput in,String selected)throws Exception{
        JSONObject row=new JSONObject();Set<String> seen=new HashSet<>();
        in.beginObject();
        while(in.hasNext()){
            String key=in.boundedName(256);remember(seen,key);
            if(STEP_HEADER.contains(key)){row.put(key,scalar(in));continue;}
            if("remoteWait".equals(key)||"businessWait".equals(key)){in.skipUnselectedValue();row.put(key,true);continue;}
            // Native createPlan writes the UUID before its result fields; if a row
            // cannot be recognized before its witness, it remains unconfirmed.
            if(!selected.equals(row.opt("id"))||!privateRow(row)||!STEP_FACT.contains(key)){in.skipUnselectedValue();continue;}
            Object value=switch(key){
                case "completionEvidence" -> fields(in,EVIDENCE,true);
                case "completionEvent" -> fields(in,EVENT,false);
                case "taskAdmission" -> fields(in,ADMISSION,false);
                case "taskObservation" -> fields(in,OBSERVATION,false);
                case "verification" -> fields(in,VERIFICATION,false);
                default -> scalar(in);
            };
            row.put(key,value);
        }
        in.endObject();return row;
    }

    private static Object fields(MigrationJsonInput in,Set<String> keys,boolean expectedArtifact)throws Exception{
        return fields(in,keys,expectedArtifact,Set.of(),false);
    }
    private static Object fields(MigrationJsonInput in,Set<String> keys,boolean expectedArtifact,Set<String> forbidden,boolean verification)throws Exception{
        if(in.peek()!=JsonToken.BEGIN_OBJECT){in.skipUnselectedValue();return JSONObject.NULL;}
        JSONObject out=new JSONObject();Set<String> seen=new HashSet<>();in.beginObject();
        while(in.hasNext()){
            String key=in.boundedName(256);remember(seen,key);
            if(keys.contains(key))out.put(key,scalar(in));
            else if(expectedArtifact&&"artifact".equals(key))out.put(key,fields(in,ARTIFACT,false));
            else if(verification&&"verification".equals(key))out.put(key,fields(in,VERIFICATION,false));
            else{
                // Dropping an unknown standard field would make a noncanonical
                // stored scope compare as the earlier reviewed digest.
                if(keys==VERIFICATION)throw invalid();
                in.skipUnselectedValue();
                if(forbidden.contains(key))out.put(key,true);
            }
        }
        in.endObject();return out;
    }

    private static boolean privateRow(JSONObject row){return "owner".equals(row.opt("ownerId"))&&"private".equals(row.opt("spaceId"));}
    private static IOException invalid(){return new IOException("原目标核验文件的记录形状或范围不可确认");}

    private static final class BookBytes extends FilterInputStream {
        private final BooleanSupplier current;private int left;
        BookBytes(InputStream source,int maxBytes,BooleanSupplier current){super(source);this.left=maxBytes;this.current=current;}
        @Override public int read()throws IOException{
            if(!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid();
            int value=super.read();
            if(value>=0&&left--<=0)throw invalid();
            return value;
        }
        @Override public int read(byte[] out,int offset,int count)throws IOException{
            if(count==0)return 0;
            if(left==0)return read(); // EOF is allowed at the exact bound; any extra byte fails.
            if(!current.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid();
            int got=super.read(out,offset,Math.min(count,left));if(got>0)left-=got;return got;
        }
    }
    private static final Set<String> TASK=Set.of("id","actorId","spaceId","kind","executionMode","status","runId",
            "coordinatorEpoch","cancelRequested","conversationId","automationRuleId","automationRevision","contextAvailable",
            "contextWithdrawn","sourceCurrentReadRequired","parentTaskId");
    private static final Set<String> ORIGIN=Set.of("kind","planId","ruleId","commitmentId","occurrenceId","ruleRevision");
    private static final Set<String> SCOPE=Set.of("planId","commitmentId","allowPrivateArtifactFreeze");
    private static final Set<String> BINDING=Set.of("commitmentId","commitmentRevision");

    /** The caller bounds this one SQL core, and already holds its read transaction. */
    static JSONObject taskIdentity(Reader source)throws Exception{
        try(MigrationJsonInput in=new MigrationJsonInput(source,256)){
            JSONObject out=new JSONObject();Set<String> seen=new HashSet<>();in.beginObject();
            while(in.hasNext()){
                String key=in.boundedName(256);remember(seen,key);
                if(TASK.contains(key))out.put(key,scalar(in));
                else switch(key){
                    case "origin" -> out.put(key,fields(in,ORIGIN,false,Set.of("nativeOrigin"),false));
                    case "automationScope" -> out.put(key,fields(in,SCOPE,false,Set.of("remoteWait","businessWait"),true));
                    case "commitmentBindings","automationResultRefs" -> {
                        JSONArray values=new JSONArray();in.beginArray();
                        while(in.hasNext()){
                            if(values.length()>=50)throw invalid();
                            values.put("commitmentBindings".equals(key)?fields(in,BINDING,false):scalar(in));
                        }
                        in.endArray();out.put(key,values);
                    }
                    case "relatedTasks" -> {
                        in.beginArray();if(in.hasNext())return null;in.endArray();
                    }
                    default -> in.skipUnselectedValue();
                }
            }
            in.endObject();return in.peek()==JsonToken.END_DOCUMENT?out:null;
        }
    }

    private static void remember(Set<String> seen,String key)throws IOException{
        if(seen.size()>=128||!seen.add(key))throw invalid();
    }
    private static Object scalar(MigrationJsonInput in)throws IOException{
        return switch(in.peek()){
            case STRING -> {String value=in.boundedString(256);if(value==null)throw invalid();yield value;}
            case NUMBER -> Long.parseLong(in.nextNumber());
            case BOOLEAN -> in.nextBoolean();
            case NULL -> {in.skipUnselectedValue();yield JSONObject.NULL;}
            default -> throw invalid();
        };
    }
}
