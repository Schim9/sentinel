package lu.kaminski.sentinel.evaluation;

/**
 * Résultat d'une règle pour un sujet donné.
 *
 * @param subjectKey   clé unique du sujet, ex. "host:pi-backend-1:disk:/"
 * @param state        état évalué
 * @param description  texte court décrivant la valeur mesurée (pour notifications et mails)
 * @param numericValue valeur brute mesurée (null pour les règles non-numériques)
 */
public record RuleResult(String subjectKey, State state, String description, Double numericValue) {

    /** Constructeur de commodité pour les règles sans valeur numérique. */
    public RuleResult(String subjectKey, State state, String description) {
        this(subjectKey, state, description, null);
    }

    public boolean isOk() { return state == State.OK; }
}
