package dev.pocketengine.features.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure typed metadata only. These examples are not availability, model or UI proofs. */
public final class OwnerSourceReferencesTest {
    private static JSONObject ref(int offset,int chars)throws Exception{return new JSONObject().put("generation","native-generation").put("sourceConversationId","native-conversation")
        .put("sourceRef",new JSONObject().put("kind","message").put("sourceId","native-message").put("revision",9_000_000_000L).put("ownerId","owner").put("spaceId","private"))
        .put("bodyFingerprint","sha256:"+"a".repeat(64)).put("offset",offset).put("chars",chars).put("unit","UTF-16");}
    @Test public void integerRevisionIsNotAHashOrCoercedNumber()throws Exception{for(Object invalid:new Object[]{"9000000000",9_000_000_000.0,"sha256:"+"a".repeat(64),0L,-1L}){JSONObject r=ref(0,3);r.getJSONObject("sourceRef").put("revision",invalid);assertThrows(IllegalArgumentException.class,()->OwnerSourceReferences.checked(new JSONArray().put(r)));}assertEquals(9_000_000_000L,OwnerSourceReferences.checked(new JSONArray().put(ref(0,3))).getJSONObject(0).getJSONObject("sourceRef").getLong("revision"));}
    @Test public void fixedSchemaRejectsBodiesForeignScopesDuplicateAndUnknownFields()throws Exception{
        for(String key:new String[]{"body","grant","ownerLocator"}){JSONObject r=ref(0,3).put(key,"not native metadata");assertThrows(SecurityException.class,()->OwnerSourceReferences.checked(new JSONArray().put(r)));}
        JSONObject foreign=ref(0,3);foreign.getJSONObject("sourceRef").put("spaceId","group:other");assertThrows(SecurityException.class,()->OwnerSourceReferences.checked(new JSONArray().put(foreign)));
        assertThrows(IllegalArgumentException.class,()->OwnerSourceReferences.checked(new JSONArray().put(ref(0,3)).put(ref(0,3))));
    }
    @Test public void countRangeOverflowAndAggregateBudgetsAreStrict()throws Exception{
        assertThrows(IllegalArgumentException.class,()->OwnerSourceReferences.checked(new JSONArray().put(ref(0,4001))));
        assertThrows(IllegalArgumentException.class,()->OwnerSourceReferences.checked(new JSONArray().put(ref(Integer.MAX_VALUE,1))));
        assertThrows(IllegalArgumentException.class,()->OwnerSourceReferences.checked(new JSONArray().put(ref(0,4000)).put(ref(4000,4000)).put(ref(8000,1))));
        JSONArray five=new JSONArray();for(int i=0;i<5;i++)five.put(ref(i,1));assertThrows(IllegalArgumentException.class,()->OwnerSourceReferences.checked(five));
        assertEquals(0,OwnerSourceReferences.checked(new JSONArray()).length());
    }
    @Test public void returnedMetadataHasNoSharedMutableState()throws Exception{JSONArray original=new JSONArray().put(ref(3,12)),checked=OwnerSourceReferences.checked(original);original.getJSONObject(0).getJSONObject("sourceRef").put("revision",1);checked.getJSONObject(0).put("offset",900);assertEquals(3,OwnerSourceReferences.checked(new JSONArray().put(ref(3,12))).getJSONObject(0).getInt("offset"));assertEquals(9_000_000_000L,checked.getJSONObject(0).getJSONObject("sourceRef").getLong("revision"));assertFalse(checked.toString().contains("body\""));}
}
