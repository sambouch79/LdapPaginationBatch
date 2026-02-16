package com.batch.ldapPaginatiobatch.config;

import com.batch.ldapPaginatiobatch.job.ldapImport.LdapJobExecutionListener;
import com.batch.ldapPaginatiobatch.job.ldapImport.LdapLetterPartitioner;
import com.batch.ldapPaginatiobatch.model.LdapUser;
import com.batch.ldapPaginatiobatch.service.LdapUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;


@Configuration
@Slf4j
@RequiredArgsConstructor
public class LdapJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final LdapJobExecutionListener jobExecutionListener;
    private final LdapUserService ldapUserService;
    @Value("${batch.ldap.inactive-months:6}")
    private int inactivate_month;


    @Bean
    public Job lockInactiveUsersJob(Step masterStep) {
        return new JobBuilder("lockInactiveUsersJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobExecutionListener)
                .start(masterStep)
                .build();
    }

    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4); // 4 threads travaillant en même temps
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("ldap-batch-thread-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Step masterStep(Partitioner partitioner, Step workerStep, TaskExecutor taskExecutor) {
        return new StepBuilder("masterStep", jobRepository)
                .partitioner("workerStep", partitioner) // Utilise le bean partitioner
                .step(workerStep)
                .gridSize(4)
                .taskExecutor(taskExecutor)
                .build();
    }

    @Bean
    public Step workerStep(ItemReader<LdapUser> ldapReader,
                           ItemProcessor<LdapUser, LdapUser> ldapUserProcessor,
                           ItemWriter<LdapUser> ldapUserWriter) {
        return new StepBuilder("workerStep", jobRepository)
                .<LdapUser, LdapUser>chunk(50)
                .reader(ldapReader)
                .processor(ldapUserProcessor)
                .writer(ldapUserWriter)
                .faultTolerant()
                .retryLimit(3)
                .retry(org.springframework.ldap.NamingException.class)
                .skipLimit(100)
                .build();
    }
    @Bean
    @StepScope
    public ItemReader<LdapUser> ldapReader(@Value("#{stepExecutionContext['nameGroup']}") String nameGroup) {
        if (ldapUserService == null) {
            throw new IllegalStateException("LdapUserService n'a pas été injecté dans la config !");
        }

        log.info("Initialisation du Reader pour le groupe : {}", nameGroup);
        List<LdapUser> users = ldapUserService.findInactiveUsersByNameGroup(nameGroup, inactivate_month);
        return new ListItemReader<>(users);
    }

    @Bean
    public Partitioner partitioner() {
        return new LdapLetterPartitioner();
    }


}