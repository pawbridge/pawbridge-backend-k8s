package com.pawbridge.animalservice.search;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.*;

class PostgresqlSearchProjectionScheduleTest {
    @Test
    void maintenance_runs_only_in_enabled_web_processes() {
        new ApplicationContextRunner()
                .withUserConfiguration(PostgresqlSearchProjectionSchedule.class)
                .withBean(PostgresqlSearchProjector.class,()->mock(PostgresqlSearchProjector.class))
                .withPropertyValues("pawbridge.animal-query.projection-schedule-enabled=true")
                .run(context->assertThat(context).doesNotHaveBean(PostgresqlSearchProjectionSchedule.class));
        WebApplicationContextRunner web=new WebApplicationContextRunner()
                .withUserConfiguration(PostgresqlSearchProjectionSchedule.class)
                .withBean(PostgresqlSearchProjector.class,()->mock(PostgresqlSearchProjector.class));
        web.withPropertyValues("pawbridge.animal-query.projection-schedule-enabled=true")
                .run(context->assertThat(context).hasSingleBean(PostgresqlSearchProjectionSchedule.class));
        web.withPropertyValues("pawbridge.animal-query.projection-schedule-enabled=false")
                .run(context->assertThat(context).doesNotHaveBean(PostgresqlSearchProjectionSchedule.class));
    }

    @Test
    void frequent_refresh_only_processes_marked_rows() {
        PostgresqlSearchProjector projector=mock(PostgresqlSearchProjector.class);
        new PostgresqlSearchProjectionSchedule(projector,50).refresh();

        verify(projector).refreshDirtyShelters(50);
        verify(projector).refreshDirtyAnimals(50);
        verifyNoMoreInteractions(projector);
    }

    @Test
    void separate_audit_checks_both_document_types() {
        PostgresqlSearchProjector projector=mock(PostgresqlSearchProjector.class);
        new PostgresqlSearchProjectionSchedule(projector,50).audit();

        verify(projector).refreshShelters(50);
        verify(projector).refreshAnimals(50);
        verifyNoMoreInteractions(projector);
    }
}
