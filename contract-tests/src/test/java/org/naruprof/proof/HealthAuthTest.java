package org.naruprof.proof;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.naruprof.http.ServiceAuth;
import org.naruprof.http.LearningController;
import org.naruprof.api.Learning;
import java.lang.reflect.Proxy;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class HealthAuthTest {
    private MockHttpServletResponse request(String method,String path,String... credentials) throws Exception {
        var request = new MockHttpServletRequest(method,path);
        for (String credential : credentials) request.addHeader("Authorization",credential);
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        new ServiceAuth("fixture-vertx").doFilter(request,response,(req,res) -> reached.set(true));
        assertEquals(response.getStatus() == 200,reached.get());
        return response;
    }

    @Test void launcherCanProbeOnlyTheTwoPrivateHealthGets() throws Exception {
        assertEquals(200,request("GET","/internal/health/live").getStatus());
        assertEquals(200,request("GET","/internal/health/ready").getStatus());
        for (String path : new String[]{"/api/chats","/internal/v1/profile/export",
                "/internal/health/ready/extra","/internal/health/ready/","/internal/health/other"}) {
            assertEquals(401,request("GET",path).getStatus(),path);
        }
        assertEquals(401,request("POST","/internal/health/ready").getStatus());
    }

    @Test void applicationRequestsStillRequireExactlyOneCorrectCredential() throws Exception {
        assertEquals(200,request("GET","/api/chats","Bearer fixture-vertx").getStatus());
        assertEquals(401,request("GET","/api/chats","Bearer wrong").getStatus());
        var repeated = request("GET","/api/chats","Bearer fixture-vertx","Bearer fixture-vertx");
        assertEquals(401,repeated.getStatus());
        assertEquals("Bearer",repeated.getHeader("WWW-Authenticate"));
    }

    @Test void anonymousProbePreservesUnavailableReadiness() throws Exception {
        var throwing = new AtomicBoolean();
        var learning = (Learning)Proxy.newProxyInstance(Learning.class.getClassLoader(),new Class<?>[]{Learning.class},
                (proxy,method,args) -> {
                    if (method.getName().equals("ready")) {
                        if (throwing.get()) throw new IllegalStateException("fixture storage unavailable");
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var client = MockMvcBuilders.standaloneSetup(new LearningController(learning))
                .addFilters(new ServiceAuth("fixture-vertx")).build();
        assertEquals(200,client.perform(get("/internal/health/live")).andReturn().getResponse().getStatus());
        assertEquals(503,client.perform(get("/internal/health/ready")).andReturn().getResponse().getStatus());
        throwing.set(true);
        assertEquals(503,client.perform(get("/internal/health/ready")).andReturn().getResponse().getStatus());
    }
}
