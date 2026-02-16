package com.batch.ldapPaginatiobatch.service;


import com.batch.ldapPaginatiobatch.model.LdapUser;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

import javax.naming.directory.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LdapUserService {

    private final LdapTemplate ldapTemplate;

    // ========================================
    // CONFIGURATION DES ATTRIBUTS LDAP
    // ========================================

    /** Attribut contenant le timestamp de dernière connexion */
    private static final String ATTR_LAST_LOGIN = "employeeNumber";

    /** Attribut sur lequel faire la pagination */
    private static final String ATTR_PAGINATION = "sn";

    /** Attribut contenant le statut de verrouillage */
    private static final String ATTR_LOCK_STATUS = "employeeType";

    /** Valeur à définir pour verrouiller un compte */
    private static final String LOCK_VALUE_TO_SET = "LOCKED";

    /** Base DN pour la recherche des utilisateurs */
    private static final String SEARCH_BASE_DN = "ou=users";

    /** Classe d'objet pour filtrer les utilisateurs */
    private static final String OBJECT_CLASS = "person";

    // ========================================
    // CONFIGURATION PAGINATION PAR SN
    // ========================================

    private static final String[] NAME_GROUPS = {
            "A-C", "D-F", "G-I", "J-L", "M-O",
            "P-R", "S-U", "V-Z", "0-9"
    };


    private final SimpleDateFormat ldapDateFormat = new SimpleDateFormat("yyyyMMddHHmmss'Z'");

    @PostConstruct
    public void init() {
        ldapDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        log.info("=== CONFIGURATION LDAP SERVICE ===");
        log.info("Attribut last login: {}", ATTR_LAST_LOGIN);
        log.info("Attribut pagination: {}", ATTR_PAGINATION);
        log.info("Nombre de groupes: {}", NAME_GROUPS.length);
        log.info("==================================");
    }

    public Map<String, List<LdapUser>> findAllInactiveUsersByLetters(int months) {
        log.info("Début recherche utilisateurs inactifs (pagination par {} groupes)", NAME_GROUPS.length);

        Map<String, List<LdapUser>> usersByGroup = new LinkedHashMap<>();
        int totalUsers = 0;
        long startTime = System.currentTimeMillis();

        for (String nameGroup : NAME_GROUPS) {
            List<LdapUser> users = findInactiveUsersByNameGroup(nameGroup, months);
            usersByGroup.put(nameGroup, users);
            totalUsers += users.size();

            if (!users.isEmpty()) {
                log.info("Groupe '{}': {} utilisateurs inactifs", nameGroup, users.size());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Recherche terminée: {} utilisateurs inactifs sur {} groupes en {} ms",
                totalUsers, NAME_GROUPS.length, duration);

        return usersByGroup;
    }

    public List<LdapUser> findInactiveUsersByNameGroup(String nameGroup, int months) {
        try {
            // 1. Calculer la date limite
            LocalDateTime thresholdDateTime = LocalDateTime.now().minusMonths(months);
            String timestampThreshold = thresholdDateTime.atZone(ZoneId.of("UTC"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'"));

            // 2. Extraire les lettres du groupe
            List<String> letters = expandLetterGroup(nameGroup);

            // 3. Construire le filtre LDAP (SANS la condition de date)
            String filter = buildLdapFilterForGroup(letters);

            log.debug("=== RECHERCHE GROUPE {} ===", nameGroup);
            log.debug("Lettres: {}", letters);
            log.debug("Seuil: {}", timestampThreshold);
            log.debug("Filtre: {}", filter);

            // 4. Configurer la recherche
            SearchControls searchControls = new SearchControls();
            searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            searchControls.setReturningAttributes(new String[]{
                    "cn", "sn", ATTR_LAST_LOGIN, ATTR_LOCK_STATUS
            });
            searchControls.setCountLimit(5000);

            // 5. Exécuter la recherche LDAP (récupère TOUS les utilisateurs du groupe)
            List<LdapUser> allUsers = ldapTemplate.search(SEARCH_BASE_DN, filter, searchControls,
                    this::mapAttributesToLdapUser);

            allUsers.removeIf(Objects::isNull);

            // 6. FILTRER EN JAVA par date d'inactivité
            Date thresholdDate = ldapDateFormat.parse(timestampThreshold);

            List<LdapUser> inactiveUsers = allUsers.stream()
                    .filter(user -> user.getAuthTimestamp() != null)
                    .filter(user -> !user.getAuthTimestamp().after(thresholdDate)) // <= en Java
                    .toList();

            log.debug("Groupe '{}': {} utilisateurs totaux, {} inactifs",
                    nameGroup, allUsers.size(), inactiveUsers.size());

            return inactiveUsers;

        } catch (Exception e) {
            log.error("Erreur lors de la recherche pour le groupe '{}': {}", nameGroup, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<String> expandLetterGroup(String letterGroup) {
        List<String> letters = new ArrayList<>();

        String[] parts = letterGroup.split("-");
        if (parts.length != 2) {
            log.warn("Format de groupe invalide: {}", letterGroup);
            return letters;
        }

        char start = parts[0].charAt(0);
        char end = parts[1].charAt(0);

        for (char c = start; c <= end; c++) {
            letters.add(String.valueOf(c));
        }

        return letters;
    }

    /**
     * Construit le filtre LDAP SANS la condition de date (filtrée en Java)
     */
    private String buildLdapFilterForGroup(List<String> letters) {
        StringBuilder filter = new StringBuilder();

        filter.append("(&");
        filter.append("(objectClass=").append(OBJECT_CLASS).append(")");

        if (letters.size() > 1) {
            filter.append("(|");
        }

        for (String letter : letters) {
            if (letter.matches("[A-Z]")) {
                filter.append("(|");
                filter.append("(").append(ATTR_PAGINATION).append("=").append(letter).append("*)");
                filter.append("(").append(ATTR_PAGINATION).append("=").append(letter.toLowerCase()).append("*)");
                filter.append(")");
            } else {
                filter.append("(").append(ATTR_PAGINATION).append("=").append(letter).append("*)");
            }
        }

        if (letters.size() > 1) {
            filter.append(")");
        }

        filter.append(")");

        return filter.toString();
    }

    private LdapUser mapAttributesToLdapUser(Attributes attributes) {
        try {
            LdapUser user = new LdapUser();

            if (attributes.get("cn") != null) {
                user.setCn(attributes.get("cn").get().toString());
            } else {
                return null;
            }

            if (attributes.get("sn") != null) {
                user.setSn(attributes.get("sn").get().toString());
            }

            // CONSTRUIRE LE DN
            //String dn = String.format("cn=%s,%s,dc=test,dc=com", user.getCn(), SEARCH_BASE_DN);
            // DN RELATIF simple
            String dn = "cn=" + user.getCn() + ",ou=users";
            user.setDn(dn);

            // Timestamp de dernière connexion (employeeNumber)
            if (attributes.get(ATTR_LAST_LOGIN) != null) {
                String timestampStr = attributes.get(ATTR_LAST_LOGIN).get().toString();
                try {
                    Date authDate = ldapDateFormat.parse(timestampStr);
                    user.setAuthTimestamp(authDate);

                    log.debug("User {} ({}): {} = {}",
                            user.getCn(), user.getSn(), ATTR_LAST_LOGIN, timestampStr);
                } catch (ParseException e) {
                    log.warn("Format timestamp invalide pour {}: {}", user.getCn(), timestampStr);
                }
            }

            // Statut de verrouillage
            if (attributes.get(ATTR_LOCK_STATUS) != null) {
                String status = attributes.get(ATTR_LOCK_STATUS).get().toString();
                user.setLocked(LOCK_VALUE_TO_SET.equals(status));
            } else {
                user.setLocked(false);
            }

            return user;

        } catch (Exception e) {
            log.warn("Erreur mapping attributs LDAP: {}", e.getMessage());
            return null;
        }
    }

    public boolean shouldLockUser(LdapUser user) {
        if (user == null || user.getCn() == null) {
            return false;
        }

        String cn = user.getCn().toLowerCase();

        if (isExcludedAccount(cn)) {
            log.debug("Utilisateur '{}' exclu (compte système/admin)", user.getCn());
            return false;
        }

        if (user.isLocked()) {
            log.debug("Utilisateur '{}' déjà verrouillé", user.getCn());
            return false;
        }

        log.debug("Utilisateur '{}' ({}) éligible au verrouillage", user.getCn(), user.getSn());
        return true;
    }

    private boolean isExcludedAccount(String cn) {
        String[] excludedPatterns = {
                "admin", "administrateur", "root",
                "system", "test", "demo", "guest",
                "service", "backup", "support"
        };

        for (String pattern : excludedPatterns) {
            if (cn.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    public void lockUser(LdapUser user) {
        try {
            //String dn = String.format("cn=%s,%s,dc=test,dc=com", cn, SEARCH_BASE_DN);
            String dn = user.getDn();
            log.info("Tentative de verrouillage de: {}", dn);

            javax.naming.directory.BasicAttribute lockAttribute =
                    new javax.naming.directory.BasicAttribute(ATTR_LOCK_STATUS, LOCK_VALUE_TO_SET);

            javax.naming.directory.ModificationItem[] modifications =
                    new javax.naming.directory.ModificationItem[] {
                            new javax.naming.directory.ModificationItem(
                                    javax.naming.directory.DirContext.REPLACE_ATTRIBUTE,
                                    lockAttribute
                            )
                    };
            // Utiliser modifyAttributes avec un Name au lieu d'une String
            ldapTemplate.modifyAttributes(
                    org.springframework.ldap.support.LdapNameBuilder.newInstance(dn).build(),
                    modifications
            );

            //ldapTemplate.modifyAttributes(dn, modifications);
            log.info("✓ Utilisateur '{}' verrouillé ({}={})", dn, ATTR_LOCK_STATUS, LOCK_VALUE_TO_SET);

        } catch (Exception e) {
            log.error("✗ Erreur verrouillage '{}'",  e.getMessage());
            throw new RuntimeException("Échec verrouillage LDAP", e);
        }
    }

    /**
     * OPTIMISATION 1 : Verrouillage batch via une seule connexion LDAP
     * Utilise une transaction LDAP pour tout le chunk
     */
    private void lockUsersBatch(List<? extends LdapUser> users) {
        ldapTemplate.executeReadWrite(ctx -> {
            int successCount = 0;
            int errorCount = 0;

            for (LdapUser user : users) {
                try {
                    BasicAttribute lockAttribute = new BasicAttribute(ATTR_LOCK_STATUS, LOCK_VALUE_TO_SET);

                    ModificationItem[] mods = new ModificationItem[] {
                            new ModificationItem(DirContext.REPLACE_ATTRIBUTE, lockAttribute)
                    };

                    ctx.modifyAttributes(user.getDn(), mods);
                    successCount++;

                } catch (Exception e) {
                    log.warn("Erreur pour '{}': {}", user.getCn(), e.getMessage());
                    errorCount++;
                }
            }

            log.debug("Batch terminé: {} succès, {} erreurs", successCount, errorCount);
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
}