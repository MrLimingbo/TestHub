package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import io.testhub.platform.account.*;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.functional.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class) @DirtiesContext
class FunctionalExchangeRoundTripTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { FunctionalDatabaseProfile.configure(registry,directory,"exchange-test-only"); }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired FunctionalAssetService assets;
    @Autowired FunctionalAssetExchangeService exchange;
    record Workspace(PlatformUserPrincipal user,String project,String requirements,String cases) {}
    Workspace workspace() {
        String account="exchange-"+UUID.randomUUID();
        var user=new PlatformUserPrincipal(registration.register(account,account+"@example.test","交换测试","Exchange-test-2026!"));
        var context=access.context(user);String project=context.projects().get(context.organizations().getFirst().id()).getFirst().id();
        var requirements=assets.createCatalog(user,project,new FunctionalAssetService.CatalogInput("需求目录",null,0,"REQUIREMENTS"));
        var cases=assets.createCatalog(user,project,new FunctionalAssetService.CatalogInput("用例目录",null,0,"CASES"));
        return new Workspace(user,project,requirements.id(),cases.id());
    }
    FunctionalAssetExchangeService.ImportResult copy(Workspace source,Workspace target,FunctionalAssetExchangeService.AssetKind kind) {
        var workbook=exchange.export(source.user(),source.project(),kind,false);
        return exchange.importWorkbook(target.user(),target.project(),kind,new MockMultipartFile("file",workbook.filename(),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook.bytes()));
    }
    @Test void exportedCasesPreserveEveryTypeAndRebindRequirementsAcrossProjects() {
        var source=workspace();var target=workspace();
        var requirement=assets.createRequirement(source.user(),source.project(),new FunctionalAssetService.RequirementInput(source.requirements(),"REQ_ROUNDTRIP","中文需求","P0","APPROVED","需求说明"));
        var types=List.of("UNIT","API","FUNCTIONAL","INSTALLATION","CONFIGURATION","PERFORMANCE","SECURITY","OTHER");
        for(var type:types)assets.createCase(source.user(),source.project(),new FunctionalAssetService.CaseInput(source.cases(),requirement.id(),"CASE_"+type,"用例 "+type,"P1","READY",type,"前置","步骤\n第二步","预期"));
        assertTrue(copy(source,target,FunctionalAssetExchangeService.AssetKind.REQUIREMENTS).completed());
        var copied=copy(source,target,FunctionalAssetExchangeService.AssetKind.CASES);assertTrue(copied.completed());assertEquals(8,copied.importedCount());
        var newRequirement=assets.listRequirements(target.user(),target.project(),null).getFirst();assertNotEquals(requirement.id(),newRequirement.id());
        for(var item:assets.listCases(target.user(),target.project(),null)) {
            assertEquals(item.caseCode().substring(5),item.caseType());assertEquals(newRequirement.id(),item.requirementId());assertEquals("步骤\n第二步",item.steps());
        }
        assertEquals(8,assets.listCases(source.user(),source.project(),null).size());
    }
    byte[] workbook(java.util.function.Consumer<org.apache.poi.ss.usermodel.Sheet> edit) throws Exception {
        var source=workspace();var bytes=exchange.export(source.user(),source.project(),FunctionalAssetExchangeService.AssetKind.CASES,true).bytes();
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));var out=new java.io.ByteArrayOutputStream()) {edit.accept(book.getSheetAt(0));book.write(out);return out.toByteArray();}
    }
    void caseRow(org.apache.poi.ss.usermodel.Sheet sheet,int index,String code,String type) {
        var row=sheet.createRow(index);var values=List.of("用例目录","",code,"导入用例","P1","READY","前置","步骤","预期",type);
        for(int i=0;i<values.size();i++)row.createCell(i).setCellValue(values.get(i));
    }
    FunctionalAssetExchangeService.ImportResult importCases(Workspace target,byte[] bytes) {return exchange.importWorkbook(target.user(),target.project(),FunctionalAssetExchangeService.AssetKind.CASES,new MockMultipartFile("file","cases.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes));}
    @Test void legacyNineColumnWorkbookStillImportsAsFunctionalCases() throws Exception {
        var target=workspace();var bytes=workbook(sheet->{caseRow(sheet,1,"LEGACY_CASE","API");sheet.getRow(0).removeCell(sheet.getRow(0).getCell(9));sheet.getRow(1).removeCell(sheet.getRow(1).getCell(9));});
        assertTrue(importCases(target,bytes).completed());assertEquals("FUNCTIONAL",assets.listCases(target.user(),target.project(),null).getFirst().caseType());
    }
    @Test void invalidTypeRejectsWholeWorkbookBeforeAnyCaseIsInserted() throws Exception {
        var target=workspace();var bytes=workbook(sheet->{caseRow(sheet,1,"GOOD_CASE","API");caseRow(sheet,2,"BAD_CASE","INVALID");});
        var result=importCases(target,bytes);assertFalse(result.completed());assertEquals(0,result.importedCount());assertTrue(result.errors().stream().anyMatch(e->e.rowNumber()==3&&e.message().contains("用例类型")));
        assertTrue(assets.listCases(target.user(),target.project(),null).isEmpty());
    }
    @Test void blankAndLowerCaseNewTypeCellsAreNormalizedWithoutBreakingTemplates() throws Exception {
        var target=workspace();var bytes=workbook(sheet->{caseRow(sheet,1,"BLANK_TYPE","");caseRow(sheet,2,"LOWER_TYPE","security");});
        assertTrue(importCases(target,bytes).completed());var items=assets.listCases(target.user(),target.project(),null);
        assertEquals("FUNCTIONAL",items.stream().filter(c->c.caseCode().equals("BLANK_TYPE")).findFirst().orElseThrow().caseType());assertEquals("SECURITY",items.stream().filter(c->c.caseCode().equals("LOWER_TYPE")).findFirst().orElseThrow().caseType());
    }
    @Test void repeatedCodesMissingCatalogAndRequirementRejectWithoutPartialWrites() throws Exception {
        var target=workspace();var bytes=workbook(sheet->{caseRow(sheet,1,"GOOD_TYPE","API");caseRow(sheet,2,"GOOD_TYPE","UNIT");sheet.getRow(2).getCell(0).setCellValue("目录不存在");sheet.getRow(2).getCell(1).setCellValue("REQ_MISSING");});
        var result=importCases(target,bytes);assertFalse(result.completed());assertTrue(result.errors().stream().anyMatch(e->e.message().contains("重复编号")));assertTrue(result.errors().stream().anyMatch(e->e.message().contains("目录不存在")));assertTrue(result.errors().stream().anyMatch(e->e.message().contains("关联需求")));assertTrue(assets.listCases(target.user(),target.project(),null).isEmpty());
    }
    @Test void exchangeHonorsProjectBoundariesAndRejectsMistypedOptionalHeader() throws Exception {
        var source=workspace();var target=workspace();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->exchange.export(target.user(),source.project(),FunctionalAssetExchangeService.AssetKind.CASES,false));
        var bytes=workbook(sheet->{caseRow(sheet,1,"BAD_HEADER","API");sheet.getRow(0).getCell(9).setCellValue("错误列名");});
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->importCases(target,bytes));assertTrue(assets.listCases(target.user(),target.project(),null).isEmpty());
    }
}
