package ai.vigzone.chatgptconnector;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ResponseEventsTest {
 @Test public void completesStreamWithRealCitationsAndSearchStatus() throws Exception {
  ResponseEvents state=new ResponseEvents();
  state.accept(new JSONObject().put("type","response.web_search_call.in_progress"));
  state.accept(new JSONObject().put("type","response.output_text.delta").put("delta","partial"));assertFalse(state.complete);
  JSONObject note=new JSONObject().put("type","url_citation").put("url","https://example.com").put("title","Example").put("start_index",0).put("end_index",6);
  JSONObject text=new JSONObject().put("type","output_text").put("text","Answer").put("annotations",new JSONArray().put(note));
  JSONObject message=new JSONObject().put("type","message").put("content",new JSONArray().put(text));
  state.accept(new JSONObject().put("type","response.completed").put("response",new JSONObject().put("output",new JSONArray().put(message))));
  JSONObject result=state.result();assertEquals("Answer",result.getString("content"));assertTrue(result.getBoolean("webSearchUsed"));assertEquals(1,result.getJSONArray("citations").length());
 }
 @Test(expected=java.io.IOException.class) public void doesNotCommitPartialReplies() throws Exception {
  ResponseEvents state=new ResponseEvents();state.accept(new JSONObject().put("type","response.output_text.delta").put("delta","unfinished"));state.result();
 }
 @Test(expected=java.io.IOException.class) public void failedSearchOrResponseIsNotSuccess() throws Exception {
  new ResponseEvents().accept(new JSONObject().put("type","response.failed"));
 }
}
