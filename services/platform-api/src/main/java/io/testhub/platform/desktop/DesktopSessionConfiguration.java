package io.testhub.platform.desktop;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

/** Session IDs, rotation, expiry and logout retain the standard Spring Session behavior. */
@Configuration
@Profile("desktop")
@EnableJdbcHttpSession(tableName = "desktop_session", maxInactiveIntervalInSeconds = 28800)
public class DesktopSessionConfiguration {
}
