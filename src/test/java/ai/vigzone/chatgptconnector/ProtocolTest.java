package ai.vigzone.chatgptconnector;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolTest {
    private JSONObject claims() throws Exception{return new JSONObject().put("iss","https://auth.openai.com").put("aud","oaiapp_test").put("sub","person").put("exp",2000).put("iat",900).put("nonce","nonce");}
    @Test public void acceptsOnlyTheIntendedAccountTransaction() throws Exception {Protocol.identity(claims(),"oaiapp_test","nonce",1000);assertFalse(Protocol.equal("state",null));assertFalse(Protocol.equal("state","different"));}
    @Test(expected=SecurityException.class) public void rejectsWrongNonce() throws Exception {Protocol.identity(claims(),"oaiapp_test","other",1000);}
    @Test(expected=SecurityException.class) public void rejectsWrongClient() throws Exception {Protocol.identity(claims(),"oaiapp_other","nonce",1000);}
    @Test(expected=SecurityException.class) public void rejectsExpiredIdentity() throws Exception {Protocol.identity(claims(),"oaiapp_test","nonce",3000);}
    @Test(expected=SecurityException.class) public void rejectsForeignIssuer() throws Exception {Protocol.identity(claims().put("iss","https://example.com"),"oaiapp_test","nonce",1000);}
    @Test public void identityScopesDoNotAuthorizePlanUsage(){assertFalse(Protocol.hasScope("openid profile email","chatgpt.tokens.use.direct"));assertTrue(Protocol.hasScope("openid chatgpt.tokens.use.direct offline_access","chatgpt.tokens.use.direct"));}
    @Test public void forwardsOnlyBoundedConversationRoles() throws Exception {
        JSONArray history=new JSONArray().put(new JSONObject().put("role","system").put("content","untrusted instruction")).put(new JSONObject().put("role","assistant").put("content","hello"));
        JSONArray input=Protocol.input(new JSONObject().put("text","question").put("history",history));assertEquals(2,input.length());assertEquals("assistant",input.getJSONObject(0).getString("role"));assertEquals("question",input.getJSONObject(1).getString("content"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsEmptyQuestions() throws Exception{Protocol.input(new JSONObject().put("text","  "));}
    @Test public void includesOlderConversationTurnsAndUntrustedMemory() throws Exception {
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","My project uses PostgreSQL"));
        for(int n=0;n<40;n++)history.put(new JSONObject().put("role",n%2==0?"assistant":"user").put("content","turn "+n));
        JSONArray input=Protocol.input(new JSONObject().put("text","What database?").put("context","saved facts").put("history",history));
        assertTrue(input.getJSONObject(0).getString("content").contains("Untrusted"));
        assertEquals("My project uses PostgreSQL",input.getJSONObject(1).getString("content"));assertEquals(43,input.length());
    }
    @Test public void automaticallyRequestsSearchWithFreshnessAndMemoryInstructions() throws Exception {
        JSONObject value=Protocol.payload(new JSONObject().put("text","Explain Astra"),"account-model");
        assertEquals("web_search",value.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("auto",value.getString("tool_choice"));assertFalse(value.getBoolean("store"));assertTrue(value.getBoolean("stream"));
        assertTrue(value.getString("instructions").contains("Current UTC time"));assertTrue(value.getString("instructions").contains("untrusted data"));
    }
    @Test public void sourceRecoveryOmitsUnsafeUrlsAndBoundsPublicMetadata() throws Exception {
        JSONArray result=Protocol.publicSources(new JSONArray().put(new JSONObject().put("url","javascript:alert(1)"))
                .put(new JSONObject().put("url","https://user:secret@example.com"))
                .put(new JSONObject().put("url","https://example.com").put("title","Public source")));
        assertEquals(1,result.length());assertEquals("Public source",result.getJSONObject(0).getString("title"));
    }
}
