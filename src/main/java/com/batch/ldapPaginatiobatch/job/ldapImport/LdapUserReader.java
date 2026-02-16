package com.batch.ldapPaginatiobatch.job.ldapImport;

import com.batch.ldapPaginatiobatch.model.LdapUser;
import com.batch.ldapPaginatiobatch.service.LdapUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class LdapUserReader implements ItemReader<LdapUser> {

    private final LdapUserService ldapUserService;
    @Value("${batch.ldap.inactive-months:6}")
    private int inactivate_month;

    // État de la lecture
    private Map<String, List<LdapUser>> usersByLetter;
    private Iterator<Map.Entry<String, List<LdapUser>>> letterIterator;
    private Iterator<LdapUser> currentUserIterator;
    private String currentLetter;

    // Statistiques
    private int totalUsersRead = 0;
    private int currentLetterIndex = 0;
    private int totalLetters;
    private long startTime;

    @Override
    public LdapUser read() throws Exception {
        // Initialisation au premier appel
        if (usersByLetter == null) {
            initialize();
        }

        // Trouver le prochain utilisateur
        LdapUser user = findNextUser();

        if (user != null) {
            totalUsersRead++;
            logProgress();
        } else {
            logCompletion();
        }

        return user;
    }

    private void initialize() {
        startTime = System.currentTimeMillis();
        log.info("Initialisation de la lecture LDAP (pagination A-Z 0-9)...");

        // Appel au service pour récupérer tous les utilisateurs groupés par lettre
        usersByLetter = ldapUserService.findAllInactiveUsersByLetters(inactivate_month);
        letterIterator = usersByLetter.entrySet().iterator();
        totalLetters = usersByLetter.size();

        log.info("{} lettres chargées, début lecture...", totalLetters);
    }

    private LdapUser findNextUser() {
        // Si pas d'utilisateurs courants ou fin de la liste courante
        if (currentUserIterator == null || !currentUserIterator.hasNext()) {
            if (!moveToNextLetter()) {
                return null;
            }
        }

        // Retourner l'utilisateur suivant
        return currentUserIterator.next();
    }

    private boolean moveToNextLetter() {
        if (!letterIterator.hasNext()) {
            return false;
        }

        Map.Entry<String, List<LdapUser>> entry = letterIterator.next();
        currentLetter = entry.getKey();
        currentUserIterator = entry.getValue().iterator();
        currentLetterIndex++;

        log.info("Lettre {}/{}: '{}' - {} utilisateurs",
                currentLetterIndex, totalLetters, currentLetter, entry.getValue().size());

        return true;
    }

    private void logProgress() {
        // Log tous les 100 utilisateurs
        if (totalUsersRead % 100 == 0) {
            long elapsed = System.currentTimeMillis() - startTime;
            double usersPerSecond = (totalUsersRead * 1000.0) / elapsed;

            log.info("Progression: {} utilisateurs ({} utilisateurs/s) - Lettre: {}",
                    totalUsersRead,
                    String.format("%.1f", usersPerSecond),
                    currentLetter != null ? currentLetter : "N/A");
        }
    }

    private void logCompletion() {
        long elapsed = System.currentTimeMillis() - startTime;

        log.info("=".repeat(70));
        log.info("LECTURE LDAP TERMINÉE");
        log.info("=".repeat(70));
        log.info("Durée totale: {} secondes", elapsed / 1000);
        log.info("Utilisateurs lus: {}", totalUsersRead);
        log.info("Lettres traitées: {}/{}", currentLetterIndex, totalLetters);

        if (totalUsersRead > 0) {
            double usersPerSecond = (totalUsersRead * 1000.0) / elapsed;
            log.info("Débit moyen: {} utilisateurs/seconde",
                    String.format("%.1f", usersPerSecond));
        }
        log.info("=".repeat(70));
    }

    /**
     * Réinitialise le reader
     */
    public void reset() {
        usersByLetter = null;
        letterIterator = null;
        currentUserIterator = null;
        currentLetter = null;
        totalUsersRead = 0;
        currentLetterIndex = 0;
        startTime = 0;
        log.info("Reader réinitialisé");
    }
}