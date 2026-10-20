package com.pawbridge.backend.community.internal.post.application;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReadAdminPostsTest {
    @Test void given__ordered_page__when__enriching__then__reuse_detail_nickname_null_fallback_and_preserve_page_total_without_extra_post_queries() {
        var order=new PostPageReader.Order("createdAt",false,false);
        var request=new PostPageReader.Request(1,2,List.of(order));
        var rows=List.of(post(1,7),post(2,8));
        var calls=new ArrayList<Long>();
        var details=new ReadPost(id->{throw new AssertionError("No per-row post query");},id->{calls.add(id);return id==7?Optional.of(""):Optional.empty();});
        var query=new ReadAdminPosts(r->{assertThat(r).isSameAs(request);return new PostPageReader.Slice(rows,41);},details);
        var page=query.read(request);
        assertThat(page.total()).isEqualTo(41);assertThat(page.content()).extracting(PostDetails::postId).containsExactly(1L,2L);
        assertThat(page.content()).extracting(PostDetails::authorNickname).containsExactly(null,"사용자8");
        assertThat(page.content().getFirst().imageUrls()).containsExactly("same",null,"same");assertThat(calls).containsExactly(7L,8L);
    }
    @Test void given__empty_or_failed_page__when__reading__then__do_not_request_nicknames_or_convert_failure_into_empty_success() {
        var request=new PostPageReader.Request(4,20,List.of());
        var details=new ReadPost(id->{throw new AssertionError();},id->{throw new AssertionError();});
        assertThat(new ReadAdminPosts(r->new PostPageReader.Slice(List.of(),41),details).read(request)).isEqualTo(new ReadAdminPosts.Page(List.of(),41));
        var failure=new IllegalStateException("synthetic");
        assertThatThrownBy(()->new ReadAdminPosts(r->{throw failure;},details).read(request)).isSameAs(failure);
    }
    private PostSnapshot post(long id,long author){return new PostSnapshot(id,author,"title","content","REPORT",Arrays.asList("same",null,"same"),null,null);}
}
