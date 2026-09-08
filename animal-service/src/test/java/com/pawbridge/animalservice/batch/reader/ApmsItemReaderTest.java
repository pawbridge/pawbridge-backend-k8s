package com.pawbridge.animalservice.batch.reader;

import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApmsItemReaderTest {
    @Mock private ApmsApiClient api;
    private ApmsItemReader reader;

    @BeforeEach
    void setUp() {
        reader = new ApmsItemReader(api);
        ReflectionTestUtils.setField(reader, "serviceKey", "test-key");
        reader.beforeStep(null);
    }

    @Test
    void givenTransportFailure__whenRead__thenFailWithoutLeakingRequest() {
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json")))
                .thenThrow(new IllegalStateException("request contains private-service-key-placeholder"));
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("private-service-key-placeholder").hasNoCause();
    }

    @Test
    void givenMissingResponse__whenRead__thenFailInsteadOfEndOfInput() {
        stubPage(null);
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenProviderError__whenRead__thenFailInsteadOfEndOfInput() {
        var response = page("0", List.of());
        response.getResponse().getHeader().setResultCode("30");
        stubPage(response);
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenSuccessfulZeroCount__whenRead__thenAcceptEmptyResult() {
        stubPage(page("0", null));
        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();
        verifySinglePage();
    }

    @Test
    void givenPositiveCountAndMissingItems__whenRead__thenFail() {
        stubPage(page("3", null));
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenPositiveCountAndEmptyItems__whenRead__thenFail() {
        stubPage(page("3", List.of()));
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenTruncatedPage__whenRead__thenFail() {
        stubPage(page("3", List.of(new ApmsAnimal())));
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenLastPage__whenReadAgain__thenStopWithoutAnotherApiCall() {
        var animal = new ApmsAnimal();
        stubPage(page("1", List.of(animal)));
        assertThat(reader.read()).isSameAs(animal);
        assertThat(reader.read()).isNull();
        verifySinglePage();
    }

    private void stubPage(ApmsRootResponse<ApmsAnimal> response) {
        when(api.getAbandonmentAnimals(anyString(), anyInt(), anyInt(), anyString(), anyString(),
                isNull(), isNull(), eq("json"))).thenReturn(response);
    }

    private void verifySinglePage() {
        verify(api).getAbandonmentAnimals(anyString(), eq(1), eq(1000), anyString(), anyString(),
                isNull(), isNull(), eq("json"));
        verifyNoMoreInteractions(api);
    }

    private ApmsRootResponse<ApmsAnimal> page(String total, List<ApmsAnimal> animals) {
        return new ApmsRootResponse<>(new ApmsResponse<>(
                ApmsHeader.builder().resultCode("00").build(),
                new ApmsBody<>(new ApmsItems<>(animals), "1000", "1", total)));
    }
}
