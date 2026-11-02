package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.apidesign.*;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

class P2ResponseCodeFlowTests extends P1FlowTestSupport {
    @Autowired ApiDesignService design;
    @Autowired ApiInterchangeService interchange;
    record Fixture(PlatformUserPrincipal actor,String project,ApiCatalog catalog,ApiDefinition api) { }
    Fixture fixture(Object status) { var a=actor();String p=project(a);var c=design.createCatalog(a,p,new ApiDesignService.CatalogInput(unique(),null,0));var api=design.createDefinition(a,p,input(c.id(),"/p2",status));return new Fixture(a,p,c,api); }
    ApiDesignService.DefinitionInput input(String catalog,String path,Object status) {
        Map<String,Object> metadata=new LinkedHashMap<>();if(status!=null)metadata.put("statusCode",status);
        return new ApiDesignService.DefinitionInput(catalog,"GET",path,"P2响应","1.0.0","","",List.of(),List.of(),List.of(),"","{\"source\":\"kept\"}",metadata);
    }
    JsonNode export(Fixture f,String format) {return switch(format){case "OpenAPI"->interchange.exportOpenApi(f.actor,f.project,f.catalog.id());case "Postman"->interchange.exportPostman(f.actor,f.project,f.catalog.id());default->interchange.exportHar(f.actor,f.project,f.catalog.id(),"https://example.test");};}
    int code(JsonNode doc,String format,String path) {return switch(format){case "OpenAPI"->Integer.parseInt(doc.path("paths").path(path).path("get").path("responses").fieldNames().next());case "Postman"->doc.path("item").get(0).path("response").get(0).path("code").asInt();default->doc.path("log").path("entries").get(0).path("response").path("status").asInt();};}
    void setMetadata(Fixture f,String metadata){String stored=metadata;try{var n=json.readTree(metadata);if(n==null||n.isMissingNode())stored=json.writeValueAsString(metadata);}catch(Exception ignored){try{stored=json.writeValueAsString(metadata);}catch(Exception e){throw new IllegalStateException(e);}}jdbc.sql("UPDATE api_definition_version SET example_response=CAST(:m AS JSON) WHERE id=:id").param("m",stored).param("id",f.api.currentVersionId()).update();}
    void invalid(Object status){var f=fixture(status);for(String format:List.of("OpenAPI","Postman","HAR")){var e=assertThrows(ResponseStatusException.class,()->export(f,format));assertEquals(400,e.getStatusCode().value());}assertEquals(1,design.definitions(f.actor,f.project,null).size());}
    void validCodes(String format){for(int status:List.of(100,201,204,301,400,404,500,599)){var f=fixture(status);assertEquals(status,code(export(f,format),format,"/p2"));}}
    @Test @DisplayName("[TC-P2-D09-01][POS] OpenAPI preserves the approved valid status matrix") void openApi(){validCodes("OpenAPI");}
    @Test @DisplayName("[TC-P2-D09-02][POS] Postman preserves the approved valid status matrix") void postman(){validCodes("Postman");}
    @Test @DisplayName("[TC-P2-D09-03][POS] HAR preserves the approved valid status matrix") void har(){validCodes("HAR");}
    @Test @DisplayName("[TC-P2-D09-04][POS] truly missing or null metadata defaults to 200") void missing(){for(String metadata:List.of("{}","{\"statusCode\":null}","null","")){var f=fixture(null);setMetadata(f,metadata);for(String format:List.of("OpenAPI","Postman","HAR"))assertEquals(200,code(export(f,format),format,"/p2"));}}
    @Test @DisplayName("[TC-P2-D09-05][NEG] floating JSON status values cannot be truncated") void decimal(){invalid(200.0);invalid(201.9);}
    @Test @DisplayName("[TC-P2-D09-06][NEG] status outside 100 to 599 cannot become a successful default") void range(){invalid(99);invalid(600);}
    @Test @DisplayName("[TC-P2-D09-07][NEG] malformed or nonnumeric status metadata rejects export") void malformed(){invalid("bad");invalid("");var f=fixture(201);setMetadata(f,"broken-json");for(String format:List.of("OpenAPI","Postman","HAR"))assertEquals(400,assertThrows(ResponseStatusException.class,()->export(f,format)).getStatusCode().value());}
    @Test @DisplayName("[TC-P2-D09-08][POS] an imported single response keeps its status on subsequent exports") void roundTrip(){var a=actor();String p=project(a);var c=design.createCatalog(a,p,new ApiDesignService.CatalogInput(unique(),null,0));String doc="{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"QA\",\"version\":\"1\"},\"paths\":{\"/p2\":{\"get\":{\"responses\":{\"201\":{\"description\":\"Created\",\"content\":{\"application/json\":{\"example\":{\"kept\":true}}}}}}}}}";interchange.importOpenApi(a,p,new ApiInterchangeService.OpenApiImportInput(c.id(),doc));var f=new Fixture(a,p,c,design.definitions(a,p,c.id()).getFirst());for(String format:List.of("OpenAPI","Postman","HAR"))assertEquals(201,code(export(f,format),format,"/p2"));
        var postman=export(f,"Postman");var har=export(f,"HAR");
        for(String origin:List.of("Postman","HAR")) {
            // Each imported fixture uses its own project because route uniqueness is project-wide.
            var other=actor();String otherProject=project(other);var target=design.createCatalog(other,otherProject,new ApiDesignService.CatalogInput(unique(),null,0));
            if(origin.equals("Postman"))interchange.importPostman(other,otherProject,new ApiInterchangeService.PostmanImportInput(target.id(),postman.toString()));
            else interchange.importHar(other,otherProject,new ApiInterchangeService.HarImportInput(target.id(),har.toString()));
            var imported=new Fixture(other,otherProject,target,design.definitions(other,otherProject,target.id()).getFirst());
            for(String format:List.of("OpenAPI","Postman","HAR"))assertEquals(201,code(export(imported,format),format,"/p2"));
            assertTrue(design.versions(other,otherProject,imported.api.id()).getFirst().responseBody().contains("kept"));
        }
    }
    @Test @DisplayName("[TC-P2-D09-09][POS] export uses the established current-version selection") void currentVersion(){var f=fixture(201);design.createVersion(f.actor,f.project,f.api.id(),new ApiDesignService.VersionInput("2.0.0","","",List.of(),List.of(),List.of(),"","{}",Map.of("statusCode",500)));for(String format:List.of("OpenAPI","Postman","HAR"))assertEquals(500,code(export(f,format),format,"/p2"));}
    @Test @DisplayName("[TC-P2-D09-10][POS] catalog filtering does not mix response codes from other definitions") void catalogScope(){var f=fixture(201);var other=design.createCatalog(f.actor,f.project,new ApiDesignService.CatalogInput(unique(),null,0));design.createDefinition(f.actor,f.project,input(other.id(),"/other",500));assertEquals(1,export(f,"OpenAPI").path("paths").size());}
    @Test @DisplayName("[TC-P2-D09-11][NEG] response metadata cannot be exported across unauthorized projects") void permissions(){var f=fixture(201);var b=actor();assertThrows(AccessDeniedException.class,()->interchange.exportOpenApi(b,f.project,null));}
    @Test @DisplayName("[TC-P2-D09-12][POS] decimal digit strings retain their existing compatibility") void stringStatus(){var f=fixture(" 201 ");for(String format:List.of("OpenAPI","Postman","HAR"))assertEquals(201,code(export(f,format),format,"/p2"));}
    @Test @DisplayName("[TC-P2-D09-13][POS] existing 200 formats and response bodies remain consumable") void original(){var f=fixture(200);for(String format:List.of("OpenAPI","Postman","HAR")){var doc=export(f,format);assertEquals(200,code(doc,format,"/p2"));assertTrue(doc.toString().contains("kept"));}}
}
