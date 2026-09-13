package com.pawbridge.animalservice.shelter;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class ShelterDirectoryCollector {
    // Same lock as ApmsBatchRunner: preserves the existing animal fallback insert contract.
    private static final String LOCK = "pawbridge_animal.apmsAnimalSyncJob";
    private final DataSource source;
    private final ShelterDirectoryClient client;
    private final ShelterDirectoryStore store;
    public ShelterDirectoryCollector(DataSource source, ShelterDirectoryClient client, ShelterDirectoryStore store) {
        this.source=source; this.client=client; this.store=store;
    }
    public int collect() throws Exception {
        try (var connection=source.getConnection()) {
            boolean acquired;
            try { acquired=lock(connection,"SELECT GET_LOCK(?,0)"); }
            catch (Exception exception) { connection.abort(Runnable::run); throw exception; }
            if (!acquired) throw new IllegalStateException("SHELTER_DIRECTORY_BUSY");
            try {
                var rows=client.collect();
                connection.setAutoCommit(false);
                try {
                    store.save(connection,rows,LocalDateTime.now(ZoneOffset.UTC));
                    connection.commit();
                    connection.setAutoCommit(true);
                    return rows.size();
                } catch (Exception exception) {
                    try { connection.rollback(); connection.setAutoCommit(true); }
                    catch (Exception rollbackFailure) { connection.abort(Runnable::run); }
                    throw exception;
                }
            } finally {
                try {
                    if (!lock(connection,"SELECT RELEASE_LOCK(?)")) connection.abort(Runnable::run);
                } catch (Exception exception) { connection.abort(Runnable::run); }
            }
        }
    }
    private boolean lock(Connection connection,String sql) throws Exception {
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,LOCK); statement.setQueryTimeout(5);
            try(var rs=statement.executeQuery()) { return rs.next() && rs.getInt(1)==1; }
        }
    }
}
