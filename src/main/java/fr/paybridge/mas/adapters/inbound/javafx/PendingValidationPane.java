package fr.paybridge.mas.adapters.inbound.javafx;

import fr.paybridge.mas.application.workspace.MailboxWorkspace.Entry;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.geometry.Insets;
import java.util.*;
import java.util.function.*;

/** Liste de travail ; le même éditeur et les mêmes gardes servent à la validation humaine. */
final class PendingValidationPane extends BorderPane {
    private final ListView<Entry> list=new ListView<>();
    private final Label count=new Label("Aucune réponse en attente");
    private final BooleanSupplier blocked;
    private boolean rendering;
    PendingValidationPane(BooleanSupplier blocked,Consumer<String> select){
        list.getStyleClass().add("pending-list");
        this.blocked=blocked;list.setAccessibleText("Réponses en attente de validation");list.setPrefWidth(250);list.setMinWidth(170);
        list.setCellFactory(view->new ListCell<>(){
            protected void updateItem(Entry entry,boolean empty){super.updateItem(entry,empty);if(empty||entry==null){setText(null);setGraphic(null);}else{
                var title=new Label(entry.subject());title.setWrapText(true);title.setMaxWidth(240);var to=new Label("À : "+entry.recipients().stream().map(a->a.value()).collect(java.util.stream.Collectors.joining(", ")));to.getStyleClass().add("muted");to.setWrapText(true);setGraphic(new VBox(5,title,to));setText(null);
            }}
        });
        list.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(!rendering&&b!=null){if(blocked.getAsBoolean()){rendering=true;list.getSelectionModel().select(a);rendering=false;return;}select.accept(b.messageId());}});
        var sidebar=new VBox(10,count,list);sidebar.setPadding(new Insets(12));sidebar.setMinWidth(180);sidebar.setPrefWidth(250);VBox.setVgrow(list,Priority.ALWAYS);setLeft(sidebar);
        placeholder();
    }
    void placeholder(){setCenter(new Label("Sélectionnez une réponse pour la lire, la modifier et la valider."));}
    int update(List<Entry> entries){
        var pending=entries.stream().filter(Entry::reviewRequired).toList();var selected=list.getSelectionModel().getSelectedItem();
        rendering=true;try{list.getItems().setAll(pending);if(selected!=null){pending.stream().filter(e->e.key().equals(selected.key())).findFirst().ifPresent(e->list.getSelectionModel().select(e));}}finally{rendering=false;}
        count.setText(pending.isEmpty()?"Aucune réponse en attente":pending.size()+" réponse(s) à relire");return pending.size();
    }
}
