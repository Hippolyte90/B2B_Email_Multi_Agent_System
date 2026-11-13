package fr.paybridge.mas.adapters.outbound.llm;

import java.net.URI;
import java.util.Locale;

/** Aucun DNS arbitraire, identifiant, proxy ou chemin fourni par la configuration. */
public record LoopbackEndpoint(URI base) {
    public LoopbackEndpoint {
        if (base == null || !"http".equals(base.getScheme()) || base.getHost() == null
                || base.getPort() < 1 || base.getPort() > 65535 || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || (!base.getPath().isEmpty() && !base.getPath().equals("/"))) {
            throw new IllegalArgumentException("Adresse HTTP loopback explicite requise");
        }
        var host = base.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("[::1]")) {
            throw new IllegalArgumentException("Le moteur doit être exécuté sur le poste local");
        }
        base = URI.create("http://" + (host.equals("localhost") ? "127.0.0.1" : host) + ":" + base.getPort() + "/");
    }
}
