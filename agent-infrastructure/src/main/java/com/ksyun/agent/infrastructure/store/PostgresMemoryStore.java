package com.ksyun.agent.infrastructure.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksyun.agent.core.exception.AgentErrorCode;
import com.ksyun.agent.core.exception.AgentFrameworkException;
import com.ksyun.agent.core.memory.MemoryCategory;
import com.ksyun.agent.core.memory.MemoryEntry;
import com.ksyun.agent.core.memory.MemoryItem;
import com.ksyun.agent.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL 长期记忆存储实现。
 * <p>
 * 行为与 {@link InMemoryMemoryStore} 的公开语义一致：
 * - 旧方法（基于 memoryId）抛 UnsupportedOperationException，不另建表、不实现兼容逻辑。
 * - put 使用 PostgreSQL 原子 upsert（ON CONFLICT），version 由数据库表达式 +1，
 *   保留原 memory_id / user_id / namespace / memory_key / created_at。
 * - get/list/delete 使用参数绑定，SQL 端按 user_id + namespace + memory_key 严格过滤。
 * <p>
 * 数据映射：
 * - category 明确映射为 MemoryCategory，未知值结构化失败为 MEMORY_STORE_FAILED。
 * - metadata 映射为 Map<String,String>，JSON 根必须是 object。
 * - Instant 与 timestamptz 时区安全映射。
 * <p>
 * 不添加 @Component/@Repository，通过 @Bean 装配。
 * 不缓存“当前用户”，不使用 ThreadLocal/static 锁。
 * 不启用 Jackson Default Typing，不保存 Java 类名或使用原生序列化。
 */
