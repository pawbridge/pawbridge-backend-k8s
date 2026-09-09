package com.pawbridge.animalservice.batch.tasklet;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.*;
import com.pawbridge.animalservice.repository.ShelterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShelterPrepTaskletTest {
    @Mock private ApmsApiClient api;
    @Mock private ShelterRepository repository;
    private ShelterPrepTasklet tasklet;

    @BeforeEach
    void setUp() {
        tasklet = new ShelterPrepTasklet(api, repository);
        ReflectionTestUtils.setField(tasklet, "serviceKey", "test-key");
    }

    @Test
    void givenProviderError__whenPrepare__thenFailBeforeDatabaseAccess() {
        stubPage("30", "0");
        assertThatThrownBy(() -> tasklet.execute(null, null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void givenMissingPositiveCountItems__whenPrepare__thenFailBeforeDatabaseAccess() {
        stubPage("00", "2");
        assertThatThrownBy(() -> tasklet.execute(null, null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void givenSuccessfulZeroCount__whenPrepare__thenCompleteWithoutWriting() {
        stubPage("00", "0");
        when(repository.findByCareRegNoIn(List.of())).thenReturn(List.of());
        assertThat(tasklet.execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
        verify(repository, never()).saveAll(any());
    }

    private void stubPage(String code, String total) {
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"))).thenReturn(new ApmsRootResponse<>(new ApmsResponse<>(
                ApmsHeader.builder().resultCode(code).build(), new ApmsBody<>(null, "1000", "1", total))));
    }
}
