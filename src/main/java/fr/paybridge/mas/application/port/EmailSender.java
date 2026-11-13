package fr.paybridge.mas.application.port;

import fr.paybridge.mas.domain.delivery.ApprovedEmail;
import fr.paybridge.mas.domain.delivery.DeliveryResult;
import fr.paybridge.mas.domain.delivery.TransportMode;

import java.io.IOException;

public interface EmailSender {
    TransportMode mode();
    DeliveryResult send(ApprovedEmail email) throws IOException, InterruptedException;
}
