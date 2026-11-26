package io.testhub.platform.aitesting;

import io.testhub.platform.model.ModelProviderService.ChatSelection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static io.testhub.platform.aitesting.AiAgentModels.*;

@Repository
class AiAgentRepository {
    private static final String SCOPE = "organization_id = :org AND project_id = :project";
    private final JdbcClient jdbc;

    AiAgentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Workspace> workspaces(Scope scope) {
        return scoped("SELECT * FROM ai_workspace WHERE " + SCOPE + " ORDER BY archived, name, id", scope)
                .query(this::workspace).list();
    }

    Optional<Workspace> workspace(Scope scope, String id, boolean lock) {
        return scoped("SELECT * FROM ai_workspace WHERE " + SCOPE + " AND id = :id" + (lock ? " FOR UPDATE" : ""), scope)
                .param("id", id).query(this::workspace).optional();
    }

    boolean saveWorkspace(Scope scope, Workspace row, boolean create) {
        String sql = create
                ? "INSERT INTO ai_workspace (id, organization_id, project_id, name, archived, version) VALUES (:id, :org, :project, :name, :archived, :version)"
                : "UPDATE ai_workspace SET name = :name, archived = :archived, version = :version WHERE "
                        + SCOPE + " AND id = :id AND version = :previous";
        var write = scoped(sql, scope).param("id", row.id()).param("name", row.name())
                .param("archived", row.archived()).param("version", row.version());
        if (!create) write.param("previous", row.version() - 1);
        return write.update() == 1;
    }

    List<Agent> agents(Scope scope) {
        return scoped("SELECT * FROM ai_agent WHERE " + SCOPE + " ORDER BY archived, name, id", scope)
                .query(this::agent).list();
    }

    Optional<Agent> agent(Scope scope, String id) {
        return scoped("SELECT * FROM ai_agent WHERE " + SCOPE + " AND id = :id", scope)
                .param("id", id).query(this::agent).optional();
    }

    boolean saveAgent(Scope scope, Agent row, boolean create) {
        String sql = create ? """
                INSERT INTO ai_agent (id, organization_id, project_id, workspace_id, name, description, system_prompt,
                                      provider_id, model_id, reasoning_level, archived, version)
                VALUES (:id, :org, :project, :workspace, :name, :description, :prompt,
                        :provider, :model, :reasoning, :archived, :version)
                """ : """
                UPDATE ai_agent SET workspace_id = :workspace, name = :name, description = :description,
                                    system_prompt = :prompt, provider_id = :provider, model_id = :model,
                                    reasoning_level = :reasoning, archived = :archived, version = :version
                WHERE
                """ + SCOPE + " AND id = :id AND version = :previous";
        var write = withModel(scoped(sql, scope), row.model()).param("id", row.id()).param("workspace", row.workspaceId())
                .param("name", row.name()).param("description", row.description()).param("prompt", row.systemPrompt())
                .param("archived", row.archived()).param("version", row.version());
        if (!create) write.param("previous", row.version() - 1);
        return write.update() == 1;
    }

    List<Conversation> conversations(Scope scope, String agentId) {
        return scoped("SELECT * FROM ai_conversation WHERE " + SCOPE
                + " AND owner_id = :owner AND agent_id = :agent ORDER BY updated_at DESC, id DESC", scope)
                .param("owner", scope.userId()).param("agent", agentId).query(this::conversation).list();
    }

    List<Conversation> workspaceConversations(Scope scope) {
        return scoped("""
                SELECT c.id, c.agent_id, c.title, c.updated_at, c.version,
                       COALESCE(c.workspace_id, a.workspace_id) AS workspace_id,
                       CASE WHEN c.workspace_id IS NULL THEN a.archived ELSE c.archived END AS archived
                FROM ai_conversation c LEFT JOIN ai_agent a
                    ON a.id = c.agent_id AND a.organization_id = c.organization_id AND a.project_id = c.project_id
                WHERE c.organization_id = :org AND c.project_id = :project AND c.owner_id = :owner
                ORDER BY c.updated_at DESC, c.id DESC
                """, scope).param("owner", scope.userId()).query(this::conversation).list();
    }

    Optional<Conversation> conversation(Scope scope, String id, boolean lock) {
        return scoped("SELECT * FROM ai_conversation WHERE " + SCOPE + " AND owner_id = :owner AND id = :id"
                        + (lock ? " FOR UPDATE" : ""), scope)
                .param("owner", scope.userId()).param("id", id).query(this::conversation).optional();
    }

    void createConversation(Scope scope, String id, String agentId, String workspaceId) {
        scoped("""
                INSERT INTO ai_conversation (id, organization_id, project_id, agent_id, workspace_id, owner_id, title)
                VALUES (:id, :org, :project, :agent, :workspace, :owner, :title)
                """, scope).param("id", id).param("agent", agentId, Types.CHAR)
                .param("workspace", workspaceId, Types.CHAR).param("owner", scope.userId())
                .param("title", "新会话").update();
    }

