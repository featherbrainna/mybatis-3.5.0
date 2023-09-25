/**
 *    Copyright 2009-2018 the original author or authors.
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

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 实现 ResultSetHandler 接口，默认唯一的 ResultSetHandler 实现类
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERRED = new Object();

  //关联的 Executor、Configuration、MappedStatement、RowBounds 对象
  private final Executor executor;
  private final Configuration configuration;
  private final MappedStatement mappedStatement;
  private final RowBounds rowBounds;
  /**
   * 参数处理器
   */
  private final ParameterHandler parameterHandler;
  /**
   * 用户指定的用于处理结果的处理器
   * 一般情况下不设置
   */
  private final ResultHandler<?> resultHandler;
  private final BoundSql boundSql;
  private final TypeHandlerRegistry typeHandlerRegistry;
  private final ObjectFactory objectFactory;
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  //存储过程相关的的多 ResultSet 涉及的属性，暂时可忽略
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  /**
   * 自动映射的缓存
   * KEY：{@link ResultMap#getId()} + ":" +  columnPrefix
   * @see #createRowKeyForUnmappedProperties(ResultMap, ResultSetWrapper, CacheKey, String)
   */
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  /**
   * 是否用构造方法创建该结果对象
   */
  private boolean useConstructorMappings;

  private static class PendingRelation {
    public MetaObject metaObject;
    public ResultMapping propertyMapping;
  }

  /**
   * DefaultResultSetHandler 的内部静态类，未映射列名自动映射后生成的映射关系对象
   */
  private static class UnMappedColumnAutoMapping {
    /**
     * 列名（结果集中未映射的列）
     */
    private final String column;
    /**
     * 属性名（结果对象中未映射的属性）
     */
    private final String property;
    /**
     * TypeHandler处理器
     */
    private final TypeHandler<?> typeHandler;
    /**
     * 是否为基本属性
     */
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  /**
   * 构造器。由Configuration调用创建 ResultSetHandler 对象，再由 BaseStatementHandler 调用 configuration
   * @param executor 执行器对象
   * @param mappedStatement MappedStatement对象
   * @param parameterHandler 参数处理器
   * @param resultHandler 结果处理器
   * @param boundSql boundSql对象
   * @param rowBounds 行限制对象
   */
  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //

  /**
   * 处理多个 java.sql.ResultSet 结果集，转换成映射的对应结果对象集合
   * [1].从Statement获取ResultSet并封装为ResultSetWrapper
   * [2].从MappedStatement获取ResultMap
   * [3].调用handleResultSet方法处理单个结果集
   * @param stmt Statement对象
   * @return 结果集对象
   * @throws SQLException
   */
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

    //多ResultSet的结果集合。每个Object对应一个ResultSet，每个obejct是List<Object>结果集对象
    // 在不考虑存储过程时，multipleResults只有一个元素
    final List<Object> multipleResults = new ArrayList<>();

    int resultSetCount = 0;
    //1.从 Statement 对象获取第一个 ResultSet 对象，并封装成 ResultSetWrapper 对象[1]
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    //2.从 mappedStatement 获取 ResultMap 集合
    // 在不考虑存储过程的多 ResultSet 的情况，普通的查询就一个 ResultMap 元素[2]
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    //获取结果映射resultMaps大小，一般为 1 个
    int resultMapCount = resultMaps.size();
    //校验至少有一个 ResultMap 对象。如果rsw不为null且resultMapCount<1，则抛出异常
    validateResultMapsCount(rsw, resultMapCount);
    //3.遍历 resultMaps 集合
    while (rsw != null && resultMapCount > resultSetCount) {
      //4.从resultMaps获取对应的 ResultMap 对象[2]
      ResultMap resultMap = resultMaps.get(resultSetCount);
      //5.处理单个 ResultSet ，并将结果添加到 multipleResults 中[3]
      handleResultSet(rsw, resultMap, multipleResults, null);
      //6.获从 Statement 对象获取下一个 ResultSet 对象，并封装成 ResultSetWrapper[1]
      rsw = getNextResultSet(stmt);
      //7.清理 nestedResultObjects 集合
      cleanUpAfterHandlingResultSet();
      resultSetCount++;
    }

    //8.从mappedStatement获取SQL节点的 resultSets 属性（仅适用于多结果集的存储过程）
    String[] resultSets = mappedStatement.getResultSets();
    //9.如果 resultSets 属性为不空，即有设置（处理嵌套的结果映射）
    if (resultSets != null) {
      //9.1如果 rsw 不为空且resultSetCount小于resultSets大小，继续处理结果集
      while (rsw != null && resultSetCount < resultSets.length) {
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          //处理单个结果集
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        rsw = getNextResultSet(stmt);
        cleanUpAfterHandlingResultSet();
        resultSetCount++;
      }
    }

    //10.如果 multipleResults 只有单个元素，则取首元素返回
    return collapseSingleResultList(multipleResults);
  }

  /**
   * 处理 java.sql.ResultSet 成 Cursor 对象，最终创建成 DefaultCursor 对象
   * 1.从Statement获取ResultSet并封装为ResultSetWrapper
   * 2.从MappedStatement获取唯一的ResultMap
   * 3.封装ResultSetWrapper、ResultMap等直接创建DefaultCursor对象
   * @param stmt Statement对象
   * @param <E> 结果对象类型
   * @return Cursor对象
   * @throws SQLException
   */
  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    //1.从Statement获得首个 ResultSet 对象，并封装成 ResultSetWrapper
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    //2.从 mappedStatement 获取结果映射集合。在不考虑存储过程的多 ResultSet 的情况，普通的查询就一个 ResultMap 元素
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    //3.获取当前SQL节点的结果映射集合大小
    int resultMapCount = resultMaps.size();
    //校验大小
    validateResultMapsCount(rsw, resultMapCount);
    //4.如果结果映射集合大小不为1，抛出异常。即多 结果集存储过程 不支持 游标结果
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    //5.如果结果映射集合大小为1，获取唯一的 结果映射对象
    ResultMap resultMap = resultMaps.get(0);
    //6.创建 DefaultCursor 对象返回，封装了结果集处理器、结果映射对象、结果集、行限制
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  /**
   * 从 Statement 对象获取第一个结果集对象，并封装为ResultSetWrapper
   * @param stmt Statement对象
   * @return ResultSetWrapper对象
   * @throws SQLException
   */
  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    //1.从 Statement 获取结果集ResultSet
    ResultSet rs = stmt.getResultSet();
    //2.如果获取到的结果集为空，则通过Statement.getMoreResults()确认是否有下一个结果集
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      //如果有更多结果集，则获取结果集
      if (stmt.getMoreResults()) {
        rs = stmt.getResultSet();
      } else {
        //没有更多结果集，则跳出循环
        if (stmt.getUpdateCount() == -1) {
          // no more results. Must be no resultset
          break;
        }
      }
    }
    //3.将结果集封装为 ResultSetWrapper 对象
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  /**
   * 从 Statement 对象获取下一个结果集对象，并封装为ResultSetWrapper
   * @param stmt Statement对象
   * @return ResultSetWrapper对象
   */
  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    try {
      //1.如果数据库支持多结果集
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
        //2.如果有更多结果集
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          //2.1获取结果集ResultSet对象
          ResultSet rs = stmt.getResultSet();
          //2.2如果结果集为空，继续获取结果集
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            //2.2如果结果集不为空，封装为 ResultSetWrapper 对象返回
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }

  /**
   * 关闭 ResultSet 对象
   * @param rs ResultSet对象
   */
  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  /**
   * 完成对单个 ResultSet 映射
   * 按照结果映射处理 ResultSet ，将结果添加到 multipleResults 中
   * 底层调用 {@link #handleRowValues(ResultSetWrapper, ResultMap, ResultHandler, RowBounds, ResultMapping)} 处理结果集
   *
   * 使用默认的 DefaultResultHandler 对象，最终会将 defaultResultHandler 的处理的结果，到 multipleResults 中。
   * 而使用自定义的 resultHandler ，不会添加到 multipleResults 中
   * @param rsw 结果集包装器
   * @param resultMap 结果映射
   * @param multipleResults 结合集合
   * @param parentMapping 父映射关系
   * @throws SQLException
   */
  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      //1.如果 parentMapping 不为空，存储过程的嵌套结果集处理，将处理后的结果设置到父结果中
      if (parentMapping != null) {
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else {
        //2.如果 parentMapping 为空，依赖 ResultHandler 来处理结果集
        //2.如果 resultHandler 为空
        if (resultHandler == null) {
          //2.1创建 DefaultResultHandler 对象
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          //2.2使用 DefaultResultHandler 处理 resultSet 结果集的每一行，并将结果对象添加到 defaultResultHandler 对象暂存
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          //2.3添加 defaultResultHandler 的处理结果到 multipleResults 中
          multipleResults.add(defaultResultHandler.getResultList());
        } else {
          //2.如果 resultHandler 不为空，则使用用户自定义的resultHandler处理 resultSet 结果集的每一行
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      // issue #228 (close resultsets)
      // 关闭 ResultSet 对象
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    //multipleResults集合只有一个元素，取首元素返回；multipleResults集合有多个元素，直接返回
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  /**
   * 处理 ResultSet 返回的每一行 Row。结果映射的【核心方法】，包含两个分支：
   * 1.处理嵌套结果映射(结果映射对象其中映射关系有resultMap属性，也可以有嵌套查询与多结果集)
   * 2.处理非嵌套映射的简单结果映射 [!!!包含 内嵌select查询的情况 和 多结果集的情况，但是没有嵌套的结果映射！！！]
   * 底层路由给 handleRowValuesForNestedResultMap 或 handleRowValuesForSimpleResultMap 方法处理
   * @param rsw
   * @param resultMap
   * @param resultHandler
   * @param rowBounds
   * @param parentMapping
   * @throws SQLException
   */
  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    //1.处理 嵌套结果映射 的情况
    if (resultMap.hasNestedResultMaps()) {
      //1.1校验不要使用 RowBounds
      ensureNoRowBounds();
      //1.2校验不要使用自定义的 ResultHandler
      checkResultHandler();
      //1.3处理嵌套的结果映射的结果集
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      //2.处理简单映射的情况
      //处理简单结果映射的结果集
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  /**
   * 检验不要使用 RowBounds
   * 该检验只有全局配置文件 safeRowBoundsEnabled 为true，且rowBounds对象不为空，且rowBounds属性其中一个不为默认值才抛出异常
   * 默认 safeRowBoundsEnabled 为false，所以不经过此检验
   */
  private void ensureNoRowBounds() {
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
          + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  /**
   * 检验不要使用自定义的 ResultHandler
   */
  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  /**
   * 处理简单结果映射
   * 遍历 resultSet 结果集所有行
   * 0.准备一个结果对象上下文 DefaultResultContext
   * 1.调用skipRows()方法，使resultSet跳转到RowBounds的offset行
   * 2.调用shouldProcessMoreRows()方法，检测是否还有需要映射的记录
   * 3.通过resolveDiscriminatedResultMap()方法，解析discriminator确定最终的 ResultMap
   * 4.通过getRowValue()方法，对ResultSet中的一行进行映射获取结果对象
   * 5.通过storeObject()方法，处理结果对象
   * @param rsw 结果集包装器
   * @param resultMap 结果映射
   * @param resultHandler 结果处理器
   * @param rowBounds 行限制
   * @param parentMapping 父结果映射关系
   * @throws SQLException
   */
  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {
    //1.创建 DefaultResultContext 对象。用于记录当前映射获取的结果对象
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    //2.从ResultSetWrapper获取  ResultSet 对象
    ResultSet resultSet = rsw.getResultSet();
    //3.步骤1：根据 rowBounds 的offset跳转到结果集指定开始行
    skipRows(resultSet, rowBounds);
    //4.遍历 resultSet 所有行
    // 步骤2：循环条件：1.根据生成的结果对象上下文判断是否继续处理 ResultSet，2.根据结果集是否关闭，3.根据结果集是否有待处理行
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      //5.步骤3：根据结果集的行记录以及 resultMap.discriminator，决定映射使用的 ResultMap 对象
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      //6.步骤4：根据最终确定的 ResultMap 对象对 ResultSet 中的该行记录进行映射，得到映射后的结果对象
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      //7.步骤5：将映射创建的结果对象添加到 resultHandler.resultList 中保存
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
    }
  }

  /**
   * 将映射创建的结果对象添加到 ResultHandler.resultList 中保存
   * @param resultHandler 结果处理器
   * @param resultContext DefaultResultContext对象
   * @param rowValue 一行对应的结果对象
   * @param parentMapping 父映射关系
   * @param rs 结果集
   * @throws SQLException
   */
  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    //如果是多结果集的存储过程，将结果保存到父对象的属性中
    if (parentMapping != null) {
      linkToParents(rs, parentMapping, rowValue);
    } else {
      //如果是 嵌套映射 或 普通映射，将结果对象保存到resultHandler中
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  /**
   * 调用 resultHandler 对象方法。用于保存结果对象
   * @param resultHandler 结果处理器
   * @param resultContext DefaultResultContext结果对象上下文（计数和获取当前结果对象）
   * @param rowValue 当前结果对象
   */
  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    //1.设置保存当前结果对象到结果对象上下文
    resultContext.nextResultObject(rowValue);
    //2.使用resultHandler处理结果
    // 如果使用 DefaultResultHandler 实现类的情况，会将映射创建的结果对象添加到 ResultHandler.resultList 中保存
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  /**
   * 根据 ResultContext 上下文和 RowBounds行限制，来判断是否还有更多的行要处理
   * @param context ResultContext上下文对象
   * @param rowBounds 行限制对象
   * @return
   */
  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    //1.检测context是否停止，2.检测映射行数是否达到了RowBounds.limit的限制
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  /**
   * 跳到结果集行限制的开头Offset
   * @param rs 结果集
   * @param rowBounds 行限制对象
   * @throws SQLException
   */
  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      //如果结果集类型不为 ResultSet.TYPE_FORWARD_ONLY
      //调用 rs.absolute(...) 直接跳转到目标行
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      //如果结果集类型为 ResultSet.TYPE_FORWARD_ONLY （只能下移）
      //以 rowBounds 的 Offset 为上限，循环通过 rs.next() 来逐行调整游标
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        if (!rs.next()) {
          break;
        }
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //

  /**
   * 根据简单结果映射 ResultMap 对 ResultSet 中的当前行记录进行映射，得到映射后的结果对象
   * 0.准备一个延迟加载的ResultLoaderMap用于缓存延迟加载对象
   * 1.通过构造器createResultObject()创建结果对象。根据 ResultMap 指定的类型创建对应的结果对象，以及对应的 MetaObject对象
   * 2.通过applyAutomaticMappings()方法应用自动映射到结果对象
   * 3.通过applyPropertyMappings()方法应用简单结果映射到结果对象
   * 4.返回结果对象
   * @param rsw 结果集包装对象
   * @param resultMap 结果映射
   * @param columnPrefix 列名前缀
   * @return 结果对象
   * @throws SQLException
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    //1.创建 ResultLoaderMap 对象。延迟加载相关
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    //2.步骤1：创建结果映射定义的映射后的结果对象，是一个（除构造器设置的属性外）未设置属性的对象（resultMap节点的type属性对象）
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    //3.如果结果对象 rowValue 不为空且hasTypeHandlerForResultObject方法返回false(true意味着rowValue是基本类型，无需执行下列逻辑)
    // 即rowValue不为空且不为基本类型
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      //3.1创建rowValue对应的 MetaObject，用于访问rowValue对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      //3.2获取 useConstructorMappings 属性
      boolean foundValues = this.useConstructorMappings;
      //3.3判断resultMap是否开启自动映射功能
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        //3.3步骤2：自动映射未明确指定映射的列，设置结果对象
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      //3.4步骤3：映射 resultMap 中明确的列，设置结果对象
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      //3.5↑↑↑ 至此，当前 ResultSet 的该行记录的数据，已经完全映射到结果对象 rowValue 的对应属性中
      foundValues = lazyLoader.size() > 0 || foundValues;
      //3.6步骤4：如果没有成功的映射任何属性，则置空rowValue对象
      // 当然，如果开启 `configuration.returnInstanceForEmptyRow` 属性，则不置空。默认情况下，该值为 false
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  /**
   * 结果映射是否开启自动映射（简单查询和内嵌查询都会调用本方法）
   * 1.优先从 resultMap 节点的 autoMapping 属性获取boolean值
   * 2.后从 configuration 获取 autoMappingBehavior 设置，根据是否嵌套对比
   * @param resultMap 结果映射
   * @param isNested 是否有嵌套
   * @return
   */
  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    if (resultMap.getAutoMapping() != null) {
      return resultMap.getAutoMapping();
    } else {
      if (isNested) {
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  /**
   * 应用传入的结果映射到结果对象（简单查询和内嵌查询都会调用本方法）（涉及延迟加载和嵌套结果映射）
   * 处理了：1.结果映射中映射关系的嵌套select查询，2.结果映射中映射关系的resultSet属性多结果集
   * 没有处理：结果映射中映射关系的嵌套结果映射，交由 {@link #applyNestedResultMappings(ResultSetWrapper, ResultMap, MetaObject, String, CacheKey, boolean)} 处理
   * @param rsw 结果集包装器
   * @param resultMap 结果映射
   * @param metaObject 结果对象的MetaObject
   * @param lazyLoader ResultLoaderMap对象
   * @param columnPrefix 列名前缀
   * @return 是否应用成功
   * @throws SQLException
   */
  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    //1.从结果集包装器获取已映射列名集合（全大写）
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    boolean foundValues = false;
    //2.获取结果映射的映射关系集合，遍历映射关系集合（该集合不包含带构造器标签的映射关系）
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    //遍历属性结果映射的映射关系
    for (ResultMapping propertyMapping : propertyMappings) {
      //3.从映射关系中获取列名
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      //4.如果映射关系有嵌套结果映射，则列名置空
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
        column = null;
      }
      //5.下面逻辑主要处理三种场景
      // 场景1：column是"{prop=col1,prop=col2}"这种形式的，与嵌套查询select配合使用（即column有多个列名）
      // 场景2：列名不为空且属于已映射列名（基本类型的属性映射）
      // 场景3；映射关系有resultSet属性，即多结果集场景处理（存储过程相关）
      if (propertyMapping.isCompositeResult()//---场景1内嵌select
          || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))//---场景2普通
          || propertyMapping.getResultSet() != null) {//---场景3多结果集
        //5.1从结果集获取列名对应的值，【核心方法】
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        // issue #541 make property optional
        //5.2获取映射关系的属性名
        final String property = propertyMapping.getProperty();
        if (property == null) {
          continue;
        } else if (value == DEFERRED) {
          //DEFERRED表示占位符对象，存储过程相关忽略（不立即设置结果对象的属性值如5.3，而是直接结束以实现延迟加载）
          foundValues = true;
          continue;
        }
        //标记获取到任一属性
        if (value != null) {
          foundValues = true;
        }
        //5.3将值设置到结果对象
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(property, value);
        }
      }
    }
    return foundValues;
  }

  /**
   * 从结果集获取映射关系对应的属性值（注意不包括内嵌的结果映射resultMap）
   * 1.映射关系有内嵌select查询，委托 {@link #getNestedQueryMappingValue(ResultSet, MetaObject, ResultMapping, ResultLoaderMap, String)}
   * 2.映射关系有resultSet顺序，委托 {@link #addPendingChildRelation(ResultSet, MetaObject, ResultMapping)}
   * 3.其它委托，委托 {@link TypeHandler}
   * @param rs 结果集
   * @param metaResultObject 结果对象的MetaObject
   * @param propertyMapping 映射关系
   * @param lazyLoader lazyLoader对象
   * @param columnPrefix 列名前缀
   * @return 属性值
   * @throws SQLException
   */
  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    //1.如果映射关系有 select 属性，即内嵌的select查询，则委托getNestedQueryMappingValue方法
    if (propertyMapping.getNestedQueryId() != null) {
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    } else if (propertyMapping.getResultSet() != null) {
      //2.如果射关系有 resultSet 属性，即存储过程相关，则委托addPendingChildRelation方法
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      return DEFERRED;
    } else {
      //3.其它普通情况，直接获取指定列名的值
      //3.1从映射关系ResultMapping获取 TypeHandler 对象
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      //3.2从映射关系获取列名，并拼接前缀
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      //3.3使用typeHandler对象获取属性值
      return typeHandler.getResult(rs, column);
    }
  }

  /**
   * 创建自动映射关系集合
   * 遍历未 mapped 的列名的集合，映射每一个列名在结果对象的相同名字的属性，最终生成 UnMappedColumnAutoMapping 对象
   * @param rsw 结果集包装器
   * @param resultMap 结果映射
   * @param metaObject 结果对象的MetaObject
   * @param columnPrefix 列前缀
   * @return 自动映射关系集合
   * @throws SQLException
   */
  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    //1.生成 自动映射缓存 的key。其同时为ResultSetWrapper中mappedColumnNamesMap和unMappedColumnNamesMap的key
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    //2.从 autoMappingsCache 缓存获取自动映射关系UnMappedColumnAutoMapping集合
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    //3.如果从缓存获取不到，则进行初始化
    if (autoMapping == null) {
      autoMapping = new ArrayList<>();
      //3.1从结果集包装器获取映射关系 未映射的列名集合
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      //3.2遍历未映射列名集合 unmappedColumnNames
      for (String columnName : unmappedColumnNames) {
        //3.3用列名先初始化属性名
        String propertyName = columnName;
        //3.4如果列名前缀不为空且长度不为0
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          //如果列名以列名前缀开头，去除列名前缀初始化 属性名
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            continue;
          }
        }
        //3.5依据下划线转驼峰命名规则，查找结果对象metaObject对应的属性名
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        //3.6获取到对应的属性名且可以设置属性
        if (property != null && metaObject.hasSetter(property)) {
          //排除已映射属性
          if (resultMap.getMappedProperties().contains(property)) {
            continue;
          }
          //3.6.1获取属性名对应的属性类型
          final Class<?> propertyType = metaObject.getSetterType(property);
          //3.6.2判断是否有对应的TypeHandler，如果有则创建UnMappedColumnAutoMapping并添加到
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            //3.6.3如果没有对应的TypeHandler，则执行 AutoMappingUnknownColumnBehavior 对应的逻辑
            configuration.getAutoMappingUnknownColumnBehavior()
                .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          //3.6如果没有对应属性，或者无法设置，则则执行 AutoMappingUnknownColumnBehavior 对应的逻辑
          configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      //3.7添加到缓存中
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  /**
   * 应用自动映射到结果对象。为自动映射关系的结果属性设置值（简单查询和内嵌查询都会调用本方法）
   * @param rsw 结果集封装对象
   * @param resultMap 结果映射
   * @param metaObject 结果对象对应的MetaObject
   * @param columnPrefix 列名前缀
   * @return 是否应用成功
   * @throws SQLException
   */
  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    //1.通过rsw和resultMap获取 UnMappedColumnAutoMapping 集合。
    // 即获取ResultSet中存在，但ResultMap中没有明确映射的列所对应的 UnMappedColumnAutoMapping 集合
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    boolean foundValues = false;
    //2.如果UnMappedColumnAutoMapping自动映射关系集合不为空，进行映射处理
    if (!autoMapping.isEmpty()) {
      //2.1遍历自动映射关系集合
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        //2.2依赖typeHandler从结果集获取指定列名的值
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        if (value != null) {
          foundValues = true;
        }
        //2.3如果值不为空 或 callSettersOnNulls属性设置为true且映射关系不是基本类型
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          //2.3将值设置到结果对象中
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMapping);
    if (columns != null && names != null) {
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          cacheKey.update(namesArray[i]);
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  /**
   * 创建映射后的结果对象（未设置映射关系的结果对象）（简单查询和内嵌查询都会调用本方法）
   * 延迟加载的代理结果对象
   * @param rsw 结果集包装器
   * @param resultMap 结果映射
   * @param lazyLoader ResultLoaderMap对象
   * @param columnPrefix 列名前缀
   * @return 结果对象
   * @throws SQLException
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    //1.useConstructorMappings表示是否使用构造器方法创建该结果对象，【此处将其重置】
    this.useConstructorMappings = false; // reset previous mapping result
    //2.记录使用的构造方法的参数类型的数组
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    //3.记录使用的构造方法的参数值的数组
    final List<Object> constructorArgs = new ArrayList<>();
    //4.使用底层重载的方法创建映射后的结果对象，核心步骤
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    //5.如果 结果对象不为空 且 不是基本数据类型【延迟加载相关】
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      //5.1获取resultMap的非constructor得映射关系集合
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      //5.2遍历映射关系集合
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
        //5.3如果propertyMapping映射关系中有嵌套的查询 且 开启了延迟加载，
        // 则使用ProxyFactory创建结果对象的代理对象。默认使用的是CglibProxyFactory。
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    //6.初始化 useConstructorMappings 属性，判断是否使用构造方法创建该结果对象【此处初始化】
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    //7.返回结果对象
    return resultObject;
  }

  /**
   * 底层创建映射后的结果对象（未设置映射关系的结果对象）
   * @param rsw 结果集包装器
   * @param resultMap 结果映射
   * @param constructorArgTypes 构造器参数类型集合
   * @param constructorArgs 构造器参数值集合
   * @param columnPrefix 列名前缀
   * @return 结果对象
   * @throws SQLException
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
      throws SQLException {
    //1.从结果映射获取 结果对象类型
    final Class<?> resultType = resultMap.getType();
    //2.获取结果类型对应的 MetaClass 对象
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    //3.从结果映射获取 带构造器标志的映射关系集合
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    //下面，分四种创建结果对象的情况
    //4.情况一：如果结果集只有一列，结果类型有对应的 TypeHandler 对象，则意味着是基本类型，直接创建结果对象
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
    } else if (!constructorMappings.isEmpty()) {
      //4.情况二：如果 ResultMap 中定义了 constructor 节点，则通过反射调用该构造方法，创建对应结果对象
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      //4.情况三：如果结果对象类型为接口类型或有默认的无参构造方法，则使用该构造方法，创建对应结果对象
      return objectFactory.create(resultType);
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      //4.情况四：通过自动映射的方式查找合适的构造方法，后使用该构造方法创建对应的结果对象
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
    }
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  /**
   * 通过 结果映射指定的 构造器反射对象创建结果对象
   * constructor节点中的映射关系没有 resultSet 属性（即没有存储过程相关）
   * @param rsw 结果集包装器
   * @param resultType 结果类型
   * @param constructorMappings 构造器标签的映射关系集合
   * @param constructorArgTypes 构造器参数类型集合
   * @param constructorArgs 构造器参数值集合
   * @param columnPrefix 列名前缀
   * @return 结果对象
   */
  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    //获得到任一结果对象的属性值，即只要一个结果对象有一个属性非空，就会设置为true
    boolean foundValues = false;
    //1.遍历带构造器标签的映射关系集合
    for (ResultMapping constructorMapping : constructorMappings) {
      //2.获取映射关系的java类型
      final Class<?> parameterType = constructorMapping.getJavaType();
      //3.获取映射关系的列名
      final String column = constructorMapping.getColumn();
      final Object value;
      try {
        //4.如果映射关系 select 属性不为空，是内嵌的select查询，则获取内嵌的值（这里不延迟加载）
        if (constructorMapping.getNestedQueryId() != null) {
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
        } else if (constructorMapping.getNestedResultMapId() != null) {
          //4.如果映射关系 resultMap 属性不为空，是内嵌的结果映射，则递归 getRowValue 方法，获取对应的属性值
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          value = getRowValue(rsw, resultMap, constructorMapping.getColumnPrefix());
        } else {
          //4.没有内嵌的映射关系，最常用的情况，直接使用 TypeHandler 获取当前结果集的当前行的指定列名的值
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      //5.将构造器的参数类型和参数值添加到 constructorArgTypes 和 constructorArgs
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      //6.判断是否获取到属性值
      foundValues = value != null || foundValues;
    }
    //7.通过 objectFactory 来调用匹配的构造器反射对象创建对象，传入类型、构造器参数类型和构造器参数值
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  /**
   * 通过构造器反射对象创建结果对象
   * @param rsw 结果集封装对象
   * @param resultType 结果对象类型
   * @param constructorArgTypes 构造器参数类型集合
   * @param constructorArgs 构造器参数值集合
   * @param columnPrefix 列名前缀
   * @return 结果对象
   * @throws SQLException
   */
  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                              String columnPrefix) throws SQLException {
    //1.获取结果对象类型的 所有构造方法反射对象
    final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    //2.从构造方法反射对象中获取其中默认构造方法（唯一的构造方法，带AutomapConstructor注解的构造方法）
    final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
    //3.默认构造方法非空，使用该构造方法创建结果对象
    if (defaultConstructor != null) {
      //结果集前几列初始化结果对象
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, defaultConstructor);
    } else {
      //3.默认构造方法为空，遍历所有构造方法，查找符合的构造方法创建结果对象
      for (Constructor<?> constructor : constructors) {
        //查找符合的构造方法。要求所有结果集行数据在构造器初始化
        if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, constructor);
        }
      }
    }
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  /**
   * 使用指定的构造方法，创建结果对象
   * [注意]：构造器的参数需要映射在结果集的最前面，且顺序不改变
   */
  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix, Constructor<?> constructor) throws SQLException {
    boolean foundValues = false;
    //遍历构造器方法参数
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      //1.获取参数java类型
      Class<?> parameterType = constructor.getParameterTypes()[i];
      //2.从结果集获取列名
      String columnName = rsw.getColumnNames().get(i);
      //3.根据java类型和列名获取TypeHandler
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      //4.通过 typeHandler 获取结果集指定列名的值
      Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
      //5.添加到 constructorArgTypes 和 constructorArgs
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      foundValues = value != null || foundValues;
    }
    //6.通过 objectFactory 创建结果对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  /**
   * 从构造方法数组中获取默认构造方法（这里的默认不是指无参构造器）
   * 1.唯一的构造方法
   * 2.带 AutomapConstructor 注解的构造方法
   * 3.没有返回null
   * @param constructors 构造方法反射对象数组
   * @return
   */
  private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
    //1.如果数组大小为1，则直接返回唯一元素
    if (constructors.length == 1) return constructors[0];

    //2.遍历数组，如果携带 AutomapConstructor 注解则返回
    for (final Constructor<?> constructor : constructors) {
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    return null;
  }

  /**
   * 查找符合的构造方法。要求所有结果集行数据在构造器初始化
   * @param constructor 构造器反射对象
   * @param jdbcTypes jdbc类型集合
   * @return
   */
  private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    //1.获取构造方法所有构造参数类型
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    //2.如果参数个数不等于结果集列数，返回false
    if (parameterTypes.length != jdbcTypes.size()) return false;
    //3.如果参数个数与结果集列数相等，遍历参数
    for (int i = 0; i < parameterTypes.length; i++) {
      //判断typeHandlerRegistry中有无指定java类型和jdbc类型的TypeHandler。没有直接返回false
      // 注意：构造器java类型顺序与结果集jdbc类型顺序一致
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * 创建基本类型结果对象
   * @param rsw
   * @param resultMap
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    //1.获取结果类型
    final Class<?> resultType = resultMap.getType();
    final String columnName;
    //2.如果结果映射中映射关系集合不为空
    if (!resultMap.getResultMappings().isEmpty()) {
      //2.1获取映射关系集合
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      //2.2获取第一个映射关系
      final ResultMapping mapping = resultMappingList.get(0);
      //2.3获取映射关系的列名，并拼接前缀
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      //2.如果结果映射中映射关系集合为空，则直接获取结果集第一列列名
      columnName = rsw.getColumnNames().get(0);
    }
    //3.从结果集获取TypeHandler对象
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    //4.获得 ResultSet 的指定字段的值，即结果对象
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  /**
   * 获得嵌套查询的构造节点参数的值（不支持延迟加载，因为必须加载结果）
   * @param rs 结果集
   * @param constructorMapping 带构造器标签的映射关系
   * @param columnPrefix 列名前缀
   * @return 构造器的参数值
   * @throws SQLException
   */
  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    //1.从映射关系中获取内嵌查询的编号
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    //2.从configuration中，根据id查询内嵌查询的 MappedStatement 对象
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    //3.从MappedStatement获取内嵌查询的参数类型
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    //4.获取内嵌查询的参数对象
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    //5.如果 参数对象 不为空。执行嵌套查询
    if (nestedQueryParameterObject != null) {
      //5.1传入参数对象获取 BoundSql 对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      //5.2获得对应的 CacheKey 对象
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      //5.3获取映射关系的java类型
      final Class<?> targetType = constructorMapping.getJavaType();
      //5.4创建 ResultLoader 对象，负责执行延迟加载
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      //5.5调用ResultLoader.loadResult()执行嵌套查询，得到相应的构造方法参数值
      value = resultLoader.loadResult();
    }
    return value;
  }

  /**
   * 获得嵌套查询的值（支持延迟加载，添加ResultLoader对象到ResultLoaderMap映射集合）
   * @param rs
   * @param metaResultObject
   * @param propertyMapping
   * @param lazyLoader
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    //1.从映射关系获取 内嵌查询的编号
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    //2.从映射关系获取 属性名
    final String property = propertyMapping.getProperty();
    //3.从configuration依据内嵌查询的编号查询对应的 MappedStatement对象
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    //4.从内嵌查询的MappedStatement对象获取 参数类型
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    //5.获取内嵌查询的 参数对象
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    //6.如果内嵌查询的参数对象非空
    if (nestedQueryParameterObject != null) {
      //6.1传入参数对象从内嵌查询的MappedStatement对象获取 BoundSql对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      //6.2创建查询的 CacheKey对象
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      //6.3获取映射关系的 java类型
      final Class<?> targetType = propertyMapping.getJavaType();
      //6.4如果一级缓存中已存在该嵌套查询的结果对象
      if (executor.isCached(nestedQuery, key)) {
        //6.4.1创建 DeferredLoad 对象，并通过该 DeferredLoad 对象从缓存中加载结果对象
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        //6.4.2返回已定义，结果为object对象（实现延迟加载）
        value = DEFERRED;
      } else {
        //6.4如果一级缓存中不存在
        //6.4.1创建 ResultLoader 对象
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
        //6.4.2如果映射关系要求延迟加载，则延迟加载
        if (propertyMapping.isLazy()) {
          //如果该属性配置了延迟加载，则将其添加到 `ResultLoader.loaderMap` 中，等待真正使用时再执行嵌套查询并得到结果对象。
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          //返回已定义
          value = DEFERRED;
        } else {
          //6.4.2如果不要求延迟加载，则直接执行加载对应的值
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  /**
   * 从结果集获取内嵌查询的参数对象
   */
  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    //1.组合。如果cloumn有多个，即嵌套查询有多个传入参数
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      //普通。如果cloumn只有一个，即一个参数
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  /**
   * 获得普通类型的内嵌查询的参数对象。只有一个参数传入嵌套查询
   * @param rs 结果集
   * @param resultMapping 映射关系对象
   * @param parameterType 参数类型
   * @param columnPrefix 列名前缀
   * @return 参数值
   * @throws SQLException
   */
  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    //1.获取 typeHandler 对象
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    //2.依赖 typeHandler 从结果集获取指定列名的 参数值
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  /**
   * 获得组合类型的内嵌查询的参数对象。有多个参数传入嵌套查询
   * @param rs 结果集
   * @param resultMapping 映射关系
   * @param parameterType 参数类型
   * @param columnPrefix 列前缀
   * @return 参数值
   * @throws SQLException
   */
  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    //1.初始化创建参数对象
    final Object parameterObject = instantiateParameterObject(parameterType);
    //2.创建参数对象的 MetaObject 对象，对参数对象进行访问
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    //3.遍历组合的所有列名
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      //4.获取属性类型
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      //5.获取对应的 TypeHandler
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      //6.依赖TypeHandler从结果集获取列名对应的参数值
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      //7.通过 metaObject 将参数值设置到 parameterObject 中
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        //标记 parameterObject非空
        foundValues = true;
      }
    }
    //8.返回组合的参数值
    return foundValues ? parameterObject : null;
  }

  /**
   * 创建空的参数对象
   * @param parameterType 参数类型
   * @return
   */
  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<>();
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  /**
   * 根据结果集该行记录以及 ResultMap.discriminator ，决定映射使用的 ResultMap 对象
   * discriminator节点的执行处理
   * @param rs 结果集
   * @param resultMap 结果映射
   * @param columnPrefix 列前缀
   * @return 结果映射
   * @throws SQLException
   */
  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    //1.记录已经处理过的 Discriminator 节点中对应的 ResultMap 编号
    Set<String> pastDiscriminators = new HashSet<>();
    //2.从 ResultMap 获取 Discriminator 节点对应的对象
    Discriminator discriminator = resultMap.getDiscriminator();
    //3.如果 discriminator 对象不为空，则进行处理返回 resultMap ；否则直接返回原 resultMap 对象（递归的循环）
    // discriminator节点下case指定的 resultMap 又有 discriminator 节点，则需要递归处理
    while (discriminator != null) {
      //4.从结果集 ResultSet 对象获取 discriminator 指定的字段的值
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      //5.从 discriminator 获取该值对应的 ResultMap 对应的编号
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      //6.如果configuration中存在该 ResultMap。即初始化时加载了mapper.xml文件对应的 ResultMap 节点
      if (configuration.hasResultMap(discriminatedMapId)) {
        //6.1从configuration获取 resultMap 对象，准备返回
        resultMap = configuration.getResultMap(discriminatedMapId);
        Discriminator lastDiscriminator = discriminator;
        //6.2获取到的 resultMap 中还有鉴别器，获取该 discriminator 对象（递归）
        discriminator = resultMap.getDiscriminator();
        //6.3检测discriminator是否出现了环形引用。如果符合以下情况直接跳出循环返回（鉴别器重复 或 resultMap的编号重复）
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          break;
        }
      } else {
        //6.如果configuration中不存在该 ResultMap，直接跳出循环
        break;
      }
    }
    return resultMap;
  }

  /**
   * 从结果集获取Discriminator节点column属性指定列名的值
   * @param rs 结果集
   * @param discriminator 鉴别器
   * @param columnPrefix 列前缀
   * @return 指定列名的值
   * @throws SQLException
   */
  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    //1.从 discriminator 对象获取其对应的 resultMapping 映射关系对象
    final ResultMapping resultMapping = discriminator.getResultMapping();
    //2.从 resultMapping 获取 TypeHandler
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    //3.依赖 typeHandler 从结果集获取指定列名的值
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  /**
   * 处理嵌套的结果映射
   * 0.准备一个结果对象上下文 DefaultResultContext
   * 1.调用skipRows()方法，将结果集定位到指定的记录行
   * 2.调用shouldProcessMoreRows()方法，检测是否能继续映射结果集中剩余的记录行
   * 3.通过resolveDiscriminatedResultMap()方法，解析discriminator决定映射使用的 ResultMap对象
   * 4.为该行记录生成CacheKey
   * 5.根据步骤4中生成的CacheKey查找nestedResultObjects集合
   * 6.检测SQL节点的resultOrdered属性
   * 7.调用getRowValue()方法，完成该行记录的嵌套结果映射返回结果对象，其中还会将结果对象添加到nestedResultObjects
   * 8.调用storeObject()方法，保存结果对象
   * @param rsw 结果包装器
   * @param resultMap 结果映射
   * @param resultHandler 结果处理器
   * @param rowBounds 行限制
   * @param parentMapping 父结果映射关系
   * @throws SQLException
   */
  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    //1.创建 DefaultResultContext 结果对象上下文
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    //2.从结果集包装器获取 结果集
    ResultSet resultSet = rsw.getResultSet();
    //3.步骤1：结果集依据 rowbounds 定位到指定行
    skipRows(resultSet, rowBounds);
    //4.结果对象，先初始化为上一次获得的结果对象
    Object rowValue = previousRowValue;
    //5.步骤2：遍历 resultSet 所有行
    // 循环条件：1.根据生成的结果对象上下文判断是否继续处理 ResultSet，2.根据结果集是否关闭，3.根据结果集是否有待处理行
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      //6.步骤3：根据结果集的行记录以及 resultMap.discriminator，决定映射使用的 ResultMap 对象
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      //7.步骤4：创建标识每一行对应的结果对象的 CacheKey 对象
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      //8.步骤5：从 nestedResultObjects 中查询指定 rowKey 的缓存的主结果对象（嵌套的最外层结果对象）
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      //9.步骤6：如果SQL节点的 resultOrdered 属性为true
      if (mappedStatement.isResultOrdered()) {
        //9.1如果主结果对象发生变化时，会将上行对应的结果对象保存到resultHandler（此时结果对象是完整的）
        if (partialObject == null && rowValue != null) {//每个外层对象在变换外层对象时，需要保存到resultHandler；后续无需保存
          nestedResultObjects.clear();//清理nestedResultObjects集合所占内存
          //调用storeObject方法将生成的结果对象保存到resultHandler（也就是嵌套映射的外层结果对象）
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        //9.2步骤7：按照嵌套结果映射获取当前行对应的结果对象，会将结果对象添加到nestedResultObjects
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        //9.如果SQL节点的 resultOrdered 属性为false
        //9.1步骤7：按照嵌套结果映射获取当前行对应的结果对象，会将结果对象添加到nestedResultObjects
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        //9.2步骤8：将生成的结果对象保存到resultHandler（也就是嵌套映射的外层结果对象）
        if (partialObject == null) {//每个外层对象（不同rowKey）第一次获取时，需要保存到resultHandler；后续无需保存
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    //10.对resultOrdered属性为true时特殊处理，调用 storeObject()方法保存结果对象
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  /**
   * 根据嵌套结果映射 resultMap 对 ResultSet 中的当前行记录进行映射，得到映射后的结果对象
   * 在处理 最外层结果映射 和 嵌套的结果映射 时都会调用本方法获取 外层结果对象/嵌套对象
   * 1.检测外层对象是否存在
   * 2.外层对象存在，生成的结果对象设置到对象属性
   * 3.外层对象不存在，创建结果对象
   * @param rsw 结果集包装器
   * @param resultMap 嵌套结果映射
   * @param combinedKey CacheKey对象
   * @param columnPrefix 列名前缀
   * @param partialObject nestedResultObjects中当前cachekey对应的结果对象
   * @return
   * @throws SQLException
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    //1.从结果映射获取编号
    final String resultMapId = resultMap.getId();
    //2.将nestedResultObjects中当前cachekey对应的结果对象赋值给 rowValue，即初始化结果对象为外层对象
    Object rowValue = partialObject;
    //3.步骤2：如果初始化的 rowValue 不为空，即nestedResultObjects映射中存在外层对象
    if (rowValue != null) {
      //3.1获取外层对象的MetaObject
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      //3.2将外层对象添加到 ancestorObjects
      putAncestor(rowValue, resultMapId);
      //3.3应用 嵌套内层结果映射 到结果对象，设置外层结果对象属性，将生成的结果对象设置到外层对象的相应属性中
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      //3.4将外层对象从ancestorObjects映射中移除
      ancestorObjects.remove(resultMapId);
    } else {
      //3.步骤3：如果初始化的 rowValue 为空，即nestedResultObjects映射中不存在外层对象
      //3.1创建 ResultLoaderMap 对象
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      //3.2通过构造器创建结果对象
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      //3.3如果结果对象不为空 且 不为基本类型
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        //3.3.1获取结果对象的 MetaObject
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;
        //3.3.2检测是否开启自动映射，开启则应用自动映射设置结果对象
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        //3.3.3应用 外层结果映射 到结果对象，设置外层结果对象
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        //3.3.4将外层对象添加到 DefaultResultSetHandler.ancestorObjects 映射集合。key:resultMapId,value:外层结果对象
        putAncestor(rowValue, resultMapId);
        //3.3.5应用 嵌套内层结果映射 到结果对象，设置外层结果对象属性，将生成的结果对象设置到外层对象的相应属性中【核心方法】
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        //3.3.6将外层对象从 ancestorObjects 映射中移除
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      //3.4将外层对象保存到 nestedResultObjects 映射中，待后续记录时使用
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    //4.返回结果对象
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  /**
   * 应用嵌套映射到结果对象（处理嵌套结果映射的核心）
   * 遍历 ResultMap.propertyResultMappings 集合中记录的 ResultMapping 对象，井处理其中的嵌套映射
   * 1.检测 nestedResultMapId 和 resultSet 两个属性的值
   * 2.调用确定嵌套使用的 ResultMap 对象
   * 3.处理循环引用的情况
   * 4.为嵌套对象创建 CacheKey对象
   * 5.初始化外层对象中Collection类型属性
   * 6.根据 notNullColumn 属性检测结果中的空值
   * 7.完成嵌套映射，并生成嵌套对象
   * 8.将步骤7得到的嵌套对象保存到外层对象的相应属性中
   * @param rsw 结果集包装器
   * @param resultMap 嵌套结果映射
   * @param metaObject 结果对象的MetaObject
   * @param parentPrefix 父结果映射的列名前缀
   * @param parentRowKey 父CacheKey
   * @param newObject
   * @return
   */
  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    //标志是否应用了嵌套映射
    boolean foundValues = false;
    //1.遍历 resultMap 的 属性映射关系集合
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      //2.获取映射关系的 嵌套结果映射编号
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      //3.步骤1：如果映射关系的 嵌套结果映射编号 不为空且映射关系的 resultSet属性为空
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          //3.1拼接 父结果映射的列名前缀 和 嵌套结果映射的列名前缀
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          //3.2步骤2：根据嵌套结果映射编号同时解析鉴别器，获取嵌套结果映射
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          //3.3步骤3：如果映射关系的 columnPrefix 属性为空
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            //依据 嵌套结果映射编号 获取缓存的结果对象
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            //如果获取到结果对象，则发生了结果映射的循环引用
            if (ancestorObject != null) {
              if (newObject) {
                //重用缓存的嵌套对象
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;//若是循环引用，则不用执行下面的路径创建新对象，而是重用之前的对象
            }
          }
          //3.4步骤4：依据 嵌套结果映射 和 结果集 生成唯一的CacheKey
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          //3.5步骤4：合并父CacheKey和子CacheKey
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          //3.6依据combinedKey从nestedResultObjects获取 嵌套结果 的缓存
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          //3.7步骤5：初始化嵌套的集合对象，并设置到外层结果对象
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          //3.8步骤6：根据映射关系的 notNullColumn 属性检测结果集中的空值
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            //3.8.1步骤7：生成嵌套对象，产生多层递归【核心方法】
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            //3.8.2如果嵌套对象不为空 且 knownValue为false（一般情况默认false）
            if (rowValue != null && !knownValue) {
              //步骤8：将步骤7生成的嵌套对象保存到外层对象的相应属性中
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  /**
   * 据映射关系的 notNullColumn 属性检测结果集中的空值，要求 notNullColumn 属性对应的列值有任一值不为空
   * @param resultMapping 映射关系
   * @param columnPrefix 列名前缀
   * @param rsw 结果集包装器
   * @return
   * @throws SQLException
   */
  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    //1.从 映射关系 中获取 notNullColumn 属性，即嵌套结果映射中任一不为空的列集合
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    //2.如果 notNullColumns 不为空且集合内有元素
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      //2.1获取结果集
      ResultSet rs = rsw.getResultSet();
      //2.2遍历 notNullColumns 的列名
      for (String column : notNullColumns) {
        //2.3从结果集获取列名对应的列值
        rs.getObject(prependPrefix(column, columnPrefix));
        //2.4如果列值不为空，返回true，表示可以嵌套映射
        if (!rs.wasNull()) {
          return true;
        }
      }
      //2.5循环结束没有非空的列值，则不可以嵌套映射
      return false;
    } else if (columnPrefix != null) {
      //2.如果列名前缀不为空，循环校验结果集列名是否存在以前缀开头
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
          return true;
        }
      }
      //2.1如果没有以该列名前缀开头，返回false，则不可以嵌套映射
      return false;
    }
    //3.其它情况返回true,表示可以嵌套映射
    return true;
  }

  /**
   * 获取嵌套结果映射
   * 先依据嵌套结果映射编号从configuration获取，再通过结果集和嵌套结果映射的鉴别器获取最终的 嵌套结果映射
   * @param rs 结果集
   * @param nestedResultMapId 嵌套结果映射编号
   * @param columnPrefix 列名前缀
   * @return 嵌套结果映射
   * @throws SQLException
   */
  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    //1.依据嵌套结果映射编号从configuration获取 原始嵌套结果映射
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    //2.解析discriminator获取 最终嵌套结果映射
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  /**
   * 创建每行记录对应的 CacheKey 对象
   * 1.尝试使用 idArg 节点或 id 节点中定义的列名以及该列在当前记录行中对应的列值组成 CacheKey 对象
   * 2.如果 ResultMap 中没有定义 idArg 节点或 id 节点，则由 ResultMap 中(非构造器标签的)明确要映射的列名以及它们在当前记录行中对应的列值一起构成 CacheKey 对象
   * 3.如果经过上述两个步骤后，依然查找不到相关的列名和列值，且 ResultMap.type 属性
   *   明确指明了结果对象为 Map 类型，则由结果集中所有列名以及该行记录行的所有列值一起构成 CacheKey 对象
   * 4.如果映射的结果对象不是 Map 类型 ，则由结果集中未映射的列名以及它们在当前记录行中的对应列值一起构成 CacheKey 对象
   * @param resultMap 结果映射
   * @param rsw 结果集包装器
   * @param columnPrefix 列名前缀
   * @return CacheKey对象
   * @throws SQLException
   */
  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    //1.创建空的CacheKey对象
    final CacheKey cacheKey = new CacheKey();
    //2.向cacheKey添加 结果映射的变号
    cacheKey.update(resultMap.getId());
    //3.从结果映射获取标识行记录的映射关系对象集合
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    //4.如果映射关系集合为空（结果映射没有子节点或只有arg子节点时）
    if (resultMappings.isEmpty()) {
      //4.1步骤3：如果结果类型为 Map 及其子类
      // 则由结果集所有列名以及当前记录行的所有值一起构成CacheKey对象
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        createRowKeyForMap(rsw, cacheKey);
      } else {
        //4.1步骤4：如果结果类型非Map
        // 则由结果集中未映射的列名以及它们在当前记录行中的对应列值一起构成CacheKey对象
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      //4.步骤1、2：如果映射关系集合不为空
      // 则由resultMappings集合中的列名以及它们在当前记录行中相应的列值一起构成 CacheKey 对象
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    //5.如果 CacheKey 构成只有一个元素，返回一个空的cacheKey对象
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    //6.返回cacheKey
    return cacheKey;
  }

  /**
   * 克隆 rowKey ，并将父CacheKey对象添加到 combinedKey 中
   * @param rowKey 子CacheKey
   * @param parentRowKey 父CacheKey
   * @return
   */
  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        //1.克隆嵌套结果映射对应的 rowKey
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      //2.与外层对象的parentRowKey合并
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  /**
   * 获取标识行记录的映射关系集合
   * 1.首先从 结果映射 获取带id标签的 映射关系（id节点和idArg节点） 返回
   * 2.其次如果以上映射关系集合为空，则获取 所有非构造器标签的映射关系集合 返回
   * @param resultMap
   * @return
   */
  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  /**
   * 解析指定的 映射关系集合，将其列名和列值添加到 CacheKey
   * @param resultMap 结果映射
   * @param rsw 结果集包装器
   * @param cacheKey CacheKey对象
   * @param resultMappings 映射关系集合
   * @param columnPrefix 列前缀
   * @throws SQLException
   */
  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    //1.遍历所有 映射关系集合
    for (ResultMapping resultMapping : resultMappings) {
      //2.如果映射关系有嵌套的 resultMap 属性 且 resultSet 属性为空
      if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
        // Issue #392
        //2.1获取嵌套的 结果映射 对象
        final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
        //2.2将嵌套的结果映射中的 构造器标签的映射关系 对应的列名和列值添加到 CacheKey
        createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
            prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
      } else if (resultMapping.getNestedQueryId() == null) {
        //2.如果映射关系没有嵌套的 select 查询属性，即select属性为空，即忽略映射关系有内嵌的查询的情况
        //2.1获取映射关系的列名
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        //2.2获取映射关系的TypeHandler
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        //2.3获取映射的列名集合
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        //2.4如果列名不为空 且 列名已映射
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          //2.4.1获取列名对应的值
          final Object value = th.getResult(rsw.getResultSet(), column);
          //2.4.2将 列名和列值 添加到 cacheKey
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  /**
   * 将结果集当前行 所有列名 和 所有列值 添加到 cacheKey
   * @param rsw 结果集包装器
   * @param cacheKey CacheKey对象
   * @throws SQLException
   */
  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    //1.获取结果集所有列名
    List<String> columnNames = rsw.getColumnNames();
    //2.按列名遍历列
    for (String columnName : columnNames) {
      //3.从结果集获取列名对应的列值
      final String value = rsw.getResultSet().getString(columnName);
      //4.如果列值不为空，则添加列名和列值
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  /**
   * 连接嵌套对象 rowValue 到外层对象 metaObject
   * @param metaObject 外层对象
   * @param resultMapping 映射关系
   * @param rowValue 嵌套对象
   */
  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    //1.检查外层对象的指定属性是否为 collection 类型。
    // 如果是且未初始化，则初始化该集合属性并返回；如果已初始化返回集合对象。如果是非集合类型返回null
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    //2.如果 collectionProperty集合属性 非空，则表明属性是集合类型，则将嵌套对象添加到集合中
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      //2.如果 collectionProperty 为空，则表明属性是非集合类型，则将嵌套对象设置到外层结果对象相应属性中
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  /**
   * 初始化嵌套的集合对象
   * 1.属性为集合类型，值为空，则返回集合空对象
   * 2.属性为集合类型，值不为空，则返回集合对象
   * 3.属性为非集合类型，则返回null
   * @param resultMapping 映射关系
   * @param metaObject 结果对象
   * @return
   */
  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    //1.从映射关系获取 property 属性
    final String propertyName = resultMapping.getProperty();
    //2.从结果对象获取对应属性名的值
    Object propertyValue = metaObject.getValue(propertyName);
    //3.如果属性值为空
    if (propertyValue == null) {
      //3.1从结果映射获取属性类型
      Class<?> type = resultMapping.getJavaType();
      //3.2如果属性类型为空则从metaObject获取
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        //3.3如果属性类型为 Collection 集合类型
        if (objectFactory.isCollection(type)) {
          //3.4为结果对象属性初始化空集合
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          //3.5返回初始化的空集合
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      //3.如果属性值不为空且为集合类型，直接返回
      return propertyValue;
    }
    //4.属性为非集合类型直接返回null
    return null;
  }

  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    if (rsw.getColumnNames().size() == 1) {
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
