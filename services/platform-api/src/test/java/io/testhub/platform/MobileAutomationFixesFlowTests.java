package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.account.RegistrationService;
import io.testhub.platform.mobileautomation.MobileAutomationService;
import io.testhub.platform.mobileautomation.MobileAutomationSyncService;
import io.testhub.platform.mobileautomation.MobileAutomationTransferService;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"testhub.registration.enabled=true","testhub.bootstrap.demo-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles(resolver=FunctionalDatabaseProfile.class)
@DirtiesContext
class MobileAutomationFixesFlowTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        if (FunctionalDatabaseProfile.mysql()) FunctionalDatabaseProfile.configure(r,directory,"mobile-fixes");
        else {
            r.add("testhub.desktop.data-directory",directory::toString);
            r.add("spring.datasource.url",()->"jdbc:h2:file:"+directory.resolve("workspace")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            r.add("spring.datasource.username",()->"sa");
            String password=UUID.randomUUID().toString(); r.add("spring.datasource.password",()->password);
        }
    }
    @Autowired RegistrationService registration;
    @Autowired AccessService access;
    @Autowired MobileAutomationService mobile;
    @Autowired MobileAutomationSyncService sync;
    @Autowired MobileAutomationTransferService transfer;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    record Fixture(PlatformUserPrincipal actor,String project) { }
    Fixture fixture() {
        String account="mobile-fix-"+UUID.randomUUID(),password=UUID.randomUUID()+"Aa!";
        var actor=new PlatformUserPrincipal(registration.register(account,account+"@example.test","移动端修复验收",password));
        var c=access.context(actor); return new Fixture(actor,c.projects().get(c.organizations().getFirst().id()).getFirst().id());
    }
    MobileAutomationService.DeviceRequest device(String platform,String engine) {
        return new MobileAutomationService.DeviceRequest("设备"+UUID.randomUUID(),platform,"owned-"+UUID.randomUUID(),engine,Map.of(),true);
    }
    @Test void paddedKnownMismatchIsRejectedByHttpWithoutCreatingRecords() throws Exception {
        var f=fixture();
        for (String[] pair:List.of(new String[]{"ANDROID"," XCUITest "},new String[]{" android ","xcuitest"},new String[]{"IOS"," UiAutomator2 "},new String[]{" ios "," espresso "})) {
            mvc.perform(post("/api/v1/projects/{p}/mobile-automation/devices",f.project()).with(user(f.actor())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(device(pair[0],pair[1])))).andExpect(status().isBadRequest());
        }
        assertTrue(mobile.devices(f.actor(),f.project()).isEmpty());
    }
    @Test void rejectedEditPreservesVersionAndOriginalEngine() throws Exception {
        var f=fixture();var before=mobile.createDevice(f.actor(),f.project(),device("ANDROID","UiAutomator2"));
        mvc.perform(put("/api/v1/projects/{p}/mobile-automation/devices/{id}",f.project(),before.id()).with(user(f.actor())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("expectedVersion",before.version(),"device",device("ANDROID"," XCUITest "))))).andExpect(status().isBadRequest());
        assertEquals(before,mobile.devices(f.actor(),f.project()).getFirst());
    }
    @Test void legalPaddedAndCustomEnginesKeepExistingNormalization() {
        var f=fixture();
        for (String[] pair:List.of(new String[]{" android "," UiAutomator2 "},new String[]{" ios "," XCUITest "},new String[]{"ANDROID"," Espresso "},new String[]{"ANDROID"," custom-driver "})) {
            var result=mobile.createDevice(f.actor(),f.project(),device(pair[0],pair[1]));
            assertEquals(pair[0].trim().toUpperCase(Locale.ROOT),result.platform());assertEquals(pair[1].trim(),result.automationName());
        }
    }
    @Test void syncRejectsMismatchAndRollsBackTheWholeBatch() {
        var f=fixture();var before=sync.snapshot(f.actor(),f.project(),f.actor().userId());
        var valid=new MobileAutomationSyncService.Item("DEVICE","正常设备",Map.of("platform","ANDROID","udid","owned-good","automationName","UiAutomator2","capabilities",Map.of()));
        var bad=new MobileAutomationSyncService.Item("DEVICE","错误设备",Map.of("platform"," android ","udid","owned-bad","automationName"," XCUITest ","capabilities",Map.of()));
        var request=new MobileAutomationSyncService.Request(UUID.randomUUID().toString(),f.actor().userId(),before.revision(),List.of(new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),valid),new MobileAutomationSyncService.Operation(UUID.randomUUID().toString(),bad)));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->sync.apply(f.actor(),f.project(),request)).getStatusCode().value());
        assertEquals(before,sync.snapshot(f.actor(),f.project(),f.actor().userId()));
    }
    @Test void transferRejectsMismatchWithoutPartialImports() {
        var f=fixture();var source=new MobileAutomationTransferService.Snapshot(1,f.project(),List.of(
                new MobileAutomationTransferService.Device("01ARZ3NDEKTSV4RRFFQ69G5FAA","正常","ANDROID","owned-good","UiAutomator2",Map.of(),true,1),
                new MobileAutomationTransferService.Device("01ARZ3NDEKTSV4RRFFQ69G5FAB","错误","IOS","owned-bad"," Espresso ",Map.of(),true,1)),List.of(),List.of(),List.of());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->transfer.transfer(f.actor(),f.project(),new MobileAutomationTransferService.ImportRequest(UUID.randomUUID().toString(),f.actor().userId(),source))).getStatusCode().value());
        assertTrue(mobile.devices(f.actor(),f.project()).isEmpty());
    }
}
