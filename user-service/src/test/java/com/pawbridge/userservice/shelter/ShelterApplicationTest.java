package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.exception.common.ApplicationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShelterApplicationTest {
    @Test void requestTrimsNameAndStartsPending() {
        var a = ShelterApplication.request(10L, "  서울 보호소  ");
        assertThat(a.getShelterName()).isEqualTo("서울 보호소");
        assertThat(a.getStatus()).isEqualTo(ShelterApplicationStatus.PENDING);
        assertThat(a.getReviewedBy()).isNull();
    }
    @Test void blankOrTooLongNameIsRejected() {
        assertThatThrownBy(() -> ShelterApplication.request(10L, "  ")).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> ShelterApplication.request(10L, "가".repeat(101))).isInstanceOf(ApplicationException.class);
    }
    @Test void approvePreservesApplicantAndRecordsDecision() {
        var a = ShelterApplication.request(10L, "보호소");
        a.approve(1L, " 123 ", " 소속 확인 ");
        assertThat(a.getUserId()).isEqualTo(10L);
        assertThat(a.getStatus()).isEqualTo(ShelterApplicationStatus.APPROVED);
        assertThat(a.getCareRegNo()).isEqualTo("123");
        assertThat(a.getReviewNote()).isEqualTo("소속 확인");
        assertThat(a.getReviewedBy()).isEqualTo(1L);
        assertThat(a.getReviewedAt()).isNotNull();
        assertThat(ShelterApplicationResponse.from(a).reason()).isNull();
    }
    @Test void rejectRecordsReasonWithoutShelterGrant() {
        var a = ShelterApplication.request(10L, "보호소");
        a.reject(1L, "담당자 확인 불가");
        assertThat(a.getStatus()).isEqualTo(ShelterApplicationStatus.REJECTED);
        assertThat(a.getCareRegNo()).isNull();
        assertThat(ShelterApplicationResponse.from(a).reason()).isEqualTo("담당자 확인 불가");
    }
    @Test void blankNoteDoesNotPartiallyApprove() {
        var a = ShelterApplication.request(10L, "보호소");
        assertThatThrownBy(() -> a.approve(1L, "123", " ")).isInstanceOf(ApplicationException.class);
        assertThat(a.getStatus()).isEqualTo(ShelterApplicationStatus.PENDING);
        assertThat(a.getCareRegNo()).isNull();
    }
    @Test void decisionCannotBeReplaced() {
        var a = ShelterApplication.request(10L, "보호소");
        a.reject(1L, "확인 불가");
        assertThatThrownBy(() -> a.approve(2L, "123", "확인")).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> a.reject(2L, "다른 사유")).isInstanceOf(ApplicationException.class);
        assertThat(a.getReviewedBy()).isEqualTo(1L);
    }
}
