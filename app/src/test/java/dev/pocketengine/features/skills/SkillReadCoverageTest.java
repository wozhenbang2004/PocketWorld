package dev.pocketengine.features.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SkillReadCoverageTest {
    private static final String RUN="10000000-0000-0000-0000-000000000001";
    private static JSONObject target()throws Exception{return new JSONObject().put("id","20000000-0000-0000-0000-000000000001").put("revision",3).put("digest","a".repeat(64)).put("executionGeneration",4L).put("skillScopeFingerprint","b".repeat(64));}
    private static JSONObject page(String path,int start,int end)throws Exception{return new JSONObject().put("runId",RUN).put("epoch",2).put("package",target()).put("path",path).put("start",start).put("end",end);}
    private static JSONObject file(String path,int chars)throws Exception{return new JSONObject().put("path",path).put("chars",chars);}
    private static void require(JSONArray reads,JSONArray files)throws Exception{SkillReadCoverage.requireComplete(reads,RUN,2,target(),files);}

    @Test public void adjacentAndOverlappingPagesCoverEveryActualFile()throws Exception {
        JSONArray pages=new JSONArray().put(page("SKILL.md",8,12)).put(page("SKILL.md",0,5)).put(page("SKILL.md",4,9)).put(page("refs/a.txt",0,2));
        require(pages,new JSONArray().put(file("SKILL.md",12)).put(file("refs/a.txt",2)));
    }
    @Test public void firstPageAndAOneCharacterGapCannotAuthorizeAnUpdate()throws Exception {
        JSONArray files=new JSONArray().put(file("SKILL.md",12));
        assertThrows(SecurityException.class,()->require(new JSONArray().put(page("SKILL.md",0,5)),files));
        assertThrows(SecurityException.class,()->require(new JSONArray().put(page("SKILL.md",0,5)).put(page("SKILL.md",6,12)),files));
    }
    @Test public void otherRunsEpochsAndInstallGenerationsDoNotFillTheGap()throws Exception {
        JSONArray files=new JSONArray().put(file("SKILL.md",12));
        for(String changed:new String[]{"run","epoch","generation","revision","digest","scope"}){
            JSONObject second=page("SKILL.md",5,12);
            switch(changed){
                case "run":second.put("runId","30000000-0000-0000-0000-000000000001");break;
                case "epoch":second.put("epoch",3);break;
                case "generation":second.getJSONObject("package").put("executionGeneration",5);break;
                case "revision":second.getJSONObject("package").put("revision",4);break;
                case "digest":second.getJSONObject("package").put("digest","c".repeat(64));break;
                case "scope":second.getJSONObject("package").put("skillScopeFingerprint","c".repeat(64));break;
            }
            assertThrows(changed,SecurityException.class,()->require(new JSONArray().put(page("SKILL.md",0,5)).put(second),files));
        }
    }
    @Test public void emptyFileNeedsItsActualZeroLengthReadReceipt()throws Exception {
        JSONArray files=new JSONArray().put(file("SKILL.md",2)).put(file("refs/empty.txt",0));
        JSONArray pages=new JSONArray().put(page("SKILL.md",0,2));
        assertThrows(SecurityException.class,()->require(pages,files));
        pages.put(page("refs/empty.txt",0,0));require(pages,files);
    }
    @Test public void eofAndDuplicateReceiptsDoNotSubstituteForMissingContent()throws Exception {
        JSONArray pages=new JSONArray().put(page("SKILL.md",0,5)).put(page("SKILL.md",0,5)).put(page("SKILL.md",12,12));
        assertThrows(SecurityException.class,()->require(pages,new JSONArray().put(file("SKILL.md",12))));
    }
    @Test public void numericStringsAndOutOfRangeNativePagesFailClosed()throws Exception {
        JSONObject coerced=page("SKILL.md",0,12);coerced.put("end","12");
        assertThrows(SecurityException.class,()->require(new JSONArray().put(coerced),new JSONArray().put(file("SKILL.md",12))));
        assertThrows(SecurityException.class,()->require(new JSONArray().put(page("SKILL.md",0,13)),new JSONArray().put(file("SKILL.md",12))));
    }
    @Test public void duplicateDescriptorsAndMissingResourcesFailClosed()throws Exception {
        JSONArray pages=new JSONArray().put(page("SKILL.md",0,12));
        assertThrows(SecurityException.class,()->require(pages,new JSONArray().put(file("SKILL.md",12)).put(file("SKILL.md",12))));
        assertThrows(SecurityException.class,()->require(pages,new JSONArray().put(file("SKILL.md",12)).put(file("refs/missing.txt",3))));
    }
}
