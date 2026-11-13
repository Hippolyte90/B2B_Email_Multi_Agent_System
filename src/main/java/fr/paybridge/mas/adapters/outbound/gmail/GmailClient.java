package fr.paybridge.mas.adapters.outbound.gmail;

import com.google.gson.*;
import fr.paybridge.mas.domain.mail.EmailAddress;
import fr.paybridge.mas.domain.draft.DraftContent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Adaptateur REST/MIME texte. Pas d'exécution HTML ni de téléchargement de pièces jointes. */
public final class GmailClient {
    public record Message(String id, String threadId, Set<String> labels, String subject, String body, EmailAddress sender,
                          List<EmailAddress> recipients, Instant at, String rfcId, String localDraftId, String remoteDraftId) {
        public Message { labels=Set.copyOf(labels);recipients=List.copyOf(recipients); }
    }
    private final GmailTransport transport;
    public GmailClient(GmailTransport transport) { this.transport=Objects.requireNonNull(transport); }
    public EmailAddress profile() { return new EmailAddress(transport.request("GET","/profile",null).get("emailAddress").getAsString()); }
    public List<Message> messages(int maximum) {
        if(maximum<1||maximum>1000){throw new IllegalArgumentException("Limite Gmail entre 1 et 1000 requise");}
        var ids=new LinkedHashSet<String>();var pages=new HashSet<String>();String page="";
        do {
            if(!pages.add(page)){throw new GmailFailure(0);}
            var list=transport.request("GET","/messages?includeSpamTrash=true&maxResults="+Math.min(100,maximum-ids.size())+(page.isEmpty()?"":"&pageToken="+encode(page)),null);
            if(list.has("messages")){for(var item:list.getAsJsonArray("messages")){ids.add(safe(item.getAsJsonObject().get("id").getAsString()));if(ids.size()>=maximum){break;}}}
            page=list.has("nextPageToken")?list.get("nextPageToken").getAsString():"";
        }while(!page.isEmpty()&&ids.size()<maximum);
        return ids.stream().map(this::message).toList();
    }
    public Message message(String id) { return parse(transport.request("GET","/messages/"+safe(id)+"?format=full",null),""); }
    public List<Message> drafts() {
        var result=new ArrayList<Message>();var pages=new HashSet<String>();String page="";
        do {
            if(!pages.add(page)||result.size()>=1000){throw new GmailFailure(0);}
            var list=transport.request("GET","/drafts?maxResults=100"+(page.isEmpty()?"":"&pageToken="+encode(page)),null);
            if(list.has("drafts")){for(var item:list.getAsJsonArray("drafts")){String id=safe(item.getAsJsonObject().get("id").getAsString());var draft=transport.request("GET","/drafts/"+id+"?format=full",null);result.add(parse(draft.getAsJsonObject("message"),id));}}
            page=list.has("nextPageToken")?list.get("nextPageToken").getAsString():"";
        }while(!page.isEmpty());return List.copyOf(result);
    }
    public void flags(String id,boolean inbox,boolean spam,boolean trash,boolean read,boolean starred) {
        var add=new JsonArray();var remove=new JsonArray();
        for(var e:Map.of("INBOX",inbox,"SPAM",spam,"TRASH",trash,"UNREAD",!read,"STARRED",starred).entrySet()){(e.getValue()?add:remove).add(e.getKey());}
        var body=new JsonObject();body.add("addLabelIds",add);body.add("removeLabelIds",remove);transport.request("POST","/messages/"+safe(id)+"/modify",body);
    }
    public String saveDraft(String remoteId,String threadId,EmailAddress sender,DraftContent content,String rfcId,String localDraftId) {
        var body=new JsonObject();body.add("message",envelope(threadId,sender,content,rfcId,localDraftId));
        var result=transport.request(remoteId==null||remoteId.isBlank()?"POST":"PUT",remoteId==null||remoteId.isBlank()?"/drafts":"/drafts/"+safe(remoteId),body);
        return safe(result.get("id").getAsString());
    }
    public String send(String threadId,EmailAddress sender,DraftContent content,String rfcId) {
        var result=transport.request("POST","/messages/send",envelope(threadId,sender,content,rfcId,""));return safe(result.get("id").getAsString());
    }
    public String sendDraft(String remoteId,String threadId,EmailAddress sender,DraftContent content,String rfcId) {
        var body=new JsonObject();body.addProperty("id",safe(remoteId));body.add("message",envelope(threadId,sender,content,rfcId,""));
        return safe(transport.request("POST","/drafts/send",body).get("id").getAsString());
    }
    static JsonObject envelope(String threadId,EmailAddress sender,DraftContent content,String rfcId,String localDraftId) {
        var mime=new StringBuilder("From: ").append(sender.value()).append("\r\nTo: ").append(content.recipients().stream().map(EmailAddress::value).collect(java.util.stream.Collectors.joining(", ")))
                .append("\r\nSubject: ").append(encodedSubject(content.subject())).append("\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n");
        if(rfcId!=null&&!rfcId.isBlank()){
            if(!rfcId.matches("<[^<>\\s]+>")){throw new IllegalArgumentException("Identifiant de fil MIME invalide");}
            mime.append("In-Reply-To: ").append(rfcId).append("\r\nReferences: ").append(rfcId).append("\r\n");
        }
        if(localDraftId!=null&&!localDraftId.isBlank()){mime.append("X-MAS-Draft-Id: ").append(UUID.fromString(localDraftId)).append("\r\n");}
        mime.append("\r\n").append(Base64.getMimeEncoder(76,"\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(content.body().getBytes(StandardCharsets.UTF_8)));
        var body=new JsonObject();body.addProperty("raw",Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toString().getBytes(StandardCharsets.UTF_8)));if(threadId!=null&&!threadId.isBlank()){body.addProperty("threadId",safe(threadId));}return body;
    }
    private static final Pattern ADDRESS=Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    static Message parse(JsonObject object,String draftId) {
        try {
            var labels=new HashSet<String>();if(object.has("labelIds")){object.getAsJsonArray("labelIds").forEach(v->labels.add(v.getAsString()));}
            var payload=object.getAsJsonObject("payload");var headers=new HashMap<String,String>();
            if(payload.has("headers")){for(var h:payload.getAsJsonArray("headers")){var header=h.getAsJsonObject();headers.putIfAbsent(header.get("name").getAsString().toLowerCase(Locale.ROOT),header.get("value").getAsString());}}
            var sender=addresses(headers.getOrDefault("from","")).stream().findFirst().orElseThrow();
            var recipients=addresses(headers.getOrDefault("to",""));
            String text=part(payload,"text/plain");if(text.isBlank()){text=part(payload,"text/html").replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>","").replaceAll("(?s)<[^>]*>"," ").replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");}
            if(text.isBlank()){text="(Message sans texte lisible ; pièces jointes non chargées.)";}
            var subject=decodedSubject(headers.getOrDefault("subject",""));if(subject.isBlank()){subject="(Sans objet)";}
            return new Message(safe(object.get("id").getAsString()),safe(object.get("threadId").getAsString()),labels,subject,text,sender,recipients,Instant.ofEpochMilli(object.get("internalDate").getAsLong()),headers.getOrDefault("message-id",""),headers.getOrDefault("x-mas-draft-id",""),draftId);
        }catch(GmailFailure known){throw known;}catch(Exception failure){throw new GmailFailure(0);}
    }
    private static List<EmailAddress> addresses(String header) {var found=new LinkedHashSet<EmailAddress>();var matcher=ADDRESS.matcher(header);while(matcher.find()){found.add(new EmailAddress(matcher.group()));}return List.copyOf(found);}
    private static String part(JsonObject part,String type) {
        if(part.has("filename")&&!part.get("filename").getAsString().isBlank()){return "";}
        if(part.has("mimeType")&&type.equals(part.get("mimeType").getAsString())&&part.has("body")&&part.getAsJsonObject("body").has("data")){
            java.nio.charset.Charset charset=StandardCharsets.UTF_8;
            if(part.has("headers")){for(var h:part.getAsJsonArray("headers")){var header=h.getAsJsonObject();if(header.get("name").getAsString().equalsIgnoreCase("Content-Type")){
                var match=Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9_-]+)").matcher(header.get("value").getAsString());if(match.find()){charset=java.nio.charset.Charset.forName(match.group(1));}
            }}}
            return new String(Base64.getUrlDecoder().decode(part.getAsJsonObject("body").get("data").getAsString()),charset);
        }
        if(part.has("parts")){return part.getAsJsonArray("parts").asList().stream().map(p->part(p.getAsJsonObject(),type)).filter(t->!t.isBlank()).collect(java.util.stream.Collectors.joining("\n"));}return "";
    }
    static String safe(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,200}")){throw new GmailFailure(0);}return id;}
    private static String encodedSubject(String subject){
        var chunks=new ArrayList<String>();var chunk=new StringBuilder();
        for(int cp:subject.codePoints().toArray()){
            var next=new String(Character.toChars(cp));
            if((chunk.toString()+next).getBytes(StandardCharsets.UTF_8).length>30){chunks.add(chunk.toString());chunk.setLength(0);}
            chunk.append(next);
        }
        if(!chunk.isEmpty()){chunks.add(chunk.toString());}
        return chunks.stream().map(s->"=?UTF-8?B?"+Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8))+"?=").collect(java.util.stream.Collectors.joining("\r\n "));
    }
    static String decodedSubject(String subject){
        var matcher=Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=").matcher(subject);var result=new StringBuilder();int previous=0;boolean encoded=false;
        while(matcher.find()){
            var gap=subject.substring(previous,matcher.start());if(!encoded||!gap.isBlank()){result.append(gap);}
            var charset=java.nio.charset.Charset.forName(matcher.group(1));byte[] bytes;
            if(matcher.group(2).equalsIgnoreCase("B")){bytes=Base64.getDecoder().decode(matcher.group(3));}
            else{var out=new java.io.ByteArrayOutputStream();var value=matcher.group(3).replace('_',' ');for(int i=0;i<value.length();i++){if(value.charAt(i)=='='&&i+2<value.length()){out.write(Integer.parseInt(value.substring(i+1,i+3),16));i+=2;}else{out.write(value.charAt(i));}}bytes=out.toByteArray();}
            result.append(new String(bytes,charset));previous=matcher.end();encoded=true;
        }return result.append(subject.substring(previous)).toString();
    }
    private static String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8);}
}
