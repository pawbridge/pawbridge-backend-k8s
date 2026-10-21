package com.pawbridge.backend.store.internal.directorder.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.application.OrderActorRejectedException;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrderReadControllerTest {
    private final DirectOrders orders=mock(DirectOrders.class);
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DirectOrders.OrderDetails order=new DirectOrders.OrderDetails(9007199254740993L,"purchased-uuid",7L,
            9007199254740993L,"PENDING","READY","saved receiver","saved phone","saved address","saved message",
            LocalDateTime.of(2026,10,20,22,0,1,123456789),List.of(new DirectOrders.Item("saved product","SAVED-80",9007199254740993L,2)));
    private MockMvc mvc;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        mvc=MockMvcBuilders.standaloneSetup(new OrderReadController(orders)).setControllerAdvice(new DirectOrderExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver(),new PageableHandlerMethodArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void current(Object member){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(member,"unused",List.of()));}

    @ParameterizedTest @NullSource @EnumSource(OrderStatus.class)
    void given__verified_member_and_optional_status_with_forged_owner__when__listing__then__forward_verified_owner_and_default_ten_and_return_direct_page(OrderStatus statusFilter) throws Exception {
        current(7L);var request=new StoreMemberReads.Request(0,10,List.of());
        when(orders.list(7,statusFilter==null?null:statusFilter.name(),request))
                .thenReturn(new StoreMemberReads.Page<>(List.of(order),0,10,1,List.of()));
        var call=get("/api/v1/orders").param("userId","99").header("X-User-Id","99").header("userId","99");
        if(statusFilter!=null)call.param("status",statusFilter.name());
        var bytes=mvc.perform(call).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var body=json.readTree(bytes);assertFalse(body.has("data"));assertEquals(1,body.get("content").size());
        assertEquals(0,body.get("number").intValue());assertEquals(10,body.get("size").intValue());
        assertEquals(1,body.get("totalElements").longValue());assertEquals(1,body.get("totalPages").intValue());
        assertTrue(body.get("sort").get("unsorted").booleanValue());
        var item=body.get("content").get(0);assertEquals(json.readTree(json.writeValueAsBytes(order)),item);
        assertEquals(12,item.size());assertEquals(4,item.get("items").get(0).size());
        assertEquals(9007199254740993L,item.get("orderId").longValue());assertEquals(9007199254740993L,item.get("items").get(0).get("price").longValue());
        assertEquals("2026-10-20T22:00:01.123456789",item.get("createdAt").asText());
        verify(orders).list(7,statusFilter==null?null:statusFilter.name(),request);verifyNoMoreInteractions(orders);
    }

    @Test void given__explicit_page_status_and_scalar_sorts__when__listing_then_reading_existing_detail__then__preserve_page_metadata_sort_and_identical_order_wire() throws Exception {
        current(7L);var sort=List.of(new StoreMemberReads.Order("totalAmount",true,false),new StoreMemberReads.Order("receiverName",false,true));
        var request=new StoreMemberReads.Request(3,2,sort);
        when(orders.list(7,"PAID",request)).thenReturn(new StoreMemberReads.Page<>(List.of(order),3,2,7,sort));
        when(orders.get(7,9007199254740993L)).thenReturn(order);
        var history=json.readTree(mvc.perform(get("/api/v1/orders").param("status","PAID").param("page","3").param("size","2")
                        .param("sort","totalAmount,asc","receiverName,desc,ignorecase"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.number").value(3)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(7)).andExpect(jsonPath("$.totalPages").value(4))
                .andExpect(jsonPath("$.sort.sorted").value(true)).andReturn().getResponse().getContentAsByteArray());
        var detail=json.readTree(mvc.perform(get("/api/v1/orders/9007199254740993"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(detail,history.get("content").get(0));
        verify(orders).list(7,"PAID",request);verify(orders).get(7,9007199254740993L);verifyNoMoreInteractions(orders);
    }

    @ParameterizedTest @ValueSource(strings={"missing","non-long"})
    void given__missing_or_non_long_principal_with_forged_owner__when__listing__then__unauthorized_before_application(String kind) throws Exception {
        if(kind.equals("non-long"))current("99");
        mvc.perform(get("/api/v1/orders").param("userId","99").header("X-User-Id","99").header("userId","99"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        verifyNoInteractions(orders);
    }

    @Test void given__unsupported_status__when__listing__then__original_type_error_without_application() throws Exception {
        current(7L);mvc.perform(get("/api/v1/orders").param("status","UNKNOWN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C005"))
                .andExpect(jsonPath("$.errors[0].field").value("status")).andExpect(jsonPath("$.errors[0].value").value("UNKNOWN"))
                .andExpect(jsonPath("$.errors[0].reason").value("typeMismatch"));
        verifyNoInteractions(orders);
    }

    static Stream<Arguments> failures(){return Stream.of(
            Arguments.of(new OrderActorRejectedException(),403,"C006","Access is Denied"),
            Arguments.of(new IllegalArgumentException("private query and account"),400,"C001","Invalid Input Value"),
            Arguments.of(new org.springframework.dao.DataAccessResourceFailureException("private query and account"),500,"C004","Server Error"));}
    @ParameterizedTest @MethodSource("failures")
    void given__current_actor_or_query_or_database_rejection__when__listing__then__preserve_sanitized_error_without_order_data(RuntimeException failure,int statusCode,String code,String message) throws Exception {
        current(7L);when(orders.list(eq(7L),isNull(),any())).thenThrow(failure);
        String body=mvc.perform(get("/api/v1/orders")).andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.errors").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private"));assertFalse(body.contains("purchased"));assertFalse(body.contains("saved"));
        verify(orders).list(7,null,new StoreMemberReads.Request(0,10,List.of()));verifyNoMoreInteractions(orders);
    }

    @ParameterizedTest @ValueSource(strings={"/api/orders","/api/v1/orders/uuid/purchased-uuid","/api/v1/orders/77/payment","/api/v1/orders/cart/direct"})
    void given__neighboring_unapproved_route__when__requesting__then__no_order_history_handler(String path) throws Exception {
        current(7L);mvc.perform(get(path)).andExpect(status().isNotFound());verifyNoInteractions(orders);
    }
}
