package ai.vigzone.chatgptconnector;

import org.json.*;
import java.util.TreeMap;

/** Streaming text and public citations only; never copies credentials or raw tool results. */
final class ResponseEvents {
    final StringBuilder text=new StringBuilder();
    private final TreeMap<Integer,JSONObject> items=new TreeMap<>();
    private JSONArray output;
    boolean complete, searched;
    void accept(JSONObject event) throws Exception {
        String type=event.optString("type");
        if(type.startsWith("response.web_search_call."))searched=true;
        if("response.output_text.delta".equals(type)){
            text.append(event.optString("delta"));
            if(text.length()>2*1024*1024)throw new java.io.IOException("ChatGPT reply exceeded the supported size.");
        }
        if("response.output_item.done".equals(type)){
            JSONObject item=event.optJSONObject("item");if(item!=null)items.put(event.optInt("output_index",items.size()),item);
        }
        if("response.completed".equals(type)){
            JSONObject response=event.optJSONObject("response");output=response==null?null:response.optJSONArray("output");complete=true;
        }
        if("response.failed".equals(type)||"response.incomplete".equals(type)||"error".equals(type))
            throw new java.io.IOException("ChatGPT could not finish this response. Check account/model web search access and your subscription allowance, then retry.");
    }
    JSONObject result() throws Exception {
        if(!complete)throw new java.io.IOException("ChatGPT's response was interrupted before completion. Please retry.");
        JSONArray records=output!=null?output:new JSONArray(items.values()),citations=new JSONArray();
        StringBuilder finalText=new StringBuilder();
        for(int i=0;i<records.length();i++){
            JSONObject item=records.getJSONObject(i);
            if("web_search_call".equals(item.optString("type")))searched=true;
            if(!"message".equals(item.optString("type")))continue;
            JSONArray parts=item.optJSONArray("content");if(parts==null)continue;
            for(int j=0;j<parts.length();j++){
                JSONObject part=parts.getJSONObject(j);if(!"output_text".equals(part.optString("type")))continue;
                int offset=finalText.codePointCount(0,finalText.length());finalText.append(part.optString("text"));
                JSONArray notes=part.optJSONArray("annotations");if(notes==null)continue;
                for(int k=0;k<notes.length()&&citations.length()<40;k++){
                    JSONObject note=notes.getJSONObject(k);if(!"url_citation".equals(note.optString("type")))continue;
                    String url=note.optString("url");
                    try{java.net.URI target=new java.net.URI(url);if(url.length()>4096||target.getHost()==null||target.getRawUserInfo()!=null||!("https".equalsIgnoreCase(target.getScheme())||"http".equalsIgnoreCase(target.getScheme())))continue;}catch(java.net.URISyntaxException invalid){continue;}
                    JSONObject safe=new JSONObject().put("url",url).put("title",note.optString("title",url));
                    if(note.has("start_index")&&note.has("end_index"))safe.put("start_index",offset+note.getInt("start_index")).put("end_index",offset+note.getInt("end_index"));
                    citations.put(safe);
                }
            }
        }
        String content=finalText.length()>0?finalText.toString():text.toString();
        if(content.trim().isEmpty())throw new java.io.IOException("ChatGPT returned no completed text response.");
        return new JSONObject().put("content",content).put("citations",citations).put("webSearchUsed",searched);
    }
}
