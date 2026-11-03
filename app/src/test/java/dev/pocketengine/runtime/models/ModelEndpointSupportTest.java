package dev.pocketengine.runtime.models;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.junit.Test;

public final class ModelEndpointSupportTest {
    private static ModelEndpointSupport parse(Object raw){return ModelEndpointSupport.parse(raw,"https://provider.invalid/v1/models#page=1/data/0/supported_endpoints",1000);}
    @Test public void recognizedDeclarationFiltersOnlyTheNamedProtocols()throws Exception{var support=parse(new JSONArray().put("/chat/completions").put("/responses"));assertNotNull(support);assertTrue(support.supports(ModelBinding.Protocol.OPENAI_CHAT));assertTrue(support.supports(ModelBinding.Protocol.OPENAI_RESPONSES));assertFalse(support.supports(ModelBinding.Protocol.ANTHROPIC));assertEquals(support,ModelEndpointSupport.fromJson(support.toJson()));}
    @Test public void absentMalformedUnknownOrUrlValuesRemainUnknown(){assertNull(parse(null));assertNull(parse("/messages"));assertNull(parse(new JSONArray().put("https://attacker.invalid/messages")));assertNull(parse(new JSONArray().put("/messages").put("/unknown")));assertNull(parse(new JSONArray().put("/messages").put("/messages")));assertNull(parse(new JSONArray().put("/Messages")));}
    @Test public void explicitlyEmptyListDoesNotPromiseAnyEndpoint(){var support=parse(new JSONArray());assertNotNull(support);for(var protocol:ModelBinding.Protocol.values())assertFalse(support.supports(protocol));}
    @Test public void cacheCannotInventAnOwnerOverrideSource(){var support=parse(new JSONArray().put("/messages"));assertThrows(IllegalArgumentException.class,()->ModelEndpointSupport.fromJson(support.toJson().put("source","OWNER_OVERRIDE")));}
}