public class PostgresMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresMemoryStore.class);

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String UPSERT_SQL =
            "INSERT INTO long_term_memories "
                    + "(memory_id, user_id, namespace, memory_key, memory_value, category, metadata, version, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) "
                    + "ON CONFLICT (user_id, namespace, memory_key) DO UPDATE SET "
                    + "memory_value = EXCLUDED.memory_value, "
                    + "category = EXCLUDED.category, "
                    + "metadata = EXCLUDED.metadata, "
                    + "version = long_term_memories.version + 1, "
                    + "updated_at = EXCLUDED.updated_at "
                    + "RETURNING memory_id, user_id, namespace, memory_key, memory_value, category, metadata, version, created_at, updated_at";

    private static final String GET_SQL =
            "SELECT memory_id, user_id, namespace, memory_key, memory_value, category, metadata, version, created_at, updated_at "
                    + "FROM long_term_memories WHERE user_id = ? AND namespace = ? AND memory_key = ?";

    private static final String LIST_SQL =
            "SELECT memory_id, user_id, namespace, memory_key, memory_value, category, metadata, version, created_at, updated_at "
                    + "FROM long_term_memories WHERE user_id = ? AND namespace = ? ORDER BY memory_key ASC";

    private static final String DELETE_SQL =
            "DELETE FROM long_term_memories WHERE user_id = ? AND namespace = ? AND memory_key = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresMemoryStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    // --- 旧方法（基于 memoryId，不支持） ---

    @Override
    public void put(MemoryItem memoryItem) {
        throw new UnsupportedOperationException(
                "MemoryItem-based put is not supported; use MemoryEntry-based put instead");
    }

    @Override
    public Optional<MemoryItem> get(String userId, String memoryId) {
        throw new UnsupportedOperationException(
                "MemoryItem-based get is not supported; use MemoryEntry-based get instead");
    }

    @Override
    public List<MemoryItem> getByUserId(String userId) {
        throw new UnsupportedOperationException(
                "MemoryItem-based getByUserId is not supported; use MemoryEntry-based list instead");
    }

    @Override
    public void delete(String userId, String memoryId) {
        throw new UnsupportedOperationException(
                "MemoryItem-based delete is not supported; use MemoryEntry-based delete instead");
    }

    // --- 新方法（基于 namespace + key，长期记忆 upsert 语义） ---

    @Override
    public MemoryEntry put(MemoryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("MemoryEntry must not be null");
        }

        String metadataJson = serializeMetadata(entry.metadata());
        Timestamp createdAt = Timestamp.from(entry.createdAt());
        Timestamp updatedAt = Timestamp.from(entry.updatedAt());

        try {
            // 使用 ConnectionCallback，确保 PGobject 与连接绑定，
            // 同时让 RETURNING 在同一语句中读取数据库实际值（version 已 +1 等）。
            MemoryEntry stored = jdbcTemplate.execute((Connection con) -> {
                try (PreparedStatement ps = con.prepareStatement(UPSERT_SQL)) {
                    ps.setString(1, entry.memoryId());
                    ps.setString(2, entry.userId());
                    ps.setString(3, entry.namespace());
                    ps.setString(4, entry.key());
                    ps.setString(5, entry.value());
                    ps.setString(6, entry.category().name());
                    ps.setString(7, metadataJson);
                    ps.setLong(8, entry.version());
                    ps.setTimestamp(9, createdAt);
                    ps.setTimestamp(10, updatedAt);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new AgentFrameworkException(
                                    AgentErrorCode.MEMORY_STORE_FAILED,
                                    "Upsert returned no row");
                        }
                        return mapRow(rs);
                    }
                }
            });

            log.info("Memory upserted: namespace={}, version={}",
                    entry.namespace(), stored.version());
            return stored;
        } catch (AgentFrameworkException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to upsert long-term memory",
                    e);
        }
    }

    @Override
    public Optional<MemoryEntry> get(String userId, String namespace, String key) {
        if (userId == null || userId.isBlank()
                || namespace == null || namespace.isBlank()
                || key == null || key.isBlank()) {
            return Optional.empty();
        }

        try {
            MemoryEntry entry = jdbcTemplate.query(
                    GET_SQL,
                    rs -> rs.next() ? mapRow(rs) : null,
                    userId.trim(), namespace.trim(), key.trim());
            return Optional.ofNullable(entry);
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to read long-term memory",
                    e);
        }
    }

    @Override
    public Collection<MemoryEntry> list(String userId, String namespace) {
        if (userId == null || userId.isBlank()
                || namespace == null || namespace.isBlank()) {
            return List.of();
        }

        try {
            List<MemoryEntry> result = jdbcTemplate.query(
                    LIST_SQL,
                    (rs, rowNum) -> mapRow(rs),
                    userId.trim(), namespace.trim());
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to list long-term memory",
                    e);
        }
    }

    @Override
    public boolean delete(String userId, String namespace, String key) {
        if (userId == null || userId.isBlank()
                || namespace == null || namespace.isBlank()
                || key == null || key.isBlank()) {
            return false;
        }

        try {
            int affected = jdbcTemplate.update(
                    DELETE_SQL,
                    userId.trim(), namespace.trim(), key.trim());
            return affected > 0;
        } catch (Exception e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to delete long-term memory",
                    e);
        }
    }

    // ---- 数据映射 ----

    private MemoryEntry mapRow(ResultSet rs) throws SQLException {
        String memoryId = rs.getString("memory_id");
        String userId = rs.getString("user_id");
        String namespace = rs.getString("namespace");
        String key = rs.getString("memory_key");
        String value = rs.getString("memory_value");
        String categoryStr = rs.getString("category");
        Object metadataObj = rs.getObject("metadata");
        long version = rs.getLong("version");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        MemoryCategory category;
        try {
            category = MemoryCategory.valueOf(categoryStr);
        } catch (IllegalArgumentException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Unknown memory category in database");
        }

        if (version < 0) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Invalid memory version in database");
        }

        Map<String, String> metadata = deserializeMetadata(metadataObj);

        try {
            return new MemoryEntry(
                    memoryId, userId, namespace, key, value,
                    category, metadata, version, createdAt, updatedAt);
        } catch (IllegalArgumentException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Corrupt memory entry in database");
        }
    }

    private String serializeMetadata(Map<String, String> metadata) {
        try {
            Map<String, String> source = metadata == null ? Map.of() : metadata;
            // 使用 LinkedHashMap 保持稳定顺序，便于可读性与测试
            Map<String, String> ordered = new LinkedHashMap<>(source);
            return objectMapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Failed to serialize memory metadata");
        }
    }

    private Map<String, String> deserializeMetadata(Object metadataObj) {
        if (metadataObj == null) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Missing memory metadata in database");
        }

        // JSONB 可通过 getString 或 toString 读取为 JSON 字符串
        String json = metadataObj instanceof String s ? s : metadataObj.toString();

        if (json.isBlank()) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Empty memory metadata in database");
        }

        try {
            // 先解析为 JsonNode 校验根节点是 object
            var node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new AgentFrameworkException(
                        AgentErrorCode.MEMORY_STORE_FAILED,
                        "Memory metadata root is not a JSON object");
            }
            Map<String, String> map = objectMapper.readValue(json, MAP_TYPE);
            return Collections.unmodifiableMap(new LinkedHashMap<>(map));
        } catch (JsonProcessingException e) {
            throw new AgentFrameworkException(
                    AgentErrorCode.MEMORY_STORE_FAILED,
                    "Corrupt memory metadata in database");
        }
    }
}
