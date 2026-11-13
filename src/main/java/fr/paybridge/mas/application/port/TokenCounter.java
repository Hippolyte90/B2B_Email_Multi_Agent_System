package fr.paybridge.mas.application.port;

public interface TokenCounter {
    long count(String text);
    String id();
    boolean estimated();
}
