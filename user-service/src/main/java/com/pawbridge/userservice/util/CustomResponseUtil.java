package com.pawbridge.userservice.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CustomResponseUtil {

    private static final Logger log = LoggerFactory.getLogger(CustomResponseUtil.class);
    public static void fail(HttpServletResponse response, String msg, HttpStatus httpStatus) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            response.setStatus(httpStatus.value());

            // 로그인 실패 JSON 응답을 생성
            ResponseDTO<Void> errorResponse = ResponseDTO.errorWithMessage(
                httpStatus,
                msg);

            String jsonResponse = objectMapper.writeValueAsString(errorResponse);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(jsonResponse);

        } catch (Exception e) {
            log.error("서버 파싱 에러");
        }
    }
}
