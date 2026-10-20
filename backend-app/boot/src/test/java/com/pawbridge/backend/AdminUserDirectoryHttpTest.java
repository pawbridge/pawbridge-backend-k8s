package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.community.internal.post.application.PostPageReader;
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery;
import com.pawbridge.backend.user.internal.directory.web.AdminUserDirectoryController;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=AdminUserDirectoryController.class,excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AdminUserDirectoryController.class,UserProfileExceptionHandler.class,AdminPostReadConfiguration.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AdminUserDirectoryHttpTest {
    private static final String ROOT="/api/v1/admin/users";
    private static final String CACHE="no-cache, no-store, max-age=0, must-revalidate";
    @Autowired MockMvc mvc;
    @MockitoBean UserDirectoryQuery users;
    @MockitoBean ReadAdminPosts posts;
    @MockitoBean ReadPost post;
    @MockitoBean PostPageReader postPages;

    @ParameterizedTest @ValueSource(strings={"","/search"})
    void given__an_admin_and_forged_identity__when__reading_default_pages__then__return_exact_source_page_and_seven_fields_without_password(String suffix)throws Exception {
        var row=new UserProfile(Long.MAX_VALUE,"fixture@example.invalid"," 한글 ",null,"GOOGLE","ROLE_USER",LocalDateTime.parse("2026-10-20T01:02:03.123456"));
        when(users.page(isNull(),isNull(),any())).thenReturn(new UserDirectoryQuery.Slice(List.of(row),41));
        mvc.perform(get(ROOT+suffix).header("Authorization",admin()).header("X-User-Id","999").header("X-User-Role","ROLE_USER"))
                .andExpect(status().isOk()).andExpect(content().json(EXPECTED,JsonCompareMode.STRICT))
                .andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"));
        verify(users).page(null,null,request(0,20));verifyNoMoreInteractions(users);assertCleared();
    }
    @Test void given__raw_keyword_role_and_repeated_sorts__when__searching__then__bind_exact_values_and_source_sort_order()throws Exception {
        when(users.page(any(),any(),any())).thenReturn(new UserDirectoryQuery.Slice(List.of(),0));
        mvc.perform(get(ROOT+"/search").param("keyword"," %_\\한글 ").param("role","ROLE_SHELTER")
                .param("sort","name,email,desc,ignorecase","userId,asc").header("Authorization",admin())).andExpect(status().isOk());
        verify(users).page(" %_\\한글 ","ROLE_SHELTER",new UserDirectoryQuery.Request(0,20,List.of(
                new UserDirectoryQuery.Order("name",false,true),new UserDirectoryQuery.Order("email",false,true),new UserDirectoryQuery.Order("userId",true,false))));
    }
    @ParameterizedTest @CsvSource({"bad,zero,0,20","2147483647,3000,2147483647,2000"})
    void given__malformed_or_large_paging__when__binding__then__retain_existing_fallback_and_resource_cap(String page,String size,int number,int cap)throws Exception {
        when(users.page(any(),any(),any())).thenReturn(new UserDirectoryQuery.Slice(List.of(),0));
        mvc.perform(get(ROOT).param("page",page).param("size",size).header("Authorization",admin())).andExpect(status().isOk());
        verify(users).page(null,null,request(number,cap));
    }
    @ParameterizedTest @ValueSource(strings={"role_user","UNKNOWN"})
    void given__invalid_role__when__an_admin_binds_search__then__retain_original_runtime_500_without_query(String role)throws Exception {
        mvc.perform(get(ROOT+"/search").param("role",role).header("Authorization",admin()))
                .andExpect(status().isInternalServerError()).andExpect(content().json(ERROR,JsonCompareMode.STRICT));verifyNoInteractions(users);
    }
    @ParameterizedTest @ValueSource(strings={"missing","invalid"})
    void given__missing_or_invalid_bearer__when__search_role_is_invalid__then__authenticate_before_binding(String mode)throws Exception {
        var request=get(ROOT+"/search").param("role","UNKNOWN").header("X-User-Role","ROLE_ADMIN");
        if(mode.equals("invalid"))request.header("Authorization","Bearer invalid");
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));verifyNoInteractions(users);assertCleared();
    }
    @ParameterizedTest @ValueSource(strings={"","/search"})
    void given__a_user_and_forged_admin_header__when__reading__then__deny_before_the_query(String suffix)throws Exception {
        mvc.perform(get(ROOT+suffix).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")).header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"code\":403,\"message\":\"관리자 권한이 필요합니다.\",\"data\":null}",JsonCompareMode.STRICT));
        verifyNoInteractions(users);assertCleared();
    }
    @ParameterizedTest @ValueSource(strings={"storage","commit","unsupported-sort"})
    void given__query_commit_or_known_sort_rejection__when__listing__then__reuse_safe_source_generic_500(String phase)throws Exception {
        RuntimeException failure=switch(phase){case "storage"->new org.springframework.dao.DataAccessResourceFailureException("synthetic-private-password");
            case "commit"->new org.springframework.transaction.TransactionSystemException("synthetic-private-password");
            default->new IllegalArgumentException("synthetic-private-password");};
        when(users.page(any(),any(),any())).thenThrow(failure);
        mvc.perform(get(ROOT).param("sort",phase.equals("unsupported-sort")?"password,asc":"createdAt,desc").header("Authorization",admin()))
                .andExpect(status().isInternalServerError()).andExpect(content().json(ERROR,JsonCompareMode.STRICT))
                .andExpect(header().string("Cache-Control",CACHE)).andExpect(header().doesNotExist("Set-Cookie"));assertCleared();
    }
    @Test void given__neighboring_paths_or_write_methods__when__an_admin_requests_them__then__keep_default_deny()throws Exception {
        mvc.perform(get(ROOT+"/search/extra").header("Authorization",admin())).andExpect(status().isForbidden());
        mvc.perform(post(ROOT).header("Authorization",admin())).andExpect(status().isForbidden());verifyNoInteractions(users);
    }
    private static void assertCleared(){assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();}
    private static String admin(){return "Bearer "+F3TestInputs.token(7,"ROLE_ADMIN");}
    private static UserDirectoryQuery.Request request(int page,int size){return new UserDirectoryQuery.Request(page,size,List.of(new UserDirectoryQuery.Order("createdAt",false,false)));}
    private static final String ERROR="{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}";
    private static final String EXPECTED="""
        {"code":200,"message":"요청이 성공적으로 처리되었습니다.","data":{
          "content":[{"userId":9223372036854775807,"email":"fixture@example.invalid","name":" 한글 ","nickname":null,"provider":"GOOGLE","role":"ROLE_USER","createdAt":"2026-10-20T01:02:03.123456"}],
          "pageable":{"pageNumber":0,"pageSize":20,"sort":{"sorted":true,"unsorted":false,"empty":false},"offset":0,"paged":true,"unpaged":false},
          "last":false,"totalElements":41,"totalPages":3,"numberOfElements":1,"first":true,"size":20,"number":0,
          "sort":{"sorted":true,"unsorted":false,"empty":false},"empty":false}}
        """;
}
