#!/bin/bash

# Couleurs pour output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  TEST BATCH LDAP AVEC DOCKER         ${NC}"
echo -e "${BLUE}========================================${NC}"

# Fonction pour afficher les logs
show_logs() {
    echo -e "\n${YELLOW}=== LOGS DE L'APPLICATION ===${NC}"
    docker logs batch-application --tail 50
}

# Fonction pour vérifier les services
check_services() {
    echo -e "\n${YELLOW}=== VÉRIFICATION DES SERVICES ===${NC}"

    # Vérifier OpenLDAP
    if docker exec batch-openldap ldapsearch -x -H ldap://localhost -b "dc=batch-test,dc=com" -s base > /dev/null 2>&1; then
        echo -e "${GREEN}✓ OpenLDAP est opérationnel${NC}"
    else
        echo -e "${RED}✗ OpenLDAP n'est pas accessible${NC}"
        return 1
    fi

    # Vérifier PostgreSQL
    if docker exec batch-postgres pg_isready -U batchuser -d batchdb > /dev/null 2>&1; then
        echo -e "${GREEN}✓ PostgreSQL est opérationnel${NC}"
    else
        echo -e "${RED}✗ PostgreSQL n'est pas accessible${NC}"
        return 1
    fi

    # Vérifier l'application
    if curl -s http://localhost:8081/batch/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Application Spring Boot est opérationnelle${NC}"
    else
        echo -e "${YELLOW}⚠ Application non encore disponible${NC}"
    fi

    return 0
}

# Fonction pour exécuter le batch
run_batch() {
    echo -e "\n${YELLOW}=== EXÉCUTION DU BATCH ===${NC}"

    # Mode simulation d'abord
    echo -e "${BLUE}1. Test en mode simulation (pas de modification LDAP)...${NC}"
    docker-compose run --rm batch-app \
        -e BATCH_SIMULATE_LOCK=true \
        -e LOGGING_LEVEL_BATCH=DEBUG

    read -p "Voulez-vous continuer avec le vrai verrouillage? (o/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Oo]$ ]]; then
        echo -e "\n${BLUE}2. Exécution réelle du batch...${NC}"
        docker-compose run --rm batch-app \
            -e BATCH_SIMULATE_LOCK=false \
            -e LOGGING_LEVEL_BATCH=INFO
    fi
}

# Fonction pour vérifier les résultats
verify_results() {
    echo -e "\n${YELLOW}=== VÉRIFICATION DES RÉSULTATS ===${NC}"

    # Compter les utilisateurs verrouillés
    echo -e "${BLUE}Utilisateurs verrouillés après batch:${NC}"
    docker exec batch-openldap ldapsearch -x -H ldap://localhost \
        -b "ou=users,dc=batch-test,dc=com" \
        "(pwdAccountLockedTime=*)" cn authTimestamp pwdAccountLockedTime | \
        grep -E "(^cn:|authTimestamp|pwdAccountLockedTime)" | \
        sed 's/^/  /'

    # Compter le total
    total_locked=$(docker exec batch-openldap ldapsearch -x -H ldap://localhost \
        -b "ou=users,dc=batch-test,dc=com" \
        "(pwdAccountLockedTime=*)" 2>/dev/null | grep "^dn:" | wc -l)

    echo -e "\n${BLUE}Résumé:${NC}"
    echo -e "  Total utilisateurs verrouillés: ${total_locked}"

    # Vérifier les attentes
    echo -e "\n${BLUE}Résultats attendus:${NC}"
    echo -e "  ✓ Alice Adams (A) - devrait être verrouillé"
    echo -e "  ✓ Arthur Anderson (A) - devrait être verrouillé"
    echo -e "  ✓ Bob Brown (B) - devrait être verrouillé"
    echo -e "  ✗ Charlie Clark (C) - devrait RESTER actif"
    echo -e "  ✗ Admin David (D) - devrait être EXCLU"
    echo -e "  ✗ Locked Edward (E) - déjà verrouillé"
    echo -e "  ✗ Test Frank (F) - devrait être EXCLU"
    echo -e "  ✗ Service George (G) - devrait être EXCLU"
    echo -e "  ✓ 0zero.user (0) - devrait être verrouillé"
    echo -e "  ✓ 1one.user (1) - devrait être verrouillé"
    echo -e "  ✗ 2system.user (2) - devrait être EXCLU"
}

# Fonction pour nettoyer
cleanup() {
    echo -e "\n${YELLOW}=== NETTOYAGE ===${NC}"
    docker-compose down -v
    echo -e "${GREEN}✓ Environnement nettoyé${NC}"
}

# Fonction pour afficher l'aide
show_help() {
    echo -e "${BLUE}Utilisation:${NC}"
    echo -e "  $0 [commande]"
    echo -e ""
    echo -e "${BLUE}Commandes:${NC}"
    echo -e "  start     - Démarrer l'environnement Docker"
    echo -e "  test      - Exécuter le batch en mode test"
    echo -e "  run       - Exécuter le batch réel"
    echo -e "  verify    - Vérifier les résultats"
    echo -e "  logs      - Afficher les logs"
    echo -e "  stop      - Arrêter l'environnement"
    echo -e "  clean     - Nettoyer complètement"
    echo -e "  help      - Afficher cette aide"
}

# Main
case "$1" in
    start)
        echo -e "${GREEN}Démarrage de l'environnement Docker...${NC}"
        docker-compose up -d
        sleep 15
        check_services
        ;;

    test)
        echo -e "${GREEN}Test du batch en mode simulation...${NC}"
        docker-compose run --rm batch-app \
            -e BATCH_SIMULATE_LOCK=true \
            -e LOGGING_LEVEL_BATCH=DEBUG
        show_logs
        ;;

    run)
        echo -e "${GREEN}Exécution réelle du batch...${NC}"
        docker-compose run --rm batch-app \
            -e BATCH_SIMULATE_LOCK=false \
            -e LOGGING_LEVEL_BATCH=INFO
        show_logs
        verify_results
        ;;

    verify)
        verify_results
        ;;

    logs)
        show_logs
        ;;

    stop)
        echo -e "${YELLOW}Arrêt de l'environnement...${NC}"
        docker-compose down
        ;;

    clean)
        cleanup
        ;;

    help|*)
        show_help
        ;;
esac