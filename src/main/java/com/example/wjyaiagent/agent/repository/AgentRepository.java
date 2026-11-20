package com.example.wjyaiagent.agent.repository;

import com.example.wjyaiagent.agent.dto.AgentDtos.*;
import com.example.wjyaiagent.model.Citation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class AgentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Conversation> find(long owner, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM public.agent_conversation WHERE user_id=? AND id=?",
                        this::conversation,
                        owner,
                        id)
                .stream()
                .findFirst();
    }

    public Conversation create(long owner, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO public.agent_conversation(id,user_id,title) VALUES (?,?,?)",
                id,
                owner,
                title);
        return find(owner, id).orElseThrow();
    }

    public List<Conversation> list(long owner, int page, int size) {
        return jdbc.query(
                "SELECT * FROM public.agent_conversation WHERE user_id=? ORDER BY updated_at"
                    + " DESC,id LIMIT ? OFFSET ?",
                this::conversation,
                owner,
                size + 1,
                (long) page * size);
    }

    public void rename(long owner, UUID id, String title) {
        jdbc.update(
                "UPDATE public.agent_conversation SET title=?,updated_at=CURRENT_TIMESTAMP WHERE"
                    + " user_id=? AND id=?",
                title,
                owner,
                id);
    }

    public void delete(long owner, UUID id) {
        jdbc.update("DELETE FROM public.agent_conversation WHERE user_id=? AND id=?", owner, id);
    }

    public List<Message> messages(UUID id, int page, int size) {
        return jdbc.query(
                "SELECT * FROM public.agent_message WHERE conversation_id=? ORDER BY sequence DESC"
                    + " LIMIT ? OFFSET ?",
                this::message,
                id,
                size + 1,
                (long) page * size);
    }

    public List<Message> context(UUID id, int limit) {
        // Include only complete user/assistant pairs. The immediately following row belongs to the
        // same turn.
        var rows =
                jdbc.query(
                        "SELECT m.* FROM public.agent_message m WHERE conversation_id=? AND"
                            + " (role='assistant' AND status='completed' OR role='user' AND EXISTS"
                            + " (SELECT 1 FROM public.agent_message a WHERE"
                            + " a.conversation_id=m.conversation_id AND a.sequence=(SELECT"
                            + " MIN(n.sequence) FROM public.agent_message n WHERE"
                            + " n.conversation_id=m.conversation_id AND n.sequence>m.sequence) AND"
                            + " a.role='assistant' AND a.status='completed')) ORDER BY sequence"
                            + " DESC LIMIT ?",
                        this::message,
                        id,
                        limit);
        Collections.reverse(rows);
        if (!rows.isEmpty() && rows.getFirst().role().equals("assistant")) rows.removeFirst();
        return rows;
    }

    @Transactional
    public void start(UUID id, String input, UUID answer) {
        jdbc.update(
                "INSERT INTO public.agent_message(id,conversation_id,role,content,status) VALUES"
                    + " (?,?,'user',?,'completed')",
                UUID.randomUUID(),
                id,
                input);
        jdbc.update(
                "INSERT INTO public.agent_message(id,conversation_id,role,content,status) VALUES"
                    + " (?,?,'assistant','','generating')",
                answer,
                id);
        touch(id);
    }

    @Transactional
    public void finish(
            UUID id, UUID answer, String content, String status, List<Citation> citations) {
        jdbc.update(
                "UPDATE public.agent_message SET content=?,status=?,citations=CAST(? AS JSONB)"
                    + " WHERE id=? AND status='generating'",
                content,
                status,
                json(citations),
                answer);
        touch(id);
    }

    public void recover() {
        jdbc.update(
                "UPDATE public.agent_message SET status='interrupted' WHERE status='generating'");
    }

    private void touch(UUID id) {
        jdbc.update(
                "UPDATE public.agent_conversation SET updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }

    private Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Message message(ResultSet rs, int row) throws SQLException {
        try {
            String citationJson = rs.getString("citations");
            // H2's PostgreSQL compatibility mode exposes CAST(? AS JSONB) as a JSON string.
            if (citationJson.startsWith("\""))
                citationJson = mapper.readValue(citationJson, String.class);
            return new Message(
                    rs.getObject("id", UUID.class),
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getString("status"),
                    mapper.readValue(citationJson, new TypeReference<List<Citation>>() {}),
                    rs.getTimestamp("created_at").toInstant());
        } catch (java.io.IOException error) {
            throw new SQLException("Invalid stored citations", error);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }
}
