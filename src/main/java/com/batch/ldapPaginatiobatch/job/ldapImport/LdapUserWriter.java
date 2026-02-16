package com.batch.ldapPaginatiobatch.job.ldapImport;

import com.batch.ldapPaginatiobatch.model.LdapUser;
import com.batch.ldapPaginatiobatch.service.LdapUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LdapUserWriter implements ItemWriter<LdapUser> {
    private final LdapUserService ldapUserService;

    // Statistiques
    private int totalProcessed = 0;
    private int successfullyLocked = 0;
    private int skipped = 0;
    private final LdapTemplate ldapTemplate;
    private static final String ATTR_LOCK_STATUS = "employeeType";
    private static final String LOCK_VALUE_TO_SET = "LOCKED";

    @Override
    public void write(Chunk<? extends LdapUser> chunk) throws Exception {
        log.info("Traitement d'un chunk de {} utilisateur(s)", chunk.size());


       List<? extends LdapUser> users = chunk.getItems();

        if (users.isEmpty()) {
            return;
        }

        log.info("Traitement d'un chunk de {} utilisateur(s)", users.size());

        try {
            // Modification batch en une seule opération LDAP
            lockUsersBatch(users);

            log.info("✓ Chunk de {} utilisateurs verrouillés avec succès", users.size());

        } catch (Exception e) {
            log.error("✗ Erreur lors du verrouillage du chunk", e);

            // Fallback : Traiter un par un pour identifier le problème
            processSingleUsers(users);
        }

    }

    /**
     * OPTIMISATION  : Verrouillage batch via une seule connexion LDAP
     * Utilise une transaction LDAP pour tout le chunk
     */
    private void lockUsersBatch(List<? extends LdapUser> users) {
        ldapTemplate.executeReadWrite(ctx -> {
            for (LdapUser user : users) {
                BasicAttribute lockAttribute = new BasicAttribute(ATTR_LOCK_STATUS, LOCK_VALUE_TO_SET);
                ModificationItem[] mods = new ModificationItem[] {
                        new ModificationItem(DirContext.REPLACE_ATTRIBUTE, lockAttribute)
                };
                ctx.modifyAttributes(user.getDn(), mods);
            }
            return null;
        });
    }

    /**
     * Fallback : Traitement individuel en cas d'erreur batch
     */
    private void processSingleUsers(List<? extends LdapUser> users) {
        for (LdapUser user : users) {
            try {
                lockSingleUser(user);
                log.debug("✓ Utilisateur '{}' verrouillé", user.getCn());
            } catch (Exception e) {
                log.error("✗ Échec définitif pour '{}': {}", user.getCn(), e.getMessage());
            }
        }
    }

    private void lockSingleUser(LdapUser user) {
        BasicAttribute lockAttribute = new BasicAttribute(ATTR_LOCK_STATUS, LOCK_VALUE_TO_SET);

        ModificationItem[] modifications = new ModificationItem[] {
                new ModificationItem(DirContext.REPLACE_ATTRIBUTE, lockAttribute)
        };

        ldapTemplate.modifyAttributes(user.getDn(), modifications);
    }

    /**
     * Affiche les statistiques
     */
    public void printStatistics() {
        log.info("=".repeat(60));
        log.info("STATISTIQUES WRITER");
        log.info("=".repeat(60));
        log.info("Total traités: {}", totalProcessed);
        log.info("Comptes verrouillés: {}", successfullyLocked);
        log.info("Comptes ignorés: {}", skipped);

        if (totalProcessed > 0) {
            double successRate = (successfullyLocked * 100.0) / totalProcessed;
            log.info("Taux de succès: {:.2f}%", successRate);
        }
        log.info("=".repeat(60));
    }

    /**
     * Réinitialise les statistiques
     */
    public void resetStatistics() {
        totalProcessed = 0;
        successfullyLocked = 0;
        skipped = 0;
        log.info("Statistiques writer réinitialisées");
    }
}
