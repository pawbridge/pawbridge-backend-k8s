package com.pawbridge.backend.user.internal.signup.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import com.pawbridge.backend.user.internal.password.application.PasswordHashes;
import org.junit.jupiter.api.Test;

class LocalSignupTest {
    private final EmailVerifications verifications = mock(EmailVerifications.class);
    private final SignupAccounts accounts = mock(SignupAccounts.class);
    private final PasswordHashes hashes = mock(PasswordHashes.class);
    private final LocalSignup signup = new LocalSignup(verifications, accounts, hashes);

    @Test
    void given__verified_new_user__when__signing_up__then__checks_run_in_original_order_and_verification_is_cleared() {
        when(verifications.isVerified("a@b.c")).thenReturn(true);
        when(hashes.encode("pass12")).thenReturn("ENC");
        var saved = new SignedUp(7, "a@b.c", "Kim", "아주귀여운강아지");
        when(accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)).thenReturn(saved);

        assertEquals(saved, signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null));

        var order = inOrder(verifications, accounts, hashes);
        order.verify(verifications).isVerified("a@b.c");
        order.verify(accounts).existsLocalEmail("a@b.c");
        order.verify(hashes).encode("pass12");
        order.verify(accounts).createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null);
        order.verify(verifications).clearVerification("a@b.c");
        verifyNoMoreInteractions(verifications, accounts, hashes);
    }

    @Test
    void given__unverified_email__when__signing_up__then__nothing_else_runs() {
        assertThrows(EmailNotVerifiedException.class,
                () -> signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null));
        verifyNoInteractions(accounts, hashes);
    }

    @Test
    void given__duplicate_mismatch_or_forbidden_role__when__signing_up__then__original_exception_and_no_insert() {
        when(verifications.isVerified("a@b.c")).thenReturn(true);
        when(accounts.existsLocalEmail("dup@b.c")).thenReturn(false);
        when(verifications.isVerified("dup@b.c")).thenReturn(true);
        when(accounts.existsLocalEmail("a@b.c")).thenReturn(true);

        assertThrows(EmailDuplicateException.class,
                () -> signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null));
        assertThrows(InconsistentPasswordException.class,
                () -> signup.signUp("dup@b.c", "Kim", "pass12", "other1", SignupRole.ROLE_USER, null));
        assertThrows(InconsistentPasswordException.class,
                () -> signup.signUp("dup@b.c", "Kim", "pass12", null, SignupRole.ROLE_USER, null));
        assertThrows(RoleRequiredException.class,
                () -> signup.signUp("dup@b.c", "Kim", "pass12", "pass12", null, null));
        assertThrows(AdminRoleNotAllowedException.class,
                () -> signup.signUp("dup@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_ADMIN, null));
        assertThrows(ShelterApprovalRequiredException.class,
                () -> signup.signUp("dup@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_SHELTER, null));
        assertThrows(ShelterApprovalRequiredException.class,
                () -> signup.signUp("dup@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, "123"));
        verify(accounts, org.mockito.Mockito.never()).createLocalUser(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void given__blank_care_registration_number__when__signing_up__then__still_a_plain_user() {
        when(verifications.isVerified("a@b.c")).thenReturn(true);
        when(hashes.encode("pass12")).thenReturn("ENC");

        signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, "  ");

        verify(accounts).createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, "  ");
    }

    @Test
    void given__failed_verification_cleanup__when__signing_up__then__account_is_still_created() {
        when(verifications.isVerified("a@b.c")).thenReturn(true);
        when(hashes.encode("pass12")).thenReturn("ENC");
        var saved = new SignedUp(7, "a@b.c", "Kim", "n");
        when(accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)).thenReturn(saved);
        doThrow(new IllegalStateException("redis down")).when(verifications).clearVerification("a@b.c");

        assertEquals(saved, signup.signUp("a@b.c", "Kim", "pass12", "pass12", SignupRole.ROLE_USER, null));
    }
}
