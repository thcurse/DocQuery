package com.doc.docquery.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** 为正式服务面每次HTTP尝试生成不可由调用方覆盖的requestId。 */
@Component
public class QueryRequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = QueryRequestIdFilter.class.getName() + ".requestId";
    public static final String HEADER = "X-DocQuery-Request-Id";
    private static final Pattern SERVICE_QUERY = Pattern.compile(
            "^/api/v1/service/knowledge-bases/[1-9][0-9]*/(retrieve|answer)$"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI() == null
                || !SERVICE_QUERY.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        filterChain.doFilter(request, response);
    }
}
