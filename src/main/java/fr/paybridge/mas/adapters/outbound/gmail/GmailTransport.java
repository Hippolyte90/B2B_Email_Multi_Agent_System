package fr.paybridge.mas.adapters.outbound.gmail;

import com.google.gson.JsonObject;

/** Frontière substituable pour les tests, sans requête réseau. */
@FunctionalInterface
public interface GmailTransport {
    JsonObject request(String method, String path, JsonObject body);
}
