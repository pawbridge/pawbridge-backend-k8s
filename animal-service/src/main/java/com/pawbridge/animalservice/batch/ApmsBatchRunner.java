package com.pawbridge.animalservice.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Serializes requests across application instances using a dedicated MySQL session. */
@Component
public class ApmsBatchRunner {
    private static final String LOCK_NAME = "pawbridge_animal.apmsAnimalSyncJob";
    private final DataSource dataSource;
    private final JobExplorer jobExplorer;
    private final JobLauncher jobLauncher;
    private final Job apmsAnimalSyncJob;
    private final ApmsSyncPlanFactory planFactory;

    public ApmsBatchRunner(DataSource dataSource, JobExplorer jobExplorer,
                           @Qualifier("apmsJobLauncher") JobLauncher jobLauncher,
                           Job apmsAnimalSyncJob, ApmsSyncPlanFactory planFactory) {
        this.dataSource = dataSource;
        this.jobExplorer = jobExplorer;
        this.jobLauncher = jobLauncher;
        this.apmsAnimalSyncJob = apmsAnimalSyncJob;
        this.planFactory = planFactory;
    }

    public JobExecution run() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Integer acquired;
            try {
                acquired = lockQuery(connection, "SELECT GET_LOCK(?, 0)");
            } catch (SQLException exception) {
                discard(connection);
                throw new UnavailableException();
            }
            if (acquired == null || (acquired != 0 && acquired != 1)) {
                discard(connection);
                throw new UnavailableException();
            }
            if (acquired == 0) {
                throw new AlreadyRunningException();
            }
            try {
                // A lost lock session must not silently restart an orphaned execution.
                boolean running;
                try {
                    running = !jobExplorer.findRunningJobExecutions(apmsAnimalSyncJob.getName()).isEmpty();
                } catch (RuntimeException exception) {
                    throw new UnavailableException();
                }
                if (running) {
                    throw new UnavailableException();
                }
                // The explicitly qualified APMS launcher holds this thread until completion.
                return jobLauncher.run(apmsAnimalSyncJob, new JobParametersBuilder(planFactory.create(apmsAnimalSyncJob.getName()).parameters())
                        .addString("requestId", UUID.randomUUID().toString()).toJobParameters());
            } finally {
                release(connection);
            }
        } catch (SQLException exception) {
            throw new UnavailableException();
        }
    }

    private Integer lockQuery(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, LOCK_NAME);
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Number value = (Number) result.getObject(1);
                return value == null ? null : value.intValue();
            }
        }
    }

    private void release(Connection connection) {
        try {
            if (Integer.valueOf(1).equals(lockQuery(connection, "SELECT RELEASE_LOCK(?)"))) {
                return;
            }
        } catch (SQLException exception) {
            // Never return a connection with an uncertain named lock to the pool.
        }
        discard(connection);
        throw new UnavailableException();
    }

    private void discard(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException exception) {
            throw new UnavailableException();
        }
    }

    public static class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException() {
            super("APMS batch is already running");
        }
    }

    public static class UnavailableException extends RuntimeException {
        public UnavailableException() {
            super("APMS execution guard is unavailable; check running job metadata");
        }
    }
}
