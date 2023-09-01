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
package org.apache.ibatis.builder;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.util.*;

/**
 * 继承 BaseBuilder 抽象类，Mapper 构造器的小助手，
 * 提供了一些公用的方法，例如创建 ParameterMap、MappedStatement 对象等等。
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {

  /**
   * 当前 Mapper 命名空间
   */
  private String currentNamespace;
  /**
   * 资源引用的地址
   */
  private final String resource;
  /**
   * 当前 Cache 对象
   */
  private Cache currentCache;
  /**
   * 是否未解析成功 cache-ref
   */
  private boolean unresolvedCacheRef; // issue #676

  /**
   * 构造器，初始化 configuration 和 resource 属性
   * @param configuration 配置类
   * @param resource 资源地址
   */
  public MapperBuilderAssistant(Configuration configuration, String resource) {
    super(configuration);
    ErrorContext.instance().resource(resource);
    this.resource = resource;
  }

  public String getCurrentNamespace() {
    return currentNamespace;
  }

  /**
   * 设置 currentNamespace 属性
   * @param currentNamespace
   */
  public void setCurrentNamespace(String currentNamespace) {
    //1.如果传入的 currentNamespace 参数为空，抛出 BuilderException 异常
    if (currentNamespace == null) {
      throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
    }
    //2.如果当前已经设置，并且还和传入的不相等，抛出 BuilderException 异常
    if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
      throw new BuilderException("Wrong namespace. Expected '"
          + this.currentNamespace + "' but found '" + currentNamespace + "'.");
    }
    //3.设置
    this.currentNamespace = currentNamespace;
  }

  /**
   * 拼接命名空间
   * @param base
   * @param isReference
   * @return
   */
  public String applyCurrentNamespace(String base, boolean isReference) {
    //1.如果 base 为空，则无需拼接直接返回空
    if (base == null) {
      return null;
    }
    //2.如果 isReference 为true
    if (isReference) {
      // is it qualified with any namespace yet?
      if (base.contains(".")) {
        return base;
      }
    } else {
      //2.如果 isReference 为false
      // is it qualified with this namespace yet?
      if (base.startsWith(currentNamespace + ".")) {
        return base;
      }
      if (base.contains(".")) {
        throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
      }
    }
    //3.拼接 currentNamespace + base
    return currentNamespace + "." + base;
  }

  /**
   * 获得指向的 Cache 对象，根据mapper命名空间选用 Cache 对象，底层依赖 configuration 对象实现
   * @param namespace 指定的命名空间
   * @return
   */
  public Cache useCacheRef(String namespace) {
    //1.如果 namespace 为空则抛出异常
    if (namespace == null) {
      throw new BuilderException("cache-ref element requires a namespace attribute.");
    }
    try {
      //标识未成功解析
      unresolvedCacheRef = true;
      //2.从 configuration 的caches属性获取namespace对应的 Cache 对象
      Cache cache = configuration.getCache(namespace);
      //3.如果cache为空则抛出异常
      if (cache == null) {
        throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
      }
      //4.记录当前命名空间的 cache对象
      currentCache = cache;
      //标识已成功解析
      unresolvedCacheRef = false;
      //5.返回cache对象
      return cache;
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
    }
  }

  /**
   * 根据解析mapper.xml的数据，构造 Cache 对象。底层依赖 CacheBuilder 实现
   * @return
   */
  public Cache useNewCache(Class<? extends Cache> typeClass,
      Class<? extends Cache> evictionClass,
      Long flushInterval,
      Integer size,
      boolean readWrite,
      boolean blocking,
      Properties props) {
    //1.创建 Cache 对象
    Cache cache = new CacheBuilder(currentNamespace)
        .implementation(valueOrDefault(typeClass, PerpetualCache.class))
        .addDecorator(valueOrDefault(evictionClass, LruCache.class))
        .clearInterval(flushInterval)
        .size(size)
        .readWrite(readWrite)
        .blocking(blocking)
        .properties(props)
        .build();
    //2.添加到 configuration 的 caches 中
    configuration.addCache(cache);
    //3.设置属性 currentCache
    currentCache = cache;
    return cache;
  }

  public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
    id = applyCurrentNamespace(id, false);
    ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings).build();
    configuration.addParameterMap(parameterMap);
    return parameterMap;
  }

  public ParameterMapping buildParameterMapping(
      Class<?> parameterType,
      String property,
      Class<?> javaType,
      JdbcType jdbcType,
      String resultMap,
      ParameterMode parameterMode,
      Class<? extends TypeHandler<?>> typeHandler,
      Integer numericScale) {
    resultMap = applyCurrentNamespace(resultMap, true);

    // Class parameterType = parameterMapBuilder.type();
    Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

    return new ParameterMapping.Builder(configuration, property, javaTypeClass)
        .jdbcType(jdbcType)
        .resultMapId(resultMap)
        .mode(parameterMode)
        .numericScale(numericScale)
        .typeHandler(typeHandlerInstance)
        .build();
  }

  /**
   * 创建 ResultMap 对象，并添加到 Configuration 中
   * @param id
   * @param type
   * @param extend
   * @param discriminator
   * @param resultMappings
   * @param autoMapping
   * @return
   */
  public ResultMap addResultMap(
      String id,
      Class<?> type,
      String extend,
      Discriminator discriminator,
      List<ResultMapping> resultMappings,
      Boolean autoMapping) {
    //1.获取ResultMap完整的 id 属性，即拼接 namespace 和 id，即格式为${namespace}.${id}
    id = applyCurrentNamespace(id, false);
    //2.获取ResultMap完整的 extend 属性，即拼接 namespace 和 extend，即格式为${namespace}.${extend}
    extend = applyCurrentNamespace(extend, true);

    //3.如果有 extend 属性，则将父类的 ResultMap 集合，添加到 resultMappings 中
    if (extend != null) {
      //3.1从 configuration 获取extend对应的 ResultMap 对象，若不存在则抛出异常，在XMLMapperBuilder中处理异常
      if (!configuration.hasResultMap(extend)) {
        throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
      }
      //3.2从 configuration 获取extend对应的父 ResultMap 对象
      ResultMap resultMap = configuration.getResultMap(extend);
      //3.3获取父 ResultMap 对象中记录的 ResultMapping 集合
      List<ResultMapping> extendedResultMappings = new ArrayList<>(resultMap.getResultMappings());
      //3.4移除父类集合中与子类重复的映射关系
      extendedResultMappings.removeAll(resultMappings);
      // Remove parent constructor if this resultMap declares a constructor.
      //3.5如果当前 resultMap 节点定义了 constructor 节点，则不需要使用父resultMap节点定义的constructor节点
      //并将父节点的关系映射中的 constructor 节点删除
      boolean declaresConstructor = false;
      //3.5.1遍历当前节点的 关系映射 ，判断是否为 constructor 节点，是则标记并跳出循环
      for (ResultMapping resultMapping : resultMappings) {
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          declaresConstructor = true;
          break;
        }
      }
      //3.5.2如果当前节点有constructor节点，就将父节点的constructor节点的关系映射删除
      if (declaresConstructor) {
        Iterator<ResultMapping> extendedResultMappingsIter = extendedResultMappings.iterator();
        while (extendedResultMappingsIter.hasNext()) {
          if (extendedResultMappingsIter.next().getFlags().contains(ResultFlag.CONSTRUCTOR)) {
            extendedResultMappingsIter.remove();
          }
        }
      }
      //3.6将 父节点关系映射集合 添加到 当前节点关系映射集合
      resultMappings.addAll(extendedResultMappings);
    }
    //4.构建 ResultMap 对象，依赖 ResultMap.Builder 构建
    ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
        .discriminator(discriminator)
        .build();
    //5.添加到 configuration 中保存
    configuration.addResultMap(resultMap);
    return resultMap;
  }

  /**
   * 构建 Discriminator 对象。底层依赖 Discriminator.Builder 实现
   * @return
   */
  public Discriminator buildDiscriminator(
      Class<?> resultType,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      Class<? extends TypeHandler<?>> typeHandler,
      Map<String, String> discriminatorMap) {
    //1.构建 ResultMapping 对象
    ResultMapping resultMapping = buildResultMapping(
        resultType,
        null,
        column,
        javaType,
        jdbcType,
        null,
        null,
        null,
        null,
        typeHandler,
        new ArrayList<>(),
        null,
        null,
        false);
    //2.创建 namespaceDiscriminatorMap 映射
    Map<String, String> namespaceDiscriminatorMap = new HashMap<>();
    //3.遍历 discriminatorMap 对象
    for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
      //获取 value:resultMap
      String resultMap = e.getValue();
      //拼接namespace
      resultMap = applyCurrentNamespace(resultMap, true);
      //放入 namespaceDiscriminatorMap 映射。
      //key:case节点的value属性，value:case节点的resultMap拼接namespace
      namespaceDiscriminatorMap.put(e.getKey(), resultMap);
    }
    //4.构建 Discriminator 对象
    return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
  }

  /**
   * 构建 MappedStatement 对象
   * 由 XMLStatementBuilder 和 MapperAnnotationBuilder 对象调用生成 SQL 语句最终对应的 MappedStatement 对象
   * @return
   */
  public MappedStatement addMappedStatement(
      String id,
      SqlSource sqlSource,
      StatementType statementType,
      SqlCommandType sqlCommandType,
      Integer fetchSize,
      Integer timeout,
      String parameterMap,
      Class<?> parameterType,
      String resultMap,
      Class<?> resultType,
      ResultSetType resultSetType,
      boolean flushCache,
      boolean useCache,
      boolean resultOrdered,
      KeyGenerator keyGenerator,
      String keyProperty,
      String keyColumn,
      String databaseId,
      LanguageDriver lang,
      String resultSets) {

    //1.如果 cacheRef 未解析，抛出 IncompleteElementException 异常
    if (unresolvedCacheRef) {
      throw new IncompleteElementException("Cache-ref not yet resolved");
    }

    //2.拼接 namespace 获取完整的id
    id = applyCurrentNamespace(id, false);
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;//判断是否为 select 语句

    //3.创建 MappedStatement.Builder 对象
    MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)
        .resource(resource)
        .fetchSize(fetchSize)
        .timeout(timeout)
        .statementType(statementType)
        .keyGenerator(keyGenerator)
        .keyProperty(keyProperty)
        .keyColumn(keyColumn)
        .databaseId(databaseId)
        .lang(lang)
        .resultOrdered(resultOrdered)
        .resultSets(resultSets)
        .resultMaps(getStatementResultMaps(resultMap, resultType, id))//3.1获得 ResultMap 集合
        .resultSetType(resultSetType)
        .flushCacheRequired(valueOrDefault(flushCache, !isSelect))
        .useCache(valueOrDefault(useCache, isSelect))
        .cache(currentCache);

    //4.获得 ParameterMap ，并设置到 MappedStatement.Builder
    ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);
    if (statementParameterMap != null) {
      statementBuilder.parameterMap(statementParameterMap);
    }

    //5.构建 MappedStatement 对象
    MappedStatement statement = statementBuilder.build();
    //6.添加到 configuration 中
    configuration.addMappedStatement(statement);
    //7.返回 MappedStatement 对象
    return statement;
  }

  private <T> T valueOrDefault(T value, T defaultValue) {
    return value == null ? defaultValue : value;
  }

  /**
   * 获得 ParameterMap 对象
   * 由 {@link #addMappedStatement(String, SqlSource, StatementType, SqlCommandType, Integer, Integer, String, Class, String, Class, ResultSetType, boolean, boolean, boolean, KeyGenerator, String, String, String, LanguageDriver, String)} 调用
   * @param parameterMapName parameterMap属性值
   * @param parameterTypeClass parameterType属性值
   * @param statementId statement 语句的完整id
   * @return
   */
  private ParameterMap getStatementParameterMap(
      String parameterMapName,
      Class<?> parameterTypeClass,
      String statementId) {
    //1.拼接 Namespace 获取完整的 parameterMapName
    parameterMapName = applyCurrentNamespace(parameterMapName, true);
    ParameterMap parameterMap = null;
    //2.如果 parameterMap属性值 非空，则从configuration获取 parameterMapName 对应的 ParameterMap 对象
    //不推荐使用
    if (parameterMapName != null) {
      try {
        parameterMap = configuration.getParameterMap(parameterMapName);
      } catch (IllegalArgumentException e) {
        throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
      }
    } else if (parameterTypeClass != null) {
      //2.如果 parameterType属性值 非空，则创建 ParameterMap 对象
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      parameterMap = new ParameterMap.Builder(
          configuration,
          statementId + "-Inline",
          parameterTypeClass,
          parameterMappings).build();
    }
    return parameterMap;
  }

  /**
   * 获得 ResultMap 集合。获取 statement 语句的属性 resultMap 指定的 ResultMap 集合
   * 由 {@link #addMappedStatement(String, SqlSource, StatementType, SqlCommandType, Integer, Integer, String, Class, String, Class, ResultSetType, boolean, boolean, boolean, KeyGenerator, String, String, String, LanguageDriver, String)} 调用
   * @param resultMap 语句的 resultMap 属性
   * @param resultType 语句的 resultType 属性
   * @param statementId 语句的完整 id
   * @return
   */
  private List<ResultMap> getStatementResultMaps(
      String resultMap,
      Class<?> resultType,
      String statementId) {
    //1.拼接 Namespace 获取完整的 resultMap 编号
    resultMap = applyCurrentNamespace(resultMap, true);

    //2.创建 ResultMap 集合
    List<ResultMap> resultMaps = new ArrayList<>();
    //3.如果 resultMap 非空，则获得 resultMap 对应的 ResultMap 对象集合
    if (resultMap != null) {
      //3.1分隔 resultMap 属性值
      String[] resultMapNames = resultMap.split(",");
      //3.2遍历多个 resultMap 属性值
      for (String resultMapName : resultMapNames) {
        try {
          //3.3从 configuration 获取 resultMap 对象并添加到 resultMaps 集合
          resultMaps.add(configuration.getResultMap(resultMapName.trim()));
        } catch (IllegalArgumentException e) {
          throw new IncompleteElementException("Could not find result map " + resultMapName, e);
        }
      }
    } else if (resultType != null) {
      //3.如果 resultType 非空，则创建 ResultMap 对象
      ResultMap inlineResultMap = new ResultMap.Builder(
          configuration,
          statementId + "-Inline",
          resultType,
          new ArrayList<>(),
          null).build();
      //将创建的唯一 ResultMap 对象添加到 resultMaps 集合
      resultMaps.add(inlineResultMap);
    }
    return resultMaps;
  }

  /**
   * 构造 ResultMapping 对象。底层依赖 ResultMapping.Builder 实现
   * @return
   */
  public ResultMapping buildResultMapping(
      Class<?> resultType,
      String property,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      String nestedSelect,
      String nestedResultMap,
      String notNullColumn,
      String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler,
      List<ResultFlag> flags,
      String resultSet,
      String foreignColumn,
      boolean lazy) {
    //1.从 resultType 类中解析指定 property 属性的class类型对象
    Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
    //2.解析获取 TypeHandler 对象，底层依赖 typeHandlerRegistry
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
    //3.解析组合字段名成为 ResultMapping 集合
    List<ResultMapping> composites = parseCompositeColumnName(column);
    //4.创建 ResultMapping.Builder 对象，并通过它创建 ResultMapping 对象
    return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
        .jdbcType(jdbcType)
        .nestedQueryId(applyCurrentNamespace(nestedSelect, true))
        .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true))
        .resultSet(resultSet)
        .typeHandler(typeHandlerInstance)
        .flags(flags == null ? new ArrayList<>() : flags)
        .composites(composites)
        .notNullColumns(parseMultipleColumnNames(notNullColumn))
        .columnPrefix(columnPrefix)
        .foreignColumn(foreignColumn)
        .lazy(lazy)
        .build();
  }

  /**
   * 将 notNullColumn 属性字符串解析成Set集合
   * @param columnName notNullColumn 属性值
   * @return
   */
  private Set<String> parseMultipleColumnNames(String columnName) {
    Set<String> columns = new HashSet<>();
    if (columnName != null) {
      // 多个字段，使用 ，分隔
      if (columnName.indexOf(',') > -1) {
        StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
        while (parser.hasMoreTokens()) {
          String column = parser.nextToken();
          columns.add(column);
        }
      } else {
        columns.add(columnName);
      }
    }
    return columns;
  }

  /**
   * 解析组合字段名称成 ResultMapping 集合
   * @param columnName column属性值
   * @return
   */
  private List<ResultMapping> parseCompositeColumnName(String columnName) {
    List<ResultMapping> composites = new ArrayList<>();
    //1.分词，解析其中的 property 和 column 的组合对
    if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
      //1.1使用 StringTokenizer 分词，分词间隔符为 "{"、"}"、"="、" "  四个
      StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
      //1.2有 tokens
      while (parser.hasMoreTokens()) {
        String property = parser.nextToken();
        String column = parser.nextToken();
        //构建映射关系 ResultMapping 对象
        ResultMapping complexResultMapping = new ResultMapping.Builder(
            configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
        //添加到 composites
        composites.add(complexResultMapping);
      }
    }
    return composites;
  }

  /**
   * 解析指定 resultType 的指定 property 属性的类型
   * 1.如果有指定 javaType 则直接返回
   * 2.如果没有指定 javaType 则从 resultType 类的属性中解析出类型class
   * @param resultType 指定的结果类型
   * @param property 指定的属性
   * @param javaType 指定的java类型
   * @return 返回java类型
   */
  private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
    //1.如果 javaType 为空且 property 不为空
    if (javaType == null && property != null) {
      try {
        //从 resultType 类中解析指定 property 属性的class类型对象
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        javaType = metaResultType.getSetterType(property);
      } catch (Exception e) {
        //ignore, following null check statement will deal with the situation
      }
    }
    //2.如果解析后 javaType 还是空，则赋值Object
    if (javaType == null) {
      javaType = Object.class;
    }
    //3.返回
    return javaType;
  }

  private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
    if (javaType == null) {
      if (JdbcType.CURSOR.equals(jdbcType)) {
        javaType = java.sql.ResultSet.class;
      } else if (Map.class.isAssignableFrom(resultType)) {
        javaType = Object.class;
      } else {
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        javaType = metaResultType.getGetterType(property);
      }
    }
    if (javaType == null) {
      javaType = Object.class;
    }
    return javaType;
  }

  /** Backward compatibility signature */
  public ResultMapping buildResultMapping(
      Class<?> resultType,
      String property,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      String nestedSelect,
      String nestedResultMap,
      String notNullColumn,
      String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler,
      List<ResultFlag> flags) {
      return buildResultMapping(
        resultType, property, column, javaType, jdbcType, nestedSelect,
        nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
  }

  /**
   * 依据 langClass 获取 LanguageDriver 对象
   * @param langClass
   * @return
   */
  public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
    if (langClass != null) {
      //1.langClass不为空，注册 LanguageDriver的 class对象
      configuration.getLanguageRegistry().register(langClass);
    } else {
      //1.langClass为空，则使用默认类，SQL节点lang属性为空时默认使用 XMLLanguageDriver class对象
      langClass = configuration.getLanguageRegistry().getDefaultDriverClass();
    }
    //2.依据 class 获取 LanguageDriver 对象
    return configuration.getLanguageRegistry().getDriver(langClass);
  }

  /** Backward compatibility signature */
  public MappedStatement addMappedStatement(
    String id,
    SqlSource sqlSource,
    StatementType statementType,
    SqlCommandType sqlCommandType,
    Integer fetchSize,
    Integer timeout,
    String parameterMap,
    Class<?> parameterType,
    String resultMap,
    Class<?> resultType,
    ResultSetType resultSetType,
    boolean flushCache,
    boolean useCache,
    boolean resultOrdered,
    KeyGenerator keyGenerator,
    String keyProperty,
    String keyColumn,
    String databaseId,
    LanguageDriver lang) {
    return addMappedStatement(
      id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
      parameterMap, parameterType, resultMap, resultType, resultSetType,
      flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
      keyColumn, databaseId, lang, null);
  }

}
