package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.lang.reflect.Method;
import java.util.Map;
import org.apache.ibatis.ognl.OgnlRuntime;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.Test;

class MyBatisDependencySecurityTests {
    @Test
    void rejectsReflectionObjectsAsInvocationProxies() throws Exception {
        Method target = String.class.getMethod("substring", int.class);
        Method proxy = Method.class.getMethod("invoke", Object.class, Object[].class);
        // A harmless proxy call exercises CVE-2026-113730 in MyBatis's shaded OGNL.
        assertThrows(IllegalAccessException.class,
                () -> OgnlRuntime.invokeMethod(target, proxy, new Object[] {"example", new Object[] {1}}));
    }

    @Test
    void keepsDynamicSqlValuesBoundAsParameters() {
        var configuration = new MybatisConfiguration();
        var source = new XMLLanguageDriver().createSqlSource(configuration,
                "<script>SELECT id FROM user_account <where><if test='account != null'>account = #{account}</if></where></script>", Map.class);
        String value = "' OR 1=1 --";
        var bound = source.getBoundSql(Map.of("account", value));
        assertEquals("SELECT id FROM user_account WHERE account = ?", bound.getSql().replaceAll("\\s+", " ").trim());
        assertEquals(1, bound.getParameterMappings().size());
        assertEquals("account", bound.getParameterMappings().getFirst().getProperty());
        assertEquals(value, ((Map<?, ?>) bound.getParameterObject()).get("account"));
    }
}
