package fr.paybridge.mas.application.context;

import fr.paybridge.mas.application.port.TokenCounter;

import java.nio.charset.StandardCharsets;

/** Estimation en octets UTF-8, volontairement prudente ; pas un tokenizer de modèle. */
public final class ConservativeTokenEstimator implements TokenCounter {
    @Override public long count(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    @Override public String id() { return "utf8-byte-estimate-v1"; }
    @Override public boolean estimated() { return true; }
}
