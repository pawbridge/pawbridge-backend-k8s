package com.pawbridge.userservice.contact;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "community-contact",
        url = "${service.community.url:${COMMUNITY_SERVICE_URL:http://community-service:8082}}")
public interface CommunityContactClient {
    @DeleteMapping("/internal/private-notes/members/{member}")
    void removeMailboxes(@PathVariable("member") long member);
}
