/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.*;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

/**
 * java.sql.ResultSet 的 包装器，可以理解成 ResultSet 的工具类，提供给 DefaultResultSetHandler 使用
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

  /**
   * 底层封装的 ResultSet 对象
   */
  private final ResultSet resultSet;
  private final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 记录了 ResultSet 每列的列名
   */
  private final List<String> columnNames = new ArrayList<>();
  /**
   * 记录了 ResultSet 每列对应的 Java 类型
   */
  private final List<String> classNames = new ArrayList<>();
  /**
   * 记录了 ResultSet 每列对应的 JdbcType 类型
   */
  private final List<JdbcType> jdbcTypes = new ArrayList<>();
  /**
   * 记录了 ResultSet 每列对应的 TypeHandler 对象
   * key：列名
   * value：TypeHandler映射集合
   */
  private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
  /**
   * 记录了被映射的列名（列名大写）。
   * key：ResultMap对象的id
   * value：ResultMap对象映射的列名集合
   */
  private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
  /**
   * 记录了未映射的列名（列名大写）。
   * key：ResultMap对象的id
   * value：ResultMap对象未映射的列名集合
   */
  private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

  /**
   * 构造器
   * 初始化对象属性 typeHandlerRegistry、resultSet、
   * @param rs ResultSet对象
   * @param configuration 配置对象
   * @throws SQLException
   */
  public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
    super();
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.resultSet = rs;
    //1.获取 resultSet 结果集对象的 元信息
    final ResultSetMetaData metaData = rs.getMetaData();
    //2.从元信息获取结果集的 列数
    final int columnCount = metaData.getColumnCount();
    //3.遍历每一列
    for (int i = 1; i <= columnCount; i++) {
      //4.从 元信息 获取列名添加到columnNames集合
      columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
      //5.从 元信息 获取列类型添加到jdbcTypes集合
      jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
      //6.从 元信息 获取列对应的java类型添加到classNames集合
      classNames.add(metaData.getColumnClassName(i));
    }
  }

  public ResultSet getResultSet() {
    return resultSet;
  }

  public List<String> getColumnNames() {
    return this.columnNames;
  }

  public List<String> getClassNames() {
    return Collections.unmodifiableList(classNames);
  }

  public List<JdbcType> getJdbcTypes() {
    return jdbcTypes;
  }

  /**
   * 根据列名获取其对应的JdbcType
   * @param columnName 列名
   * @return
   */
  public JdbcType getJdbcType(String columnName) {
    for (int i = 0 ; i < columnNames.size(); i++) {
      if (columnNames.get(i).equalsIgnoreCase(columnName)) {
        return jdbcTypes.get(i);
      }
    }
    return null;
  }

  /**
   * 获得指定字段名的指定 JavaType 类型的 TypeHandler 对象
   * [1].先从 typeHandlerMap 缓存获取指定列名指定java类型的 TypeHandler
   * [2].后直接从 typeHandlerRegistry 获取指定 java类型和指定jdbc类型的 TypeHandler
   *    [2.1]先根据传入的java类型和列名对应的jdbc类型获取 TypeHandler
   *    [2.2]后根据元信息中的java类型和列名对应的jdbc类型获取 TypeHandler
   *    [2.3]最后仍然没有获取到则直接返回ObjectTypeHandler
   * Gets the type handler to use when reading the result set.
   * Tries to get from the TypeHandlerRegistry by searching for the property type.
   * If not found it gets the column JDBC type and tries to get a handler for it.
   *
   * @param propertyType java类型class对象
   * @param columnName 列名
   * @return TypeHandler对象
   */
  public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
    TypeHandler<?> handler = null;
    //1.从缓存的 typeHandlerMap 中获得指定字段名的 TypeHandler映射集合
    Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
    //2.如果映射集合 columnHandlers 为空，则添加空映射集合到 typeHandlerMap
    if (columnHandlers == null) {
      columnHandlers = new HashMap<>();
      typeHandlerMap.put(columnName, columnHandlers);
    } else {
      //2.如果映射集合 columnHandlers 不为空，则根据指定 java 类型获取 TypeHandler 对象[1]
      handler = columnHandlers.get(propertyType);
    }
    //3.如果 handler 为空，即缓存查找不到则从typeHandlerRegistry查找
    if (handler == null) {
      //3.1获取对应的jdbc类型
      JdbcType jdbcType = getJdbcType(columnName);
      //3.2从typeHandlerRegistry获取指定java类型和指定jdbc类型的handler[2.1]
      handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
      // Replicate logic of UnknownTypeHandler#resolveTypeHandler
      // See issue #59 comment 10
      //3.3如果获取不到handler 或 handler类型为UnknownTypeHandler
      if (handler == null || handler instanceof UnknownTypeHandler) {
        //使用 结果集元信息 中的classNames类型，继续进行查找 TypeHandler 对象[2.2]
        final int index = columnNames.indexOf(columnName);
        final Class<?> javaType = resolveClass(classNames.get(index));
        if (javaType != null && jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
        } else if (javaType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType);
        } else if (jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(jdbcType);
        }
      }
      //3.4如果获取不到handler 或 handler类型为UnknownTypeHandler，则使用 ObjectTypeHandler[2.3]
      if (handler == null || handler instanceof UnknownTypeHandler) {
        handler = new ObjectTypeHandler();
      }
      //4.缓存到 typeHandlerMap 中
      columnHandlers.put(propertyType, handler);
    }
    return handler;
  }

  /**
   * 解析类名为class对象
   * @param className 全类名
   * @return
   */
  private Class<?> resolveClass(String className) {
    try {
      // #699 className could be null
      if (className != null) {
        return Resources.classForName(className);
      }
    } catch (ClassNotFoundException e) {
      // ignore
    }
    return null;
  }

  /**
   * 加载指定 结果映射 的 被映射的列名集合 和 未被映射集合
   * 并保存到对象属性中的映射集合 mappedColumnNamesMap 和 unMappedColumnNamesMap
   * @param resultMap ResultMap对象
   * @param columnPrefix ResultMap对象所有结果映射的列名前缀
   * @throws SQLException
   */
  private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    //分别记录ResultMap对应的映射列名和未映射列名
    List<String> mappedColumnNames = new ArrayList<>();
    List<String> unmappedColumnNames = new ArrayList<>();
    //1.列名前缀大写
    final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
    //2.拼接resultMap定义的列名和前缀，得到实际完整的列名
    final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
    //3.遍历结果集中的所有列名 columnNames 集合
    for (String columnName : columnNames) {
      //4.大写列名
      final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
      //5.如果结果映射中映射列名集合包含此列名，则将列名添加到 mappedColumnNames
      if (mappedColumns.contains(upperColumnName)) {
        mappedColumnNames.add(upperColumnName);
      } else {
        //5.如果结果映射中映射列名集合不包含此列名，则将列名添加到 unmappedColumnNames
        unmappedColumnNames.add(columnName);
      }
    }
    //6.初始化集合属性元素（列名全部大写）
    mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
    unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
  }

  /**
   * 从mappedColumnNamesMap获取指定 ResultMap 对应的 映射列名集合
   * @param resultMap ResultMap对象
   * @param columnPrefix ResultMap对象所有结果映射的列名前缀
   * @return
   * @throws SQLException
   */
  public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    //1.在 mappedColumnNamesMap 集合中查找被映射的列名集合，其中key是由ResultMap的id与列前缀组成
    List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    //2.如果未查找到指定 ResultMap 的映射列名集合，则加载后存入 mappedColumnNamesMap和unMappedColumnNamesMap 集合
    if (mappedColumnNames == null) {
      //2.1加载 mappedColumnNamesMap和unMappedColumnNamesMap 集合数据
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      //2.2获取指定 ResultMap 的映射列名集合
      mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return mappedColumnNames;
  }

  /**
   * 从unMappedColumnNamesMap获取指定 ResultMap 对应的 未映射列名集合
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    if (unMappedColumnNames == null) {
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return unMappedColumnNames;
  }

  /**
   * 获取以 resultMap的id + “：” + 列名前缀 的键
   * @param resultMap
   * @param columnPrefix
   * @return
   */
  private String getMapKey(ResultMap resultMap, String columnPrefix) {
    return resultMap.getId() + ":" + columnPrefix;
  }

  /**
   * 给列名集合columnNames所有元素拼接前缀
   * @param columnNames resultMap的列名集合
   * @param prefix 前缀
   * @return
   */
  private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
    //1.直接返回columnNames，如果符合如下任一情况
    if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
      return columnNames;
    }
    //2.遍历映射的列名集合拼接前缀，然后返回
    final Set<String> prefixed = new HashSet<>();
    for (String columnName : columnNames) {
      prefixed.add(prefix + columnName);
    }
    return prefixed;
  }

}
