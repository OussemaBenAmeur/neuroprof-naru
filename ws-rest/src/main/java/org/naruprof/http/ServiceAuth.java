package org.naruprof.http;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** The launcher provisions a distinct credential for each private hop. */
public final class ServiceAuth extends OncePerRequestFilter {
    private final byte[] bearer;
    public ServiceAuth(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("VERTX_TO_NARU_TOKEN required");
        bearer = ("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        var headers = req.getHeaders("Authorization");
        if (!headers.hasMoreElements() || !MessageDigest.isEqual(bearer,headers.nextElement().getBytes(StandardCharsets.UTF_8)) || headers.hasMoreElements()) {
            res.setStatus(401);
            res.setHeader("WWW-Authenticate","Bearer");
            return;
        }
        chain.doFilter(req,res);
    }
}
