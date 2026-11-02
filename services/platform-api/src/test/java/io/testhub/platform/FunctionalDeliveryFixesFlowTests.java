package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.functional.FunctionalAssetExchangeService;
import io.testhub.platform.functional.FunctionalAssetService;
import io.testhub.platform.functional.FunctionalTestExecutionService;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {"testhub.registration.enabled=true", "testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver = FunctionalDatabaseProfile.class)
@DirtiesContext
class FunctionalDeliveryFixesFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FunctionalDatabaseProfile.configure(registry, directory, "delivery-fixes-fixture");
        if (!FunctionalDatabaseProfile.mysql()) {
            registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("workspace")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            registry.add("spring.datasource.username", () -> "sa");
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalAssetService assets;
    @Autowired FunctionalAssetExchangeService exchange;
    @Autowired FunctionalTestExecutionService executions;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor, String project, String requirements, String cases) { }
    Fixture fixture() {
        String account = "qa-" + UUID.randomUUID();
        var actor = new PlatformUserPrincipal(registration.register(account, account + "@example.test", "交付回归", UUID.randomUUID() + "Aa1!"));
        var context = access.context(actor);
        String project = context.projects().get(context.organizations().getFirst().id()).getFirst().id();
        var requirements = assets.createCatalog(actor, project, new FunctionalAssetService.CatalogInput("需求", null, 0, "REQUIREMENTS"));
        var cases = assets.createCatalog(actor, project, new FunctionalAssetService.CatalogInput("用例", null, 0, "CASES"));
        return new Fixture(actor, project, requirements.id(), cases.id());
    }
    static Stream<String> planNames() {
        return Stream.of("短计划", "长".repeat(193), "长".repeat(194), "长".repeat(200), "长".repeat(192) + "😀" + "尾".repeat(6));
    }
    @ParameterizedTest @MethodSource("planNames")
    void defaultRunNameFitsStorageAndKeepsSuffixWithoutBreakingUnicode(String name) {
        var f = fixture();
        var c = assets.createCase(f.actor(), f.project(), new FunctionalAssetService.CaseInput(f.cases(), null, "CASE_1", "用例", "P1", "READY", "FUNCTIONAL", "", "步骤", "结果"));
        var p = executions.createPlan(f.actor(), f.project(), new FunctionalTestExecutionService.PlanInput(null, "PLAN_1", name, "READY", "", List.of(c.id())));
        for (String defaultName : new String[] {null, "", "  "}) {
            var run = executions.createRun(f.actor(), f.project(), p.id(), defaultName);
            assertTrue(run.name().length() <= 200);
            assertTrue(run.name().endsWith(" · 执行批次"));
            String prefix = run.name().substring(0, run.name().length() - " · 执行批次".length());
            assertTrue(name.startsWith(prefix));
            assertFalse(Character.isHighSurrogate(prefix.charAt(prefix.length() - 1)));
            if (name.length() <= 193) assertEquals(name + " · 执行批次", run.name());
        }
        assertEquals("自定义批次", executions.createRun(f.actor(), f.project(), p.id(), "自定义批次").name());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> executions.createRun(f.actor(), f.project(), p.id(), "超".repeat(201))).getStatusCode().value());
        assertEquals(4, executions.listRuns(f.actor(), f.project(), p.id()).size());
    }
    @Test void missingWorkbookReturns400EnvelopeAndDoesNotWrite() throws Exception {
        var f = fixture(); long audit = auditCount(f.project());
        mvc.perform(multipart("/api/v1/projects/{p}/functional/assets/import", f.project()).param("type", "REQUIREMENTS").with(user(f.actor())).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.message").value("缺少必填文件或表单项：file"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertEquals(audit, auditCount(f.project()));
        assertTrue(assets.listRequirements(f.actor(), f.project(), null).isEmpty());
    }
    static Stream<Arguments> bodyLengths() {
        return Stream.of("description", "precondition", "steps", "expectedResult")
                .flatMap(field -> Stream.of(19999, 20000, 20001).map(length -> Arguments.of(field, length)));
    }
    @ParameterizedTest @MethodSource("bodyLengths")
    void importPreflightHonorsEveryBodyBoundaryAndIsAtomic(String field, int length) throws Exception {
        var f = fixture();
        boolean requirement = field.equals("description");
        var kind = requirement ? FunctionalAssetExchangeService.AssetKind.REQUIREMENTS : FunctionalAssetExchangeService.AssetKind.CASES;
        String label = switch (field) { case "description" -> "需求说明"; case "precondition" -> "前置条件"; case "steps" -> "测试步骤"; default -> "预期结果"; };
        String[] headers = requirement ? new String[] {"目录名称", "需求编号", "需求标题", "优先级", "状态", "需求说明"}
                : new String[] {"目录名称", "关联需求编号", "用例编号", "用例标题", "优先级", "状态", "前置条件", "测试步骤", "预期结果", "用例类型"};
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("导入"); var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            for (int rowNumber = 1; rowNumber <= 2; rowNumber++) {
                String[] values = requirement ? new String[] {"需求", "REQ_" + rowNumber, "需求", "P1", "DRAFT", "正常"}
                        : new String[] {"用例", "", "CASE_" + rowNumber, "用例", "P1", "READY", "前置", "步骤", "预期", "FUNCTIONAL"};
                int column = requirement ? 5 : field.equals("precondition") ? 6 : field.equals("steps") ? 7 : 8;
                if (rowNumber == 2) values[column] = "界".repeat(length);
                var row = sheet.createRow(rowNumber);
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            }
            book.write(out); bytes = out.toByteArray();
        }
        long audit = auditCount(f.project());
        var result = exchange.importWorkbook(f.actor(), f.project(), kind, new MockMultipartFile("file", "qa.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes));
        int count = requirement ? assets.listRequirements(f.actor(), f.project(), null).size() : assets.listCases(f.actor(), f.project(), null).size();
        if (length > 20000) {
            assertFalse(result.completed()); assertEquals(0, result.importedCount()); assertEquals(0, count);
            assertEquals(audit, auditCount(f.project()));
            assertTrue(result.errors().stream().anyMatch(error -> error.rowNumber() == 3 && error.message().contains(label) && error.message().contains("20000")));
        } else {
            assertTrue(result.completed()); assertEquals(2, result.importedCount()); assertEquals(2, count); assertTrue(result.errors().isEmpty());
            String stored = requirement ? assets.listRequirements(f.actor(), f.project(), null).stream().filter(r -> r.requirementCode().equals("REQ_2")).findFirst().orElseThrow().description()
                    : assets.listCases(f.actor(), f.project(), null).stream().filter(c -> c.caseCode().equals("CASE_2")).map(c -> switch (field) { case "precondition" -> c.precondition(); case "steps" -> c.steps(); default -> c.expectedResult(); }).findFirst().orElseThrow();
            assertEquals(length, stored.length());
        }
    }
    long auditCount(String project) { return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE project_id=:p").param("p", project).query(Long.class).single(); }
}
