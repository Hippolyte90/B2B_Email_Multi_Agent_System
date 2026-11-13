package fr.paybridge.mas;

import com.google.gson.*;
import fr.paybridge.mas.adapters.outbound.gmail.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Faux fournisseur entièrement en mémoire ; aucune requête Google ni transmission réelle. */
final class GmailTestTransport implements GmailTransport {
    record Call(String method,String path,JsonObject body){}
    final List<Call> calls=new ArrayList<>();
    final Map<String,JsonObject> messages=new LinkedHashMap<>();
    final Map<String,JsonObject> drafts=new LinkedHashMap<>();
    final Map<String,JsonObject> pages=new HashMap<>();
    int failureStatus=-1;
    String failPath="";
    boolean malformedSend;
    public JsonObject request(String method,String path,JsonObject body){
        calls.add(new Call(method,path,body==null?null:body.deepCopy()));
        if(failureStatus>=0&&path.startsWith(failPath)){throw new GmailFailure(failureStatus);}
        if(pages.containsKey(path)){return pages.get(path).deepCopy();}
        if(path.equals("/profile")){return object("{\"emailAddress\":\"operator@example.test\"}");}
        if(path.startsWith("/messages?")||path.startsWith("/drafts?")){
            var list=new JsonObject();var array=new JsonArray();var source=path.startsWith("/messages?")?messages:drafts;
            source.keySet().forEach(id->{var item=new JsonObject();item.addProperty("id",id);array.add(item);});list.add(path.startsWith("/messages?")?"messages":"drafts",array);return list;
        }
        if(method.equals("GET")&&path.startsWith("/messages/")){return messages.get(path.substring(10).split("\\?")[0]).deepCopy();}
        if(method.equals("GET")&&path.startsWith("/drafts/")){return drafts.get(path.substring(8).split("\\?")[0]).deepCopy();}
        if(path.endsWith("/modify")){
            var message=messages.get(path.split("/")[2]);var labels=new LinkedHashSet<String>();message.getAsJsonArray("labelIds").forEach(v->labels.add(v.getAsString()));
            body.getAsJsonArray("removeLabelIds").forEach(v->labels.remove(v.getAsString()));body.getAsJsonArray("addLabelIds").forEach(v->labels.add(v.getAsString()));var updated=new JsonArray();labels.forEach(updated::add);message.add("labelIds",updated);return message.deepCopy();
        }
        if(path.equals("/messages/send")||path.equals("/drafts/send")){return malformedSend?new JsonObject():object("{\"id\":\"sent123\"}");}
        if(path.equals("/drafts")||method.equals("PUT")){return object("{\"id\":\"remote123\"}");}
        throw new AssertionError("Chemin de test inattendu : "+path);
    }
    static JsonObject object(String value){return JsonParser.parseString(value).getAsJsonObject();}
    static JsonObject message(String id,String from,String to,String subject,String text,String...labels){
        var message=new JsonObject();message.addProperty("id",id);message.addProperty("threadId","thread123");message.addProperty("internalDate","1794564000000");var tags=new JsonArray();Arrays.stream(labels).forEach(tags::add);message.add("labelIds",tags);
        var payload=new JsonObject();payload.addProperty("mimeType","text/plain");var body=new JsonObject();body.addProperty("data",Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)));payload.add("body",body);
        var headers=new JsonArray();header(headers,"From",from);header(headers,"To",to);header(headers,"Subject",subject);header(headers,"Message-ID","<"+id+"@example.test>");payload.add("headers",headers);message.add("payload",payload);return message;
    }
    static void header(JsonArray headers,String name,String value){var h=new JsonObject();h.addProperty("name",name);h.addProperty("value",value);headers.add(h);}
    static String mime(JsonObject body){var message=body.has("message")?body.getAsJsonObject("message"):body;return new String(Base64.getUrlDecoder().decode(message.get("raw").getAsString()),StandardCharsets.UTF_8);}
}
