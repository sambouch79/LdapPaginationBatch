package com.batch.ldapPaginatiobatch.job.ldapImport;

import com.batch.ldapPaginatiobatch.model.LdapUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.infrastructure.item.ItemReader;

import org.springframework.ldap.core.*;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.util.Date;
import java.util.List;
import java.util.TimeZone;


@Slf4j
@Component
@RequiredArgsConstructor
public class PaginatedLdapUserReader implements ItemReader<LdapUser> {

        private final LdapTemplate ldapTemplate;

        private static final int LDAP_PAGE_SIZE = 1000;
        private static final String[] LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".split("");

        // Cache pour le formatter de date
        private static final ThreadLocal<SimpleDateFormat> LDAP_DATE_FORMATTER =
                ThreadLocal.withInitial(() -> {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss'Z'");
                    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                    return sdf;
                });

        private List<LdapUser> currentLetterUsers;
        private int currentLetterIndex = 0;
        private int currentUserIndex = 0;
        private boolean finished = false;

        @Override
        public LdapUser read() throws Exception {
            if (finished) {
                return null;
            }

            if (currentLetterUsers == null || currentUserIndex >= currentLetterUsers.size()) {
                if (!loadNextLetter()) {
                    finished = true;
                    return null;
                }
            }

            LdapUser user = currentLetterUsers.get(currentUserIndex++);

            // Log de progression
            if (currentUserIndex % 100 == 0) {
                log.debug("Progression lettre '{}': {}/{}",
                        LETTERS[currentLetterIndex - 1],
                        currentUserIndex,
                        currentLetterUsers.size());
            }

            return user;
        }

        private boolean loadNextLetter() {
            if (currentLetterIndex >= LETTERS.length) {
                return false;
            }

            String currentLetter = LETTERS[currentLetterIndex++];
            log.info("Traitement lettre '{}' ({}/{})",
                    currentLetter, currentLetterIndex, LETTERS.length);

            try {
                currentLetterUsers = fetchUsersForLetter(currentLetter);
                currentUserIndex = 0;

                if (currentLetterUsers.isEmpty()) {
                    log.info("Aucun utilisateur pour la lettre '{}'", currentLetter);
                    return loadNextLetter(); // Passer à la suivante
                }

                log.info("Lettre '{}': {} utilisateurs trouvés",
                        currentLetter, currentLetterUsers.size());
                return true;

            } catch (Exception e) {
                log.error("Erreur lettre '{}': {}", currentLetter, e.getMessage());
                return loadNextLetter();
            }
        }

        private List<LdapUser> fetchUsersForLetter(String letter) {
            // Construire le filtre
            LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
            String sixMonthsAgoStr = sixMonthsAgo.atZone(ZoneId.of("UTC"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'"));

            String filter;
            if (letter.matches("\\d")) {
                filter = String.format(
                        "(&(objectClass=person)(cn=%s*)(authTimestamp<=%s))",
                        letter, sixMonthsAgoStr
                );
            } else {
                filter = String.format(
                        "(&(objectClass=person)(|(cn=%s*)(cn=%s*))(authTimestamp<=%s))",
                        letter.toUpperCase(), letter.toLowerCase(), sixMonthsAgoStr
                );
            }

            // Recherche LDAP
            return ldapTemplate.search(
                    LdapQueryBuilder.query().filter(filter),
                    new AbstractContextMapper<LdapUser>() {
                        @Override
                        protected LdapUser doMapFromContext(DirContextOperations ctx) {
                            LdapUser user = new LdapUser();
                            user.setCn(ctx.getStringAttribute("cn"));
                            user.setDistinguishedName(ctx.getDn().toString());

                            String authTimestampStr = ctx.getStringAttribute("authTimestamp");
                            if (authTimestampStr != null && !authTimestampStr.isEmpty()) {
                                try {
                                    Date authDate = LDAP_DATE_FORMATTER.get().parse(authTimestampStr);
                                    user.setAuthTimestamp(authDate);
                                } catch (java.text.ParseException e) {
                                    log.warn("Format date invalide pour {}: {}",
                                            user.getCn(), authTimestampStr);
                                }
                            }

                            return user;
                        }
                    }
            );
        }

        /**
         * Réinitialise le reader (utile pour les tests)
         */
        public void reset() {
            currentLetterIndex = 0;
            currentUserIndex = 0;
            currentLetterUsers = null;
            finished = false;
            log.info("Reader réinitialisé");
        }
}