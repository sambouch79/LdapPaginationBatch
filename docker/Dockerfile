FROM osixia/openldap:1.5.0

# Copier les fichiers LDIF
#COPY 01-modules-config.ldif /container/service/slapd/assets/config/bootstrap/ldif/01-modules-config.ldif
#COPY 00-ppolicy-schema.ldif /container/service/slapd/assets/config/bootstrap/ldif/00-ppolicy-schema.ldif
#COPY 02-overlays-config.ldif /container/service/slapd/assets/config/bootstrap/ldif/02-overlays-config.ldif

# DONNÉES ANNUAIRE (dc=test,dc=com) - dans /custom/
#COPY 03-ppolicy-data.ldif /container/service/slapd/assets/config/bootstrap/ldif/custom/03-ppolicy-data.ldif
COPY test-data.ldif /container/service/slapd/assets/config/bootstrap/ldif/50-test-data.ldif


# Définir les permissions
RUN chmod 644 /container/service/slapd/assets/config/bootstrap/ldif/*.ldif


# Variables d'environnement
ENV LDAP_ORGANISATION="Test" \
    LDAP_DOMAIN="test.com" \
    LDAP_ADMIN_PASSWORD="admin123"


EXPOSE 389 636
