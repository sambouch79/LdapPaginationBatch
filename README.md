# 🔒 LDAP Inactive Account Locking — Spring Batch

> 🇫🇷 [Français](#français) | 🇬🇧 [English](#english)

---

## Français

### 📋 Description

Batch Spring permettant le **verrouillage automatique des comptes LDAP inactifs** selon les règles de conformité RGPD.  
Le traitement est **partitionné** pour garantir de hautes performances, et intègre un **monitoring complet** via Prometheus et Grafana.


---

### 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Spring Batch App                        │
│                                                             │
│  ┌─────────────┐    ┌──────────────────────────────────┐   │
│  │  Partitioner │───►│  Workers (partitions parallèles) │   │
│  │  (LDAP pages)│    │  ┌──────┐ ┌──────┐ ┌──────┐     │   │
│  └─────────────┘    │  │ W-1  │ │ W-2  │ │ W-N  │     │   │
│                      │  └──┬───┘ └──┬───┘ └──┬───┘     │   │
│                      └─────┼────────┼────────┼─────────┘   │
│                            ▼        ▼        ▼             │
│                    ┌───────────────────────────┐           │
│                    │  LdapItemWriter           │           │
│                    │  (userAccountControl)     │           │
│                    └───────────────────────────┘           │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  JobExecutionListener → PushGateway (métriques)      │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │                                      │
         ▼                                      ▼
  ┌─────────────┐                    ┌──────────────────┐
  │  OpenLDAP   │                    │   PostgreSQL      │
  │  (annuaire) │                    │  (JobRepository)  │
  └─────────────┘                    └──────────────────┘
         
  ┌──────────────┐    ┌───────────────┐    ┌─────────────┐
  │ PushGateway  │───►│  Prometheus   │───►│   Grafana   │
  │  :9091       │    │   :9090       │    │   :3000     │
  └──────────────┘    └───────────────┘    └─────────────┘
```

**Flux du traitement :**

1. Le `Partitioner` pagine l'annuaire LDAP et crée N partitions
2. Chaque worker lit sa page de comptes via LDAP
3. Les comptes inactifs (dépassant le seuil de jours configuré) sont verrouillés
4. Le `JobExecutionListener` pousse les métriques vers le PushGateway en fin de job

---

### 🚀 Installation & Lancement

#### Prérequis

- Docker & Docker Compose
- Java 17+
- Maven 3.8+

#### Lancement rapide

```bash
# Cloner le projet
git clone https://github.com/votre-user/ldap-pagination-batch.git
cd ldap-pagination-batch

# Construire l'image
docker-compose build

# Démarrer tous les services
docker-compose up -d

# Suivre les logs du batch
docker-compose logs -f app
```

#### Services démarrés

| Service      | URL                          | Description                  |
|--------------|------------------------------|------------------------------|
| Application  | `http://localhost:8080`      | Spring Batch App             |
| OpenLDAP     | `ldap://localhost:389`       | Annuaire LDAP                |
| PostgreSQL   | `localhost:5432`             | Job Repository               |
| PushGateway  | `http://localhost:9091`      | Récepteur métriques          |
| Prometheus   | `http://localhost:9090`      | Collecte métriques           |
| Grafana      | `http://localhost:3000`      | Dashboard (admin/admin)      |

---

### ⚙️ Configuration

#### `application.properties`

```properties
# === LDAP ===
spring.ldap.urls=ldap://localhost:389
spring.ldap.base=dc=test,dc=com
spring.ldap.username=cn=admin,dc=test,dc=com
spring.ldap.password=admin123

# === BATCH ===
# Nombre de mois d'inactivité avant verrouillage
batch.ldap.inactive-months=9
# Taille de chaque partition (page LDAP)
batch.partition.page-size=50
# Nombre de workers parallèles
batch.partition.grid-size=4
# Ne pas lancer automatiquement au démarrage (lancement manuel ou schedulé)
spring.batch.job.enabled=false

# === DATASOURCE (Job Repository) ===
spring.datasource.url=jdbc:postgresql://localhost:5432/batchdb
spring.datasource.username=batchuser
spring.datasource.password=batchpass

# === PUSHGATEWAY ===
pushgateway.url=pushgateway:9091
```

#### `application-docker.properties` (profil Docker)

```properties
spring.ldap.urls=ldap://openldap:389
spring.datasource.url=jdbc:postgresql://postgres:5432/batchdb
pushgateway.url=pushgateway:9091
```

> ⚠️ **Important Docker** : utiliser les noms de services (ex: `openldap`, `postgres`) et non `localhost` pour la communication inter-containers.

#### Variables d'environnement (docker-compose)

| Variable                    | Valeur par défaut  | Description                     |
|-----------------------------|--------------------|---------------------------------|
| `SPRING_PROFILES_ACTIVE`    | `docker`           | Profil Spring actif             |
| `BATCH_SIMULATE_LOCK`       | `false`            | Simuler sans verrouiller réellement |

---

### 📊 Monitoring Grafana / Prometheus

#### Importer le Dashboard

1. Ouvrir Grafana → `http://localhost:3000` (admin / admin)
2. **Connections** → **Data Sources** → vérifier que Prometheus pointe sur `http://prometheus:9090`
3. **Dashboards** → **New** → **Import**
4. Uploader le fichier `grafana-dashboard-ldap-batch.json` (disponible à la racine du projet)

#### Métriques exposées

| Métrique                   | Type  | Description                              |
|----------------------------|-------|------------------------------------------|
| `batch_job_status`         | Gauge | Statut du job (1 = succès, 0 = échec)    |
| `batch_items_read_total`   | Gauge | Nombre de comptes lus depuis LDAP        |
| `batch_items_written_total`| Gauge | Nombre de comptes verrouillés            |
| `push_time_seconds`        | Gauge | Timestamp du dernier push réussi         |
| `push_failure_time_seconds`| Gauge | Timestamp du dernier push en échec       |

#### Panels du Dashboard

- **Statut** du dernier job (✅ SUCCÈS / ❌ ÉCHEC)
- **Utilisateurs lus** depuis LDAP
- **Comptes verrouillés**
- **Comptes non verrouillés** (actifs / skippés)
- **Jauge** taux de verrouillage (%)
- **Historique** des exécutions (state timeline)
- **Évolution** lus / verrouillés dans le temps

#### Configuration Prometheus (`prometheus.yml`)

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'pushgateway'
    honor_labels: true   # Conserve le label job="lockInactiveUsersJob"
    static_configs:
      - targets: ['pushgateway:9091']
```

> ⚠️ `honor_labels: true` est **obligatoire** — sans ça, Prometheus écrase le label `job` de tes métriques.

---

### 🛠️ Stack Technique

- **Java 17** / **Spring Batch** / **Spring LDAP**
- **PostgreSQL** — stockage du Job Repository
- **OpenLDAP** — annuaire cible
- **Docker / Docker Compose**
- **Prometheus** + **Pushgateway** + **Grafana** — observabilité
- **Lombok** — réduction boilerplate
- **Maven** — build

---

---

## English

### 📋 Description

A Spring Batch application for **automatically locking inactive LDAP accounts** in compliance with GDPR policies.  
Processing is **partitioned** for high performance, with **full observability** via Prometheus and Grafana.

**Measured throughput: ~39 users/second**

---

### 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Spring Batch App                        │
│                                                             │
│  ┌─────────────┐    ┌──────────────────────────────────┐   │
│  │  Partitioner │───►│  Workers (parallel partitions)   │   │
│  │  (LDAP pages)│    │  ┌──────┐ ┌──────┐ ┌──────┐     │   │
│  └─────────────┘    │  │ W-1  │ │ W-2  │ │ W-N  │     │   │
│                      │  └──┬───┘ └──┬───┘ └──┬───┘     │   │
│                      └─────┼────────┼────────┼─────────┘   │
│                            ▼        ▼        ▼             │
│                    ┌───────────────────────────┐           │
│                    │  LdapItemWriter           │           │
│                    │  (userAccountControl)     │           │
│                    └───────────────────────────┘           │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  JobExecutionListener → PushGateway (metrics)        │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │                                      │
         ▼                                      ▼
  ┌─────────────┐                    ┌──────────────────┐
  │  OpenLDAP   │                    │   PostgreSQL      │
  │  (directory)│                    │  (JobRepository)  │
  └─────────────┘                    └──────────────────┘
         
  ┌──────────────┐    ┌───────────────┐    ┌─────────────┐
  │ PushGateway  │───►│  Prometheus   │───►│   Grafana   │
  │  :9091       │    │   :9090       │    │   :3000     │
  └──────────────┘    └───────────────┘    └─────────────┘
```

**Processing flow:**

1. The `Partitioner` paginates the LDAP directory and creates N partitions
2. Each worker reads its page of accounts from LDAP
3. Inactive accounts (exceeding the configured inactivity threshold) are locked
4. The `JobExecutionListener` pushes metrics to the PushGateway at the end of the job

---

### 🚀 Installation & Launch

#### Prerequisites

- Docker & Docker Compose
- Java 17+
- Maven 3.8+

#### Quick start

```bash
# Clone the project
git clone https://github.com/your-user/ldap-pagination-batch.git
cd ldap-pagination-batch

# Build the image
docker-compose build

# Start all services
docker-compose up -d

# Follow batch logs
docker-compose logs -f app
```

#### Running services

| Service      | URL                          | Description                  |
|--------------|------------------------------|------------------------------|
| Application  | `http://localhost:8080`      | Spring Batch App             |
| OpenLDAP     | `ldap://localhost:389`       | LDAP Directory               |
| PostgreSQL   | `localhost:5432`             | Job Repository               |
| PushGateway  | `http://localhost:9091`      | Metrics receiver             |
| Prometheus   | `http://localhost:9090`      | Metrics collector            |
| Grafana      | `http://localhost:3000`      | Dashboard (admin/admin)      |

---

### ⚙️ Configuration

#### `application.properties`

```properties
# === LDAP ===
spring.ldap.urls=ldap://localhost:389
spring.ldap.base=dc=test,dc=com
spring.ldap.username=cn=admin,dc=test,dc=com
spring.ldap.password=admin123

# === BATCH ===
# Number of inactivity months before locking
batch.ldap.inactive-months=90
# Size of each partition (LDAP page)
batch.partition.page-size=50
# Number of parallel workers
batch.partition.grid-size=4
# Do not auto-run on startup
spring.batch.job.enabled=false

# === DATASOURCE (Job Repository) ===
spring.datasource.url=jdbc:postgresql://localhost:5432/batchdb
spring.datasource.username=batchuser
spring.datasource.password=batchpass

# === PUSHGATEWAY ===
pushgateway.url=pushgateway:9091
```

#### Environment variables (docker-compose)

| Variable                    | Default  | Description                          |
|-----------------------------|----------|--------------------------------------|
| `SPRING_PROFILES_ACTIVE`    | `docker` | Active Spring profile                |
| `BATCH_SIMULATE_LOCK`       | `false`  | Simulate without actually locking    |

> ⚠️ **Docker networking**: always use service names (`openldap`, `postgres`) instead of `localhost` for inter-container communication.

---

### 📊 Monitoring — Grafana / Prometheus

#### Import the Dashboard

1. Open Grafana → `http://localhost:3000` (admin / admin)
2. **Connections** → **Data Sources** → ensure Prometheus URL is `http://prometheus:9090`
3. **Dashboards** → **New** → **Import**
4. Upload `grafana-dashboard-ldap-batch.json` (available at project root)

#### Exposed Metrics

| Metric                     | Type  | Description                              |
|----------------------------|-------|------------------------------------------|
| `batch_job_status`         | Gauge | Job status (1 = success, 0 = failure)    |
| `batch_items_read_total`   | Gauge | Accounts read from LDAP                  |
| `batch_items_written_total`| Gauge | Accounts locked                          |
| `push_time_seconds`        | Gauge | Timestamp of last successful push        |
| `push_failure_time_seconds`| Gauge | Timestamp of last failed push            |

#### Prometheus configuration (`prometheus.yml`)

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'pushgateway'
    honor_labels: true   # Preserves job="lockInactiveUsersJob" label
    static_configs:
      - targets: ['pushgateway:9091']
```

> ⚠️ `honor_labels: true` is **mandatory** — without it, Prometheus overwrites your metrics' `job` label.

---

### 🛠️ Tech Stack

- **Java 17** / **Spring Batch** / **Spring LDAP**
- **PostgreSQL** — Job Repository storage
- **OpenLDAP** — target directory
- **Docker / Docker Compose**
- **Prometheus** + **Pushgateway** + **Grafana** — observability
- **Lombok** — boilerplate reduction
- **Maven** — build tool

---

### 📄 License

MIT License — see [LICENSE](LICENSE) file.
