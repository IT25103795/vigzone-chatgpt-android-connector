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
}
