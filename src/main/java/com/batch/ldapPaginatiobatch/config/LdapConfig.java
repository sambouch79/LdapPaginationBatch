package com.batch.ldapPaginatiobatch.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.pool.factory.PoolingContextSource;

@Configuration
public class LdapConfig {

    @Value("${spring.ldap.urls}")
    private String ldapUrls;

    @Value("${spring.ldap.base}")
    private String ldapBase;

    @Value("${spring.ldap.username}")
    private String ldapUsername;

    @Value("${spring.ldap.password}")
    private String ldapPassword;

    @Bean
    public LdapTemplate ldapTemplate(LdapContextSource contextSource) {

        return new LdapTemplate(contextSource);

    }



    @Bean

    public ContextSource contextSource() {

        // 1. Configuration de la source réelle
        LdapContextSource target = new LdapContextSource();
        target.setUrl(ldapUrls);
        target.setBase(ldapBase);
        target.setUserDn(ldapUsername);
        target.setPassword(ldapPassword);
        target.afterPropertiesSet(); // Indispensable pour initialiser la source

        // 2. Configuration du Pool
        PoolingContextSource poolingContextSource = new PoolingContextSource();
        poolingContextSource.setContextSource(target);

        // On définit la taille du pool (doit être > au nombre de threads batch)
        poolingContextSource.setMaxActive(20);
        poolingContextSource.setMaxTotal(20);
        poolingContextSource.setTestOnBorrow(true); // Vérifie si la connexion est OK avant usage

        return poolingContextSource;

    }
}
