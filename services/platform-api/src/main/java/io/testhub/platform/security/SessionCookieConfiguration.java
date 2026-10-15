package io.testhub.platform.security;

import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Keep the session cookie contract identical for embedded and mock servlet contexts. */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfiguration {
    @Bean
    CookieSerializer sessionCookieSerializer(ServerProperties serverProperties) {
        Cookie cookie = serverProperties.getServlet().getSession().getCookie();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        if (cookie.getName() != null) serializer.setCookieName(cookie.getName());
        if (cookie.getDomain() != null) serializer.setDomainName(cookie.getDomain());
        if (cookie.getPath() != null) serializer.setCookiePath(cookie.getPath());
        if (cookie.getHttpOnly() != null) serializer.setUseHttpOnlyCookie(cookie.getHttpOnly());
        if (cookie.getSecure() != null) serializer.setUseSecureCookie(cookie.getSecure());
        if (cookie.getMaxAge() != null) serializer.setCookieMaxAge(Math.toIntExact(cookie.getMaxAge().getSeconds()));
        if (cookie.getSameSite() != null) serializer.setSameSite(cookie.getSameSite().attributeValue());
        if (cookie.getPartitioned() != null) serializer.setPartitioned(cookie.getPartitioned());
        return serializer;
    }
}
