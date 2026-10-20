package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.boot.configuration.AdminPostReadConfiguration;
import com.pawbridge.backend.community.internal.post.application.*;
import com.pawbridge.backend.community.internal.post.web.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers=AdminPostReadController.class,excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={AdminPostReadConfiguration.class,ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class AdminPostReadHttpTest {
    static final String ROOT="/api/v1/admin/posts";
    @Autowired MockMvc mvc;
    @MockitoBean ReadAdminPosts posts;
    @MockitoBean ReadPost post;
    @MockitoBean PostPageReader pageReader;
    @Test void given__default_admin_page__when__reading__then__match_original_page_json_and_ignore_forged_identity_headers()throws Exception{
        when(posts.read(any())).thenReturn(new ReadAdminPosts.Page(List.of(row()),41));
        mvc.perform(get(ROOT).header("Authorization",admin()).header("X-User-Role","ROLE_USER").header("X-User-Id","999"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json(EXPECTED_DEFAULT,JsonCompareMode.STRICT));
        verify(posts).read(new PostPageReader.Request(0,20,List.of(new PostPageReader.Order("createdAt",false,false))));verifyNoInteractions(post);
    }
    static Stream<Arguments> paging(){return Stream.of(Arguments.of("-1","0",0,20),Arguments.of("garbage","oops",0,20),Arguments.of("2147483647","3000",Integer.MAX_VALUE,2000),Arguments.of("2147483648","999999999999",0,20));}
    @ParameterizedTest @MethodSource("paging")
    void given__invalid_or_large_page_parameters__when__binding__then__reuse_original_fallback_and_maximum(String page,String size,int expectedPage,int expectedSize)throws Exception{
        when(posts.read(any())).thenReturn(new ReadAdminPosts.Page(List.of(),0));
        mvc.perform(get(ROOT).param("page",page).param("size",size).header("Authorization",admin())).andExpect(status().isOk());
        verify(posts).read(new PostPageReader.Request(expectedPage,expectedSize,List.of(new PostPageReader.Order("createdAt",false,false))));
    }
    @Test void given__repeated_sort_with_ignorecase__when__binding__then__retain_original_order_list_without_added_tiebreak()throws Exception{
        when(posts.read(any())).thenReturn(new ReadAdminPosts.Page(List.of(),0));
        mvc.perform(get(ROOT).param("sort","title,content,desc,ignorecase","postId,asc").header("Authorization",admin())).andExpect(status().isOk());
        verify(posts).read(new PostPageReader.Request(0,20,List.of(new PostPageReader.Order("title",false,true),new PostPageReader.Order("content",false,true),new PostPageReader.Order("postId",true,false))));
    }
    @Test void given__admin_detail__when__reading__then__reuse_exact_nine_fields_and_null_media_without_new_query()throws Exception{
        var row=new PostDetails(7,9,null,"title","content","COMMUNICATION",null,null,null);when(post.read(7)).thenReturn(row);
        mvc.perform(get(ROOT+"/7").header("Authorization",admin())).andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"message\":\"요청이 성공적으로 처리되었습니다.\",\"data\":{\"postId\":7,\"authorId\":9,\"authorNickname\":null,\"title\":\"title\",\"content\":\"content\",\"boardType\":\"COMMUNICATION\",\"imageUrls\":null,\"createdAt\":null,\"updatedAt\":null}}",JsonCompareMode.STRICT));verify(post).read(7);verifyNoInteractions(posts);
    }
    @ParameterizedTest @ValueSource(longs={-1,0,7})
    void given__missing_or_deleted_numeric_post__when__reading__then__retain_original_404_without_positive_id_guard(long id)throws Exception{
        when(post.read(id)).thenThrow(new PostNotFoundException());mvc.perform(get(ROOT+"/"+id).header("Authorization",admin())).andExpect(status().isNotFound()).andExpect(content().json("{\"code\":404,\"message\":\"게시글을 찾을 수 없습니다.\",\"data\":null}",JsonCompareMode.STRICT));verify(post).read(id);verifyNoInteractions(posts);
    }
    @ParameterizedTest @ValueSource(strings={"no-number","9223372036854775808","read","search"})
    void given__malformed_or_overflow_id__when__admin_binds__then__retain_measured_original_500_without_service_call(String id)throws Exception{
        mvc.perform(get(ROOT+"/"+id).header("Authorization",admin())).andExpect(status().isInternalServerError()).andExpect(content().json(ERROR,JsonCompareMode.STRICT));verifyNoInteractions(posts,post);
    }
    @ParameterizedTest @ValueSource(strings={"","/7"})
    void given__user_token_and_admin_header__when__requesting__then__deny_before_binding_or_query(String suffix)throws Exception{
        mvc.perform(get(ROOT+suffix).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER")).header("X-User-Role","ROLE_ADMIN")).andExpect(status().isForbidden());verifyNoInteractions(posts,post);
    }
    @ParameterizedTest @ValueSource(strings={"missing","invalid"})
    void given__missing_or_invalid_token__when__requesting_bad_id__then__authenticate_before_binding(String mode)throws Exception{
        var request=get(ROOT+"/no-number").header("X-User-Role","ROLE_ADMIN");if(mode.equals("invalid"))request.header("Authorization","Bearer invalid");mvc.perform(request).andExpect(status().isUnauthorized());verifyNoInteractions(posts,post);
    }
    @ParameterizedTest @ValueSource(strings={"read","search"})
    void given__ported_id_mapping_literals__when__requested_without_bearer__then__authenticate_before_the_original_500_binding(String path)throws Exception {
        mvc.perform(get(ROOT+"/"+path).header("X-User-Role","ROLE_ADMIN")).andExpect(status().isUnauthorized());verifyNoInteractions(posts,post);
    }
    @ParameterizedTest @ValueSource(strings={"list","detail"})
    void given__query_or_commit_runtime_failure__when__reading__then__return_safe_original_500(String mode)throws Exception{
        var failure=new org.springframework.transaction.TransactionSystemException("private synthetic details");if(mode.equals("list"))when(posts.read(any())).thenThrow(failure);else when(post.read(7)).thenThrow(failure);
        mvc.perform(get(ROOT+(mode.equals("list")?"":"/7")).header("Authorization",admin())).andExpect(status().isInternalServerError()).andExpect(content().json(ERROR,JsonCompareMode.STRICT));
    }
    @Test void given__admin_and_deeper_or_unported_mutations__when__requesting__then__retain_default_deny()throws Exception{
        mvc.perform(delete(ROOT+"/7/extra").header("Authorization",admin())).andExpect(status().isForbidden());mvc.perform(put(ROOT+"/7/extra").header("Authorization",admin())).andExpect(status().isForbidden());mvc.perform(post(ROOT).header("Authorization",admin())).andExpect(status().isForbidden());verifyNoInteractions(posts,post);
    }
    static PostDetails row(){return new PostDetails(7,9,"synthetic","title","content","COMMUNICATION",List.of(),null,null);}
    static String admin(){return "Bearer "+F3TestInputs.token(7,"ROLE_ADMIN");}
    static final String ERROR="{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}";
    static final String EXPECTED_DEFAULT="""
{
  "code": 200,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "postId": 7,
        "authorId": 9,
        "authorNickname": "synthetic",
        "title": "title",
        "content": "content",
        "boardType": "COMMUNICATION",
        "imageUrls": [],
        "createdAt": null,
        "updatedAt": null
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": {
        "sorted": true,
        "unsorted": false,
        "empty": false
      },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "last": false,
    "totalElements": 41,
    "totalPages": 3,
    "numberOfElements": 1,
    "first": true,
    "size": 20,
    "number": 0,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "empty": false
  }
}
    """;
}
