package io.testhub.platform.access;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccessRepository {
    private final JdbcClient jdbc;

    public AccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrganizationSummary> findOrganizationsForUser(String userId) {
        return jdbc.sql("""
                SELECT DISTINCT o.id, o.name, o.code
                FROM organization o
                JOIN membership m ON m.organization_id = o.id
                WHERE m.user_id = :userId
                ORDER BY o.name
                """).param("userId", userId).query((rs, rowNumber) ->
                new OrganizationSummary(rs.getString("id"), rs.getString("name"), rs.getString("code"))).list();
    }

    public List<ProjectSummary> findProjectsForUser(String userId, String organizationId) {
        return jdbc.sql("""
                SELECT DISTINCT p.id, p.organization_id, p.name, p.code, p.status
                FROM project p
                JOIN membership m ON m.organization_id = p.organization_id
                WHERE p.organization_id = :organizationId
                  AND m.user_id = :userId
                  AND (m.project_id IS NULL OR m.project_id = p.id)
                ORDER BY p.name
                """).param("userId", userId).param("organizationId", organizationId).query((rs, rowNumber) ->
                new ProjectSummary(rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
                        rs.getString("code"), rs.getString("status"))).list();
    }

    public Optional<ProjectSummary> findProject(String projectId) {
        return jdbc.sql("""
                SELECT id, organization_id, name, code, status FROM project WHERE id = :projectId
                """).param("projectId", projectId).query((rs, rowNumber) ->
                new ProjectSummary(rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
                        rs.getString("code"), rs.getString("status"))).optional();
    }

    public List<String> findRoles(String userId, String organizationId, String projectId) {
        return jdbc.sql("""
                SELECT role_code FROM membership
                WHERE user_id = :userId AND organization_id = :organizationId
                  AND (project_id IS NULL OR project_id = :projectId)
                """).param("userId", userId).param("organizationId", organizationId).param("projectId", projectId)
                .query(String.class).list();
    }

    public boolean hasRole(String userId, String roleCode) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM membership WHERE user_id = :userId AND role_code = :roleCode)")
                .param("userId", userId).param("roleCode", roleCode).query(Boolean.class).single();
    }

    public List<ProjectMember> findProjectMembers(String projectId) {
        return jdbc.sql("""
                SELECT m.id, u.id AS user_id, u.email, u.display_name, m.role_code, m.created_at
                FROM membership m
                JOIN user_account u ON u.id = m.user_id
                WHERE m.project_id = :projectId
                ORDER BY m.created_at, u.email
                """).param("projectId", projectId).query((rs, rowNumber) -> new ProjectMember(
                rs.getString("id"), rs.getString("user_id"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("role_code"), timestamp(rs.getTimestamp("created_at")))).list();
    }

    public void deleteProjectMemberships(String userId, String projectId) {
        jdbc.sql("DELETE FROM membership WHERE user_id = :userId AND project_id = :projectId")
                .param("userId", userId).param("projectId", projectId).update();
    }

    public void insertMembership(String id, String userId, String organizationId, String projectId, String roleCode) {
        jdbc.sql("""
                INSERT INTO membership (id, user_id, organization_id, project_id, role_code)
                VALUES (:id, :userId, :organizationId, :projectId, :roleCode)
                """).param("id", id).param("userId", userId).param("organizationId", organizationId)
                .param("projectId", projectId).param("roleCode", roleCode).update();
    }

    public void deleteMembership(String membershipId, String projectId) {
        jdbc.sql("DELETE FROM membership WHERE id = :membershipId AND project_id = :projectId")
                .param("membershipId", membershipId).param("projectId", projectId).update();
    }

    public Optional<OrganizationSummary> findOrganizationByCode(String code) {
        return jdbc.sql("SELECT id, name, code FROM organization WHERE code = :code").param("code", code)
                .query((rs, rowNumber) -> new OrganizationSummary(rs.getString("id"), rs.getString("name"),
                        rs.getString("code"))).optional();
    }

    public Optional<ProjectSummary> findProjectByCode(String organizationId, String code) {
        return jdbc.sql("""
                SELECT id, organization_id, name, code, status FROM project
                WHERE organization_id = :organizationId AND code = :code
                """).param("organizationId", organizationId).param("code", code).query((rs, rowNumber) ->
                new ProjectSummary(rs.getString("id"), rs.getString("organization_id"), rs.getString("name"),
                        rs.getString("code"), rs.getString("status"))).optional();
    }

    public void insertOrganization(String id, String name, String code) {
        jdbc.sql("INSERT INTO organization (id, name, code) VALUES (:id, :name, :code)")
                .param("id", id).param("name", name).param("code", code).update();
    }

    public void insertProject(String id, String organizationId, String name, String code) {
        jdbc.sql("""
                INSERT INTO project (id, organization_id, name, code) VALUES (:id, :organizationId, :name, :code)
                """).param("id", id).param("organizationId", organizationId).param("name", name).param("code", code)
                .update();
    }

    private Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
