package com.pawbridge.backend.user.internal.directory.web;

import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.web.UserProfileController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AdminUserDirectoryController {
    public enum Role { ROLE_USER, ROLE_ADMIN, ROLE_SHELTER }
    private final UserDirectoryQuery users;
    public AdminUserDirectoryController(UserDirectoryQuery users) { this.users=users; }

    @GetMapping("/api/v1/admin/users")
    public UserProfileController.Response<Page<UserProfile>> list(
            @PageableDefault(size=20,sort="createdAt",direction=Sort.Direction.DESC) Pageable pageable) {
        return response(null,null,pageable);
    }
    @GetMapping("/api/v1/admin/users/search")
    public UserProfileController.Response<Page<UserProfile>> search(
            @RequestParam(name="keyword",required=false) String keyword,
            @RequestParam(name="role",required=false) Role role,
            @PageableDefault(size=20,sort="createdAt",direction=Sort.Direction.DESC) Pageable pageable) {
        return response(keyword,role==null?null:role.name(),pageable);
    }
    private UserProfileController.Response<Page<UserProfile>> response(String keyword,String role,Pageable pageable) {
        var request=new UserDirectoryQuery.Request(pageable.getPageNumber(),pageable.getPageSize(),pageable.getSort().stream()
                .map(order->new UserDirectoryQuery.Order(order.getProperty(),order.isAscending(),order.isIgnoreCase())).toList());
        var result=users.page(keyword,role,request);
        return new UserProfileController.Response<>(200,"요청이 성공적으로 처리되었습니다.",new PageImpl<>(result.content(),pageable,result.total()));
    }
}
