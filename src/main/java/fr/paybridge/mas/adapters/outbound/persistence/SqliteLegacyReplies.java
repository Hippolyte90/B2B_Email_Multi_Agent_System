package fr.paybridge.mas.adapters.outbound.persistence;

import fr.paybridge.mas.domain.draft.*;
import fr.paybridge.mas.domain.delivery.TransportMode;
import fr.paybridge.mas.domain.generation.StoredGeneration;
import java.util.*;

/** Migration des anciennes réponses fixes ; les saisies humaines et les transports réels sont préservés. */
final class SqliteLegacyReplies {
    private final SqliteStore store;
    SqliteLegacyReplies(SqliteStore store){this.store=store;}
    private static boolean predefined(DraftVersion version){
        return version.origin()==DraftVersion.Origin.GENERATED&&version.generation().simulated()
                &&version.content().body().equals(version.generation().text())&&legacyText(version.content().body());
    }
    private static boolean legacyText(String text){return text.startsWith("[SIMULATION : texte fixe, aucun modèle appelé]");}
    private void archive(String kind,String id,String payload){store.write("INSERT INTO retired_demo_content(kind,id,payload) VALUES(?,?,?)",kind,id,payload);}
    void remove(){
        for(var id:store.payloads("SELECT id FROM drafts ORDER BY id")){
            var payload=store.payload("SELECT payload FROM drafts WHERE id=?",id).orElseThrow();var draft=store.decodeDraft(payload);
            if(!draft.versions().stream().allMatch(SqliteLegacyReplies::predefined)||draft.sendAttempts().stream().anyMatch(a->a.mode()==TransportMode.REAL)){continue;}
            archive("DRAFT",id,payload);
            for(var attempt:store.payloads("SELECT attempt_id FROM simulated_outbox WHERE draft_id=?",id)){
                archive("SIMULATED_OUTBOX",attempt,store.payload("SELECT payload FROM simulated_outbox WHERE attempt_id=?",attempt).orElseThrow());
                store.write("DELETE FROM simulated_outbox WHERE attempt_id=?",attempt);
            }
            store.write("DELETE FROM drafts WHERE id=?",id);store.audit("DEFAULT_REPLY_RETIRED",id,draft.revision());
        }
        var referenced=new HashSet<UUID>();
        for(var payload:store.payloads("SELECT payload FROM drafts")){store.decodeDraft(payload).versions().forEach(v->referenced.add(v.generation().taskId()));}
        for(var id:store.payloads("SELECT task_id FROM completed_generations ORDER BY sequence")){
            if(referenced.contains(UUID.fromString(id))){continue;}
            var payload=store.payload("SELECT payload FROM completed_generations WHERE task_id=?",id).orElseThrow();var generation=store.decode(payload,StoredGeneration.class);
            if(generation.result().reply().filter(r->r.simulated()&&legacyText(r.text())).isEmpty()){continue;}
            archive("GENERATION",id,payload);store.write("DELETE FROM completed_generations WHERE task_id=?",id);
        }
    }
}
