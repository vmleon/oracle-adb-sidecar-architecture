package dev.victormartin.adbsidecar.back.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class VersionsController {

    private final JdbcTemplate adbJdbc;
    private final JdbcTemplate oracleJdbc;
    private final JdbcTemplate postgresJdbc;
    private final MongoTemplate mongo;

    public VersionsController(
            @Qualifier("adbJdbc") JdbcTemplate adbJdbc,
            @Qualifier("oracleJdbc") JdbcTemplate oracleJdbc,
            @Qualifier("postgresJdbc") JdbcTemplate postgresJdbc,
            MongoTemplate mongo) {
        this.adbJdbc = adbJdbc;
        this.oracleJdbc = oracleJdbc;
        this.postgresJdbc = postgresJdbc;
        this.mongo = mongo;
    }

    @GetMapping("/versions")
    public Map<String, String> versions() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("adb", queryOracle(adbJdbc));
        result.put("oracle", queryOracle(oracleJdbc));
        result.put("postgres", queryPostgres());
        result.put("mongo", queryMongo());
        return result;
    }

    private String queryOracle(JdbcTemplate jdbc) {
        try {
            return jdbc.queryForObject(
                    "SELECT BANNER_FULL FROM V$VERSION WHERE ROWNUM = 1", String.class);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String queryPostgres() {
        try {
            return postgresJdbc.queryForObject("SELECT version()", String.class);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String queryMongo() {
        try {
            Document buildInfo = mongo.executeCommand("{ buildInfo: 1 }");
            return "MongoDB " + buildInfo.getString("version");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
