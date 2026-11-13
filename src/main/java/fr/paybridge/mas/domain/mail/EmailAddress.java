package fr.paybridge.mas.domain.mail;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Adresse simple de démonstration ; pas un parseur complet de messages RFC. */
public record EmailAddress(String value) {
    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}");

    public EmailAddress {
        Objects.requireNonNull(value, "Adresse obligatoire");
        value = value.strip();
        if (!ADDRESS.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException("Adresse email invalide");
        }
        // Le domaine est insensible à la casse ; la partie locale est préservée.
        int separator = value.lastIndexOf('@');
        value = value.substring(0, separator + 1) + value.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}