    void updateConversation(Scope scope, String id, ConversationInput input, long version) {
        scoped("""
                UPDATE ai_conversation SET workspace_id = :workspace, archived = :archived, version = :version,
                    custom_title = CASE WHEN :title IS NULL THEN custom_title ELSE TRUE END,
                    title = COALESCE(:title, title)
                WHERE
                """ + SCOPE + " AND owner_id = :owner AND id = :id", scope)
                .param("id", id).param("owner", scope.userId()).param("workspace", input.workspaceId())
                .param("archived", input.archived()).param("version", version)
                .param("title", input.title() == null ? null : input.title().strip(), Types.VARCHAR).update();
    }

    void touchConversation(Scope scope, String id, String title) {
        scoped("UPDATE ai_conversation SET title = CASE WHEN custom_title THEN title ELSE COALESCE(:title, title) END, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE "
                        + SCOPE + " AND owner_id = :owner AND id = :id", scope)
                .param("id", id).param("owner", scope.userId()).param("title", title, Types.VARCHAR).update();
    }

    List<Turn> turns(Scope scope, String conversationId) {
        return scoped("SELECT * FROM ai_turn WHERE " + SCOPE + " AND conversation_id = :conversation ORDER BY turn_number", scope)
                .param("conversation", conversationId).query(this::turn).list();
    }

    void insertTurn(Scope scope, String conversationId, Turn turn) {
        withModel(scoped("""
                INSERT INTO ai_turn (id, organization_id, project_id, conversation_id, turn_number, attempt_id,
                                     provider_id, model_id, reasoning_level, user_text, source_summary, prompt_context, status)
                VALUES (:id, :org, :project, :conversation, :number, :attempt,
                        :provider, :model, :reasoning, :text, :sources, :context, 'RUNNING')
                """, scope), turn.model()).param("id", turn.id()).param("conversation", conversationId)
                .param("number", turn.number()).param("attempt", turn.attemptId()).param("text", turn.userText())
                .param("sources", turn.sourceSummary()).param("context", turn.promptContext()).update();
    }

    void retryTurn(Scope scope, Turn turn) {
        withModel(scoped("""
                UPDATE ai_turn SET attempt_id = :attempt, provider_id = :provider, model_id = :model,
                                   reasoning_level = :reasoning, status = 'RUNNING', reply = NULL,
                                   failure_message = NULL, started_at = CURRENT_TIMESTAMP
                WHERE
                """ + SCOPE + " AND id = :id", scope), turn.model())
                .param("id", turn.id()).param("attempt", turn.attemptId()).update();
    }

    boolean finishTurn(Scope scope, String conversationId, String id, String attemptId, TurnStatus status,
                       String reply, String reason) {
        return scoped("UPDATE ai_turn SET status = :status, reply = :reply, failure_message = :reason WHERE "
                        + SCOPE + " AND conversation_id = :conversation AND id = :id AND attempt_id = :attempt AND status = 'RUNNING'", scope)
                .param("conversation", conversationId).param("id", id).param("attempt", attemptId)
                .param("status", status.name()).param("reply", reply, Types.VARCHAR).param("reason", reason, Types.VARCHAR)
                .update() == 1;
    }

    void expireTurns(Scope scope, String conversationId, Instant before) {
        scoped("UPDATE ai_turn SET status = 'FAILED', failure_message = :reason WHERE " + SCOPE
                        + " AND conversation_id = :conversation AND status = 'RUNNING' AND started_at < :before", scope)
                .param("reason", "上次回复已中断，可以重试").param("conversation", conversationId)
                .param("before", Timestamp.from(before)).update();
    }

    private JdbcClient.StatementSpec scoped(String sql, Scope scope) {
        return jdbc.sql(sql).param("org", scope.organizationId()).param("project", scope.projectId());
    }

    private JdbcClient.StatementSpec withModel(JdbcClient.StatementSpec statement, ChatSelection model) {
        return statement.param("provider", model.providerId()).param("model", model.modelId())
                .param("reasoning", model.reasoningLevel() == null ? null : model.reasoningLevel().name(), Types.VARCHAR);
    }

    private Workspace workspace(ResultSet row, int index) throws SQLException {
        return new Workspace(row.getString("id"), row.getString("name"), row.getBoolean("archived"), row.getLong("version"));
    }

    private Agent agent(ResultSet row, int index) throws SQLException {
        return new Agent(row.getString("id"), row.getString("workspace_id"), row.getString("name"),
                row.getString("description"), row.getString("system_prompt"), model(row), row.getBoolean("archived"),
                row.getLong("version"));
    }

    private Conversation conversation(ResultSet row, int index) throws SQLException {
        return new Conversation(row.getString("id"), row.getString("agent_id"), row.getString("title"),
                row.getTimestamp("updated_at").toInstant(), row.getString("workspace_id"),
                row.getBoolean("archived"), row.getLong("version"));
    }

    private Turn turn(ResultSet row, int index) throws SQLException {
        return new Turn(row.getString("id"), row.getInt("turn_number"), row.getString("attempt_id"), model(row),
                row.getString("user_text"), row.getString("reply"), TurnStatus.valueOf(row.getString("status")),
                row.getString("failure_message"), row.getTimestamp("started_at").toInstant(),
                row.getString("source_summary"), row.getString("prompt_context"));
    }

    private ChatSelection model(ResultSet row) throws SQLException {
        return new ChatSelection(row.getString("provider_id"), row.getString("model_id"),
                reasoningLevel(row.getString("reasoning_level")));
    }
}
