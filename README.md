# 🔒 LDAP Inactive Account Locking — Spring Batch

> 🇫🇷 [Français](#-description-fr) | 🇬🇧 [English](#-description-en)

![Java](https://img.shields.io/badge/Java-17-orange?logo=java)
![Spring Batch](https://img.shields.io/badge/Spring%20Batch-5.x-brightgreen?logo=spring)
![Docker](https://img.shields.io/badge/Docker-Compose-blue?logo=docker)
![Prometheus](https://img.shields.io/badge/Monitoring-Prometheus%20%2B%20Grafana-orange?logo=prometheus)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

---

## 🇫🇷 Description (FR)

Batch Spring permettant le **verrouillage automatique des comptes LDAP inactifs** conformément aux règles de conformité RGPD.

Le traitement est **partitionné par groupes alphabétiques** (A-C, D-F, ..., 0-9) pour garantir de hautes performances, et intègre un **monitoring complet** via Prometheus et Grafana.

**🚀 Performances mesurées : ~39 utilisateurs/seconde**

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Spring Batch App                             │
│                                                                     │
│  ┌──────────────────────┐                                           │
│  │  LdapLetterPartitioner│  Découpe en 9 groupes :                  │
│  │  A-C / D-F / G-I /   │  chaque groupe = 1 partition = 1 thread  │
│  │  J-L / M-O / P-R /   │                                           │
│  │  S-U / V-Z / 0-9     │                                           │
│  └──────────┬───────────┘                                           │
│             │  gridSize=4 (4 workers en parallèle)                  │
│             ▼                                                        │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    masterStep (partitionné)                  │   │
│  │   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │   │
│  │   │ Worker-1 │  │ Worker-2 │  │ Worker-3 │  │ Worker-4 │   │   │
│  │   │  (A-C)   │  │  (D-F)   │  │  (G-I)   │  │  (J-L)   │   │   │
│  │   └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │   │
│  └────────┼─────────────┼─────────────┼─────────────┼──────────┘   │
│           ▼             ▼             ▼             ▼               │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    workerStep (chunk=50)                     │   │
│  │                                                              │   │
│  │  LdapReader          LdapProcessor        LdapWriter         │   │
│  │  ─────────           ─────────────        ──────────         │   │
│  │  Filtre par          shouldLockUser()     lockUsersBatch()   │   │
│  │  groupe A-Z          - Déjà verrouillé?   (connexion unique) │   │
│  │  + filtre Java       - Compte système?    Fallback unitaire  │   │
│  │  par inactivité      - Éligible?          en cas d'erreur    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  JobExecutionListener                                        │   │
│  │  beforeJob() → log démarrage                                 │   │
│  │  afterJob()  → log stats + push métriques Prometheus        │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
          │                                           │
          ▼                                           ▼
  ┌──────────────┐                        ┌──────────────────┐
  │   OpenLDAP   │                        │   PostgreSQL      │
  │  :389        │                        │   JobRepository   │
  │  dc=test,    │                        │   :5432           │
  │  dc=com      │                        └──────────────────┘
  └──────────────┘

  ┌───────────────┐    ┌──────────────┐    ┌─────────────┐
  │  PushGateway  │───►│  Prometheus  │───►│   Grafana   │
  │  :9091        │    │  :9090       │    │   :3000     │
  └───────────────┘    └──────────────┘    └─────────────┘
```

---

## ⚙️ Fonctionnement Détaillé

### 1. Partitionnement (`LdapLetterPartitioner`)

L'annuaire LDAP est découpé en **9 groupes alphabétiques** basés sur l'attribut `sn` (nom de famille) :

```
A-C | D-F | G-I | J-L | M-O | P-R | S-U | V-Z | 0-9
```

Chaque groupe est traité en parallèle par un worker indépendant (ThreadPoolTaskExecutor, 4 threads core / 8 max).

### 2. Lecture (`LdapUserReader` / `LdapJobConfig`)

- Le reader est `@StepScope` : instancié **une fois par partition**, il reçoit le groupe via `#{stepExecutionContext['nameGroup']}`
- Pour chaque groupe, le filtre LDAP récupère les utilisateurs correspondant aux lettres du groupe
- ⚠️ **Le filtrage par date d'inactivité est fait en Java** (pas en LDAP) pour contourner les limitations des filtres LDAP sur les timestamps personnalisés

Exemple de filtre LDAP généré pour le groupe `A-C` :

```
(&
  (objectClass=person)
  (|
    (|(sn=A*)(sn=a*))
    (|(sn=B*)(sn=b*))
    (|(sn=C*)(sn=c*))
  )
)
```

Puis filtrage Java sur la date :

```java
.filter(user -> !user.getAuthTimestamp().after(thresholdDate))
```

**Attributs LDAP utilisés :**

| Attribut LDAP    | Rôle                                      |
|------------------|-------------------------------------------|
| `cn`             | Identifiant unique de l'utilisateur       |
| `sn`             | Nom de famille (base de pagination)       |
| `employeeNumber` | Timestamp de dernière connexion           |
| `employeeType`   | Statut de verrouillage (`LOCKED` / vide)  |

### 3. Traitement (`LdapUserProcessor`)

Avant verrouillage, chaque utilisateur passe par `shouldLockUser()` :

- ❌ Ignoré si déjà `LOCKED`
- ❌ Ignoré si `cn` contient : `admin`, `root`, `system`, `test`, `demo`, `guest`, `service`, `backup`, `support`
- ✅ Éligible sinon

### 4. Écriture (`LdapUserWriter`)

- **Mode optimisé** : `lockUsersBatch()` — modification de tout le chunk via **une seule connexion LDAP** (`executeReadWrite`)
- **Fallback automatique** : si le batch échoue, bascule en mode unitaire (`processSingleUsers`)
- Taille de chunk : **50 utilisateurs** par transaction
- Retry sur `NamingException` : **3 tentatives**
- Skip limit : **100 erreurs** tolérées

---

## 🐳 Installation & Lancement Docker

### Prérequis

- Docker & Docker Compose
- Java 17+
- Maven 3.8+

### Structure du projet

```
ldap-pagination-batch/
├── src/
│   └── main/
│       ├── java/com/batch/ldapPaginatiobatch/
│       │   ├── config/          # LdapJobConfig.java
│       │   ├── job/ldapImport/  # Partitioner, Reader, Writer, Listener
│       │   ├── model/           # LdapUser.java
│       │   └── service/         # LdapUserService.java
│       └── resources/
│           ├── application.properties
│           └── application-docker.properties
├── docker/
│   ├── Dockerfile               # Image OpenLDAP custom
│   └── bootstrap.ldif           # Données de test
├── grafana-dashboard-ldap-batch.json
├── prometheus.yml
├── docker-compose.yml
└── Dockerfile
```

### Lancement rapide

```bash
# Cloner le projet
git clone https://github.com/votre-user/ldap-pagination-batch.git
cd ldap-pagination-batch

# Construire et démarrer tous les services
docker-compose up -d --build

# Suivre les logs du batch
docker-compose logs -f app

# Vérifier que tous les services sont UP
docker-compose ps
```

### Services démarrés

| Service      | URL                        | Credentials               |
|--------------|----------------------------|---------------------------|
| Application  | `http://localhost:8080`    | —                         |
| OpenLDAP     | `ldap://localhost:389`     | admin / admin123          |
| PostgreSQL   | `localhost:5432/batchdb`   | batchuser / batchpass     |
| PushGateway  | `http://localhost:9091`    | —                         |
| Prometheus   | `http://localhost:9090`    | —                         |
| Grafana      | `http://localhost:3000`    | admin / admin             |

### Chargement des données OpenLDAP

L'image OpenLDAP est construite depuis `./docker/` avec un fichier `bootstrap.ldif` chargé automatiquement au démarrage du container.

**Structure LDAP générée :**

```
dc=test,dc=com
└── ou=users
    ├── cn=user001   (employeeNumber=20231001120000Z, employeeType=)
    ├── cn=user002   (employeeNumber=20240315083000Z, employeeType=)
    └── ...
```

**Format du timestamp de dernière connexion (`employeeNumber`) :**

```
yyyyMMddHHmmss'Z'   →   ex: 20231001120000Z  (1er oct 2023 à 12:00 UTC)
```

> ⚠️ Le container OpenLDAP utilise `--copy-service` pour forcer le rechargement du LDIF à chaque démarrage (utile en dev). À désactiver en prod via `KEEP_EXISTING_CONFIG: "true"`.

---

## ⚙️ Configuration

### `application.properties`

```properties
# === LDAP ===
spring.ldap.urls=ldap://localhost:389
spring.ldap.base=dc=test,dc=com
spring.ldap.username=cn=admin,dc=test,dc=com
spring.ldap.password=admin123

# === BATCH ===
# Nombre de mois d'inactivité avant verrouillage
batch.ldap.inactive-months=6
# Ne pas lancer automatiquement au démarrage
spring.batch.job.enabled=false

# === DATASOURCE (Job Repository) ===
spring.datasource.url=jdbc:postgresql://localhost:5432/batchdb
spring.datasource.username=batchuser
spring.datasource.password=batchpass
spring.datasource.driver-class-name=org.postgresql.Driver

# === PUSHGATEWAY ===
pushgateway.url=pushgateway:9091
```

### `application-docker.properties` (profil Docker)

```properties
spring.ldap.urls=ldap://openldap:389
spring.datasource.url=jdbc:postgresql://postgres:5432/batchdb
pushgateway.url=pushgateway:9091
```

> ⚠️ **Règle Docker** : utiliser les noms de services (`openldap`, `postgres`, `pushgateway`) et **jamais** `localhost` pour la communication inter-containers.

### Variables d'environnement (`docker-compose`)

| Variable                    | Valeur par défaut | Description                                   |
|-----------------------------|-------------------|-----------------------------------------------|
| `SPRING_PROFILES_ACTIVE`    | `docker`          | Active le profil Docker                       |
| `BATCH_SIMULATE_LOCK`       | `false`           | `true` = simulation sans écriture LDAP réelle |

### Paramètres de performance

| Paramètre      | Valeur | Description                              |
|----------------|--------|------------------------------------------|
| `corePoolSize` | 4      | Workers parallèles                       |
| `maxPoolSize`  | 8      | Maximum de threads                       |
| `gridSize`     | 4      | Partitions actives simultanément         |
| `chunk-size`   | 50     | Utilisateurs par transaction LDAP        |
| `retryLimit`   | 3      | Tentatives sur `NamingException`         |
| `skipLimit`    | 100    | Erreurs tolérées avant échec du job      |

---

## 📊 Monitoring — Grafana / Prometheus

### Importer le Dashboard

1. Ouvrir Grafana → `http://localhost:3000` (admin / admin)
2. **Connections** → **Data Sources** → vérifier que l'URL Prometheus est `http://prometheus:9090`
3. **Dashboards** → **New** → **Import** → uploader `grafana-dashboard-ldap-batch.json`

### Métriques exposées

| Métrique                    | Type  | Description                              |
|-----------------------------|-------|------------------------------------------|
| `batch_job_status`          | Gauge | Statut du job (1 = succès, 0 = échec)    |
| `batch_items_read_total`    | Gauge | Comptes lus depuis LDAP                  |
| `batch_items_written_total` | Gauge | Comptes verrouillés                      |
| `push_time_seconds`         | Gauge | Timestamp du dernier push réussi         |
| `push_failure_time_seconds` | Gauge | Timestamp du dernier push en échec       |

### Configuration `prometheus.yml`

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'pushgateway'
    honor_labels: true        # ← OBLIGATOIRE : conserve job="lockInactiveUsersJob"
    static_configs:
      - targets: ['pushgateway:9091']
```

> ⚠️ Sans `honor_labels: true`, Prometheus écrase le label `job` et le dashboard Grafana n'affiche rien.

---

## 🛠️ Stack Technique

| Technologie         | Rôle                                          |
|---------------------|-----------------------------------------------|
| Java 17             | Langage                                       |
| Spring Batch 5.x    | Orchestration batch, partitionnement, retry   |
| Spring LDAP         | Lecture et modification de l'annuaire         |
| PostgreSQL 15       | Stockage du JobRepository Spring Batch        |
| OpenLDAP (Docker)   | Annuaire LDAP cible (données de test)         |
| Prometheus          | Collecte des métriques                        |
| Pushgateway         | Récepteur métriques pour jobs batch           |
| Grafana             | Dashboard de monitoring                       |
| Lombok              | Réduction du boilerplate Java                 |
| Docker Compose      | Orchestration des services                    |

---
---

## 🇬🇧 Description (EN)

A Spring Batch application for **automatically locking inactive LDAP accounts** in compliance with GDPR policies.

Processing is **partitioned by alphabetical groups** (A-C, D-F, ..., 0-9) for high throughput, with **full observability** via Prometheus and Grafana.

**🚀 Measured throughput: ~39 users/second**

---

## 🏗️ Architecture (EN)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Spring Batch App                             │
│                                                                     │
│  ┌──────────────────────┐                                           │
│  │  LdapLetterPartitioner│  Splits into 9 groups:                   │
│  │  A-C / D-F / G-I /   │  each group = 1 partition = 1 thread     │
│  │  J-L / M-O / P-R /   │                                           │
│  │  S-U / V-Z / 0-9     │                                           │
│  └──────────┬───────────┘                                           │
│             │  gridSize=4 (4 parallel workers)                      │
│             ▼                                                        │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    masterStep (partitioned)                  │   │
│  │   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │   │
│  │   │ Worker-1 │  │ Worker-2 │  │ Worker-3 │  │ Worker-4 │   │   │
│  │   │  (A-C)   │  │  (D-F)   │  │  (G-I)   │  │  (J-L)   │   │   │
│  │   └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │   │
│  └────────┼─────────────┼─────────────┼─────────────┼──────────┘   │
│           ▼             ▼             ▼             ▼               │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    workerStep (chunk=50)                     │   │
│  │  LdapReader        LdapProcessor        LdapWriter           │   │
│  │  ──────────        ─────────────        ──────────           │   │
│  │  Filter by         shouldLockUser()     lockUsersBatch()     │   │
│  │  letter group      - Already locked?    (single connection)  │   │
│  │  + Java filter     - System account?    Unit fallback        │   │
│  │  by inactivity     - Eligible?          on error             │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
          │                                           │
          ▼                                           ▼
  ┌──────────────┐                        ┌──────────────────┐
  │   OpenLDAP   │                        │   PostgreSQL      │
  │  :389        │                        │   JobRepository   │
  └──────────────┘                        └──────────────────┘

  ┌───────────────┐    ┌──────────────┐    ┌─────────────┐
  │  PushGateway  │───►│  Prometheus  │───►│   Grafana   │
  │  :9091        │    │  :9090       │    │   :3000     │
  └───────────────┘    └──────────────┘    └─────────────┘
```

---

## ⚙️ How It Works

### 1. Partitioning

The LDAP directory is split into **9 alphabetical groups** based on the `sn` attribute: `A-C | D-F | G-I | J-L | M-O | P-R | S-U | V-Z | 0-9`. Each group is processed in parallel by an independent worker.

### 2. Reading

The reader is `@StepScope` — instantiated once per partition, receiving the group via `#{stepExecutionContext['nameGroup']}`. Inactivity date filtering is done **in Java** to work around LDAP filter limitations on custom timestamp attributes.

**LDAP attributes used:**

| LDAP Attribute   | Role                                       |
|------------------|--------------------------------------------|
| `cn`             | User unique identifier                     |
| `sn`             | Surname (pagination base)                  |
| `employeeNumber` | Last login timestamp                       |
| `employeeType`   | Lock status (`LOCKED` / empty)             |

### 3. Processing

Each user is checked before locking: skipped if already `LOCKED`, skipped if `cn` matches a system account pattern (`admin`, `root`, `system`, etc.), eligible otherwise.

### 4. Writing

Optimized via `lockUsersBatch()` — the whole chunk is modified through **a single LDAP connection**. Automatic fallback to unit mode if the batch operation fails. Chunk size: 50, retry: 3, skip limit: 100.

---

## 🐳 Quick Start

```bash
git clone https://github.com/your-user/ldap-pagination-batch.git
cd ldap-pagination-batch
docker-compose up -d --build
docker-compose logs -f app
```

| Service      | URL                        | Credentials           |
|--------------|----------------------------|-----------------------|
| Application  | `http://localhost:8080`    | —                     |
| OpenLDAP     | `ldap://localhost:389`     | admin / admin123      |
| PostgreSQL   | `localhost:5432/batchdb`   | batchuser / batchpass |
| PushGateway  | `http://localhost:9091`    | —                     |
| Prometheus   | `http://localhost:9090`    | —                     |
| Grafana      | `http://localhost:3000`    | admin / admin         |

> ⚠️ **Docker networking**: always use service names (`openldap`, `postgres`) instead of `localhost` for inter-container communication.

---

## 📊 Monitoring

Import `grafana-dashboard-ldap-batch.json` into Grafana. Ensure the Prometheus datasource URL is `http://prometheus:9090` (not `localhost`).

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'pushgateway'
    honor_labels: true   # MANDATORY
    static_configs:
      - targets: ['pushgateway:9091']
```

---

## 🛠️ Tech Stack

Java 17 · Spring Batch 5.x · Spring LDAP · PostgreSQL 15 · OpenLDAP · Prometheus · Pushgateway · Grafana · Docker Compose · Lombok

---

## 📄 License

MIT License — see [LICENSE](LICENSE).
