package com.batch.ldapPaginatiobatch.job.ldapImport;

import com.batch.ldapPaginatiobatch.model.LdapUser;
import com.batch.ldapPaginatiobatch.service.LdapUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class LdapUserProcessor implements ItemProcessor<LdapUser, LdapUser> {

    private final LdapUserService ldapUserService;

    @Override
    public LdapUser process(LdapUser user) throws Exception {
        if (user == null) {
            return null;
        }

        // Validation basique
        if (user.getCn() == null || user.getCn().trim().isEmpty()) {
            log.warn("Utilisateur sans CN ignoré");
            return null;
        }

        // Appel au service pour décision métier
        boolean shouldLock = ldapUserService.shouldLockUser(user);

        if (shouldLock) {
            user.setLocked(true);
            log.debug("✓ Utilisateur '{}' marqué pour verrouillage", user.getCn());
            return user;
        } else {
            log.debug("✗ Utilisateur '{}' non éligible", user.getCn());
            return null;
        }
    }
}
