/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.repository.jdbc.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.repository.jdbc.orm.JdbcColumn;
import io.gravitee.repository.jdbc.orm.JdbcObjectMapper;
import io.gravitee.repository.management.api.IdentityProviderRepository;
import io.gravitee.repository.management.model.IdentityProvider;
import io.gravitee.repository.management.model.IdentityProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.lang.reflect.Array;
import java.sql.*;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static io.gravitee.repository.jdbc.common.AbstractJdbcRepositoryConfiguration.escapeReservedWord;
import static io.gravitee.repository.jdbc.orm.JdbcColumn.getDBName;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Repository
public class JdbcIdentityProviderRepository extends JdbcAbstractCrudRepository<IdentityProvider, String> implements IdentityProviderRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcIdentityProviderRepository.class);

    private final static ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final Rm mapper = new Rm();

    private static final JdbcObjectMapper ORM = JdbcObjectMapper.builder(IdentityProvider.class, "identity_providers", "id")
            .addColumn("id", Types.NVARCHAR, String.class)
            .addColumn("name", Types.NVARCHAR, String.class)
            .addColumn("description", Types.NVARCHAR, String.class)
            .addColumn("type", Types.NVARCHAR, IdentityProviderType.class)
            .addColumn("enabled", Types.BOOLEAN, boolean.class)
            .addColumn("created_at", Types.TIMESTAMP, Date.class)
            .addColumn("updated_at", Types.TIMESTAMP, Date.class)
            .build();

    private class Rm implements RowMapper<IdentityProvider> {
        @Override
        public IdentityProvider mapRow(ResultSet rs, int i) throws SQLException {
            IdentityProvider identityProvider = new IdentityProvider();
            ORM.setFromResultSet(identityProvider, rs);

            identityProvider.setConfiguration(convert(rs.getString("configuration"), Object.class, false));
            identityProvider.setGroupMappings(convert(rs.getString("group_mappings"), String.class, true));
            identityProvider.setRoleMappings(convert(rs.getString("role_mappings"), String.class, true));
            identityProvider.setUserProfileMapping(convert(rs.getString("user_profile_mapping"), String.class, false));

            return identityProvider;
        }

        private <T, C> Map<String, T> convert(String sMap, Class<C> valueType, boolean array) {
            TypeReference<HashMap<String, T>> typeRef
                    = new TypeReference<HashMap<String, T>>() {};
            if (sMap != null && ! sMap.isEmpty()) {
                try {
                    HashMap<String, Object> value = JSON_MAPPER.readValue(sMap, typeRef);
                    if (array) {
                        value
                                .forEach(new BiConsumer<String, Object>() {
                                    @Override
                                    public void accept(String s, Object t) {
                                        List<C> list = (List<C>) t;
                                        C[] arr = (C[]) Array.newInstance(valueType, list.size());
                                        arr = list.toArray(arr);
                                        value.put(s, arr);
                                    }
                                });
                    }

                    return (Map<String, T>) value;
                } catch (IOException e) {
                }
            }

            return null;
        }
    }

    private static class Psc implements PreparedStatementCreator {

        private final String sql;
        private final IdentityProvider identityProvider;
        private final Object[] ids;

        public Psc(String sql, IdentityProvider identityProvider, Object... ids) {
            this.sql = sql;
            this.identityProvider = identityProvider;
            this.ids = ids;
        }

        @Override
        public PreparedStatement createPreparedStatement(Connection cnctn) throws SQLException {
            LOGGER.debug("SQL: {}", sql);
            LOGGER.debug("identity_provider: {}", identityProvider);
            PreparedStatement stmt = cnctn.prepareStatement(sql);
            int idx = ORM.setStatementValues(stmt, identityProvider, 1);
            stmt.setString(idx++, convert(identityProvider.getConfiguration()));
            stmt.setString(idx++, convert(identityProvider.getGroupMappings()));
            stmt.setString(idx++, convert(identityProvider.getRoleMappings()));
            stmt.setString(idx++, convert(identityProvider.getUserProfileMapping()));

            for (Object id : ids) {
                stmt.setObject(idx++, id);
            }

            return stmt;
        }

        private String convert(Object object) {
            if (object != null) {
                try {
                    return JSON_MAPPER.writeValueAsString(object);
                } catch (JsonProcessingException e) {
                }
            }

            return null;
        }
    }

    private static String buildInsertStatement() {
        final StringBuilder builder = new StringBuilder("insert into identity_providers (");
        boolean first = true;
        for (JdbcColumn column : (List<JdbcColumn>) ORM.getColumns()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(escapeReservedWord(getDBName(column.name)));
        }
        builder.append(", configuration");
        builder.append(", group_mappings");
        builder.append(", role_mappings");
        builder.append(", user_profile_mapping");
        builder.append(" ) values ( ");
        first = true;
        for (int i = 0; i < ORM.getColumns().size(); i++) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append("?");
        }
        builder.append(", ?");
        builder.append(", ?");
        builder.append(", ?");
        builder.append(", ?");
        builder.append(" )");
        return builder.toString();
    }

    private static final String INSERT_SQL = buildInsertStatement();

    private static String buildUpdateStatement() {
        StringBuilder builder = new StringBuilder();
        builder.append("update identity_providers set ");
        boolean first = true;
        for (JdbcColumn column : (List<JdbcColumn>) ORM.getColumns()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(escapeReservedWord(getDBName(column.name)));
            builder.append(" = ?");
        }
        builder.append(", configuration = ?");
        builder.append(", group_mappings = ?");
        builder.append(", role_mappings = ?");
        builder.append(", user_profile_mapping = ?");

        builder.append(" where id = ?");
        return builder.toString();
    }

    private static final String UPDATE_SQL = buildUpdateStatement();

    @Override
    protected JdbcObjectMapper getOrm() {
        return ORM;
    }

    @Override
    protected String getId(IdentityProvider item) {
        return item.getId();
    }

    @Override
    protected RowMapper<IdentityProvider> getRowMapper() {
        return mapper;
    }

    @Override
    protected PreparedStatementCreator buildUpdatePreparedStatementCreator(IdentityProvider identityProvider) {
        return new Psc(UPDATE_SQL, identityProvider, identityProvider.getId());
    }

    @Override
    protected PreparedStatementCreator buildInsertPreparedStatementCreator(IdentityProvider identityProvider) {
        return new Psc(INSERT_SQL, identityProvider);
    }
}