package com.batch.ldapPaginatiobatch.job.ldapImport;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.exporter.PushGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class LdapJobExecutionListener implements JobExecutionListener   {
    private LocalDateTime startTime;
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String PUSHGATEWAY_URL = "pushgateway:9091";

    @Override
    public void beforeJob(JobExecution jobExecution) {
        startTime = LocalDateTime.now();

        log.info("=".repeat(70));
        log.info("DÉMARRAGE DU JOB BATCH");
        log.info("=".repeat(70));
        log.info("Job: {}", jobExecution.getJobInstance().getJobName());
        log.info("Démarrage à: {}", startTime.format(TIME_FORMATTER));
        log.info("Paramètres: {}", jobExecution.getJobParameters());
        log.info("=".repeat(70));
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        LocalDateTime endTime = LocalDateTime.now();
        Duration duration = Duration.between(startTime, endTime);

        log.info("=".repeat(70));
        log.info("FIN DU JOB BATCH");
        log.info("=".repeat(70));
        log.info("Job: {}", jobExecution.getJobInstance().getJobName());
        log.info("Status: {}", jobExecution.getStatus());
        log.info("Démarrage: {}", startTime.format(TIME_FORMATTER));
        log.info("Fin: {}", endTime.format(TIME_FORMATTER));
        log.info("Durée: {} secondes", duration.getSeconds());

        // Afficher les statistiques par step
        logJobStatistics(jobExecution);

        // ENVOI PROMETHEUS (Dashboard Simple)
        sendMetricsToPushgateway(jobExecution);

        // Log détaillé en cas d'échec
        if (jobExecution.getStatus() == BatchStatus.FAILED) {
            logFailureDetails(jobExecution);
        }

        log.info("=".repeat(70));
    }

    private void sendMetricsToPushgateway(JobExecution jobExecution) {
        try {
            CollectorRegistry registry = new CollectorRegistry();

            // 1. Définition des métriques
            Gauge jobStatus = Gauge.build()
                    .name("batch_job_status")
                    .help("Status du job (1=OK, 0=Fail)")
                    .labelNames("job_name")
                    .register(registry);

            Gauge itemsRead = Gauge.build()
                    .name("batch_items_read_total")
                    .help("Total items lus")
                    .register(registry);

            Gauge itemsWritten = Gauge.build()
                    .name("batch_items_written_total")
                    .help("Total items écrits")
                    .register(registry);

            // 2. Calcul des totaux
            long totalRead = 0;
            long totalWritten = 0;
            for (StepExecution step : jobExecution.getStepExecutions()) {
                if (step.getStepName().contains("partition_")) {
                    totalRead += step.getReadCount();
                    totalWritten += step.getWriteCount();
                }
            }

            // 3. Affectation des valeurs
            jobStatus.set(jobExecution.getStatus() == BatchStatus.COMPLETED ? 1 : 0);
            itemsRead.set(totalRead);
            itemsWritten.set(totalWritten);

            // 4. Pousser vers le Pushgateway
            PushGateway pg = new PushGateway(PUSHGATEWAY_URL);
            // On utilise push() pour écraser les anciennes valeurs du job précédent
            pg.push(registry, jobExecution.getJobInstance().getJobName());

            log.info("MÉTRIQUES ENVOYÉES AU PUSHGATEWAY : {} lus, {} écrits", totalRead, totalWritten);

        } catch (IOException e) {
            log.error("Impossible d'envoyer les métriques au Pushgateway : {}", e.getMessage());
        }
    }

    private void logJobStatistics(JobExecution jobExecution) {
        log.info("-".repeat(50));
        log.info("STATISTIQUES PAR STEP:");

        for (StepExecution step : jobExecution.getStepExecutions()) {
            log.info("Step: {}", step.getStepName());
            log.info("  Lues: {}", step.getReadCount());
            log.info("  Écrites: {}", step.getWriteCount());
            log.info("  Skippées: {}", step.getSkipCount());
            log.info("  Échecs: {}", step.getFailureExceptions().size());

            if (step.getSkipCount() > 0) {
                log.warn("  ⚠ {} éléments skippés", step.getSkipCount());
            }

            if (!step.getFailureExceptions().isEmpty()) {
                log.error("  ✗ {} échecs", step.getFailureExceptions().size());
            }
        }
        log.info("-".repeat(50));
    }

    private void logFailureDetails(JobExecution jobExecution) {
        log.error("DÉTAILS DES ÉCHECS:");

        for (Throwable exception : jobExecution.getAllFailureExceptions()) {
            log.error("Exception: {}", exception.getMessage());
            if (exception.getCause() != null) {
                log.error("Cause: {}", exception.getCause().getMessage());
            }
        }

        // Loguer les exceptions par step
        for (StepExecution step : jobExecution.getStepExecutions()) {
            if (!step.getFailureExceptions().isEmpty()) {
                log.error("Échecs dans step '{}':", step.getStepName());
                for (Throwable ex : step.getFailureExceptions()) {
                    log.error("  - {}", ex.getMessage());
                }
            }
        }
    }
}
