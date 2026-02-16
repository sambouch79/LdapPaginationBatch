-- Activer les extensions utiles
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Créer le schéma batch
CREATE SCHEMA IF NOT EXISTS batch;

-- Définir le schéma par défaut
SET search_path TO batch, public;

-- Les tables Spring Batch seront créées automatiquement
-- mais on peut ajouter des tables custom pour le monitoring

-- Table pour tracer les exécutions de batch
CREATE TABLE IF NOT EXISTS batch_audit (
    id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    job_name VARCHAR(255) NOT NULL,
    job_execution_id BIGINT,
    start_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(50),
    total_users INTEGER DEFAULT 0,
    locked_users INTEGER DEFAULT 0,
    skipped_users INTEGER DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index pour performances
CREATE INDEX idx_batch_audit_job_name ON batch_audit(job_name);
CREATE INDEX idx_batch_audit_start_time ON batch_audit(start_time);