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
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.*;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.io.InputStream;
import java.io.Reader;
import java.util.*;

/**
 * Mapper XML 配置构建器，主要负责解析 Mapper 映射配置文件。
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

  /**
   * 基于 java XPath 的解析器 XPathParser 对象
   */
  private final XPathParser parser;
  /**
   * Mapper构造器助手
   */
  private final MapperBuilderAssistant builderAssistant;
  /**
   * 可被其他语句引用的可重用sql语句块的集合，从configuration传入
   * 例如：<sql id="userColumns"> ${alias}.id,${alias}.username,${alias}.password </sql>
   */
  private final Map<String, XNode> sqlFragments;
  /**
   * 资源引用的地址
   */
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  /**
   * 构造器构造对应的XPathParser对象，由 {@link XMLConfigBuilder} 的方法调用，传入从mybatis-config.xml解析的参数值 和 Configuration对象
   * @param inputStream mapper.xml的输入流
   * @param configuration Configuration对象
   * @param resource 资源地址
   * @param sqlFragments 可重用sql引用地址
   */
  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  /**
   * 底层构造器，初始化所有对象属性
   * @param parser XPathParser对象
   * @param configuration Configuration对象
   * @param resource 资源地址
   * @param sqlFragments 可重用sql引用地址
   */
  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    //传入 configuration、resource 创建 MapperBuilderAssistant 对象
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 核心对外解析方法。解析 Mapper XML 配置文件和 mpper接口
   */
  public void parse() {
    //1.从configuration判断是否已经加载过 mapper.xml 映射文件，没有则执行以下处理，否则继续
    if (!configuration.isResourceLoaded(resource)) {
      //1.1解析 mapper节点
      configurationElement(parser.evalNode("/mapper"));
      //1.2在configuration标记该 mapper.xml 已经加载过
      configuration.addLoadedResource(resource);
      //1.3完成映射配置文件与对应 Mapper 接口的绑定
      bindMapperForNamespace();
    }

    //2.解析configurationElement（）方法中解析失败的 resultMap 节点
    parsePendingResultMaps();
    //3.解析configurationElement（）方法中解析失败的 cache-ref 节点
    parsePendingCacheRefs();
    //4.解析configurationElement（）方法中解析失败的 SQL 节点
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析 mapper 节点
   * @param context mapper 节点
   */
  private void configurationElement(XNode context) {
    try {
      //1.获取 mapper 节点的 namespace 属性
      String namespace = context.getStringAttribute("namespace");
      //2.如果 namespace 属性为null或者为""则直接抛出异常，即mapper.xml没有标明对应的mapper接口
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      //3.设置 builderAssistant 属性的 currentNamespace 属性，记录当前的命名空间
      builderAssistant.setCurrentNamespace(namespace);
      //4.解析 cache-ref 节点
      cacheRefElement(context.evalNode("cache-ref"));
      //5.解析 cache 节点
      cacheElement(context.evalNode("cache"));
      //6.解析 parameterMap 节点（该节点已废弃，不要使用）
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      //7.解析 resultMap 节点
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      //8.解析 sql 节点
      sqlElement(context.evalNodes("/mapper/sql"));
      //9.解析 select|insert|update|delete 节点
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  /**
   * 解析 select、insert、update、delete 节点集合，由 {@link #configurationElement(XNode)} 调用
   * @param list
   */
  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  /**
   * 解析 select、insert、update、delete 节点集合，由 {@link #buildStatementFromContext(List)} 调用
   * @param list 节点集合
   * @param requiredDatabaseId configuration中的databaseId属性（在解析mybatis-config.xml时初始化）
   */
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    //1.遍历 select、insert、update、delete 节点集合
    for (XNode context : list) {
      //2.创建 XMLStatementBuilder 对象
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        //3.执行节点解析
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        //3.解析失败，添加到 configuration 中
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  /**
   * 在解析 ResultMap 节点的 extends 属性失败时，放入 configuration 的 ResultMapResolver 继续再次解析
   */
  private void parsePendingResultMaps() {
    //1.从 configuration 获取 ResultMapResolver 集合
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      //2.遍历 ResultMapResolver 解析出 ResultMap
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          //解析
          iter.next().resolve();
          //从 incompleteResultMaps 集合移除元素
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
          // 解析失败，不抛出异常
        }
      }
    }
  }

  /**
   * 在解析 cacheRef 节点的 namespace 属性失败时，放入 configuration 的 CacheRefResolver 继续再次解析
   */
  private void parsePendingCacheRefs() {
    //1.从 configuration 获取 CacheRefResolver 集合
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      //2.遍历 CacheRefResolver 解析出 Cache
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          //执行解析
          iter.next().resolveCacheRef();
          //从 incompleteCacheRefs 集合移除元素
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
          //解析失败，不抛出异常
        }
      }
    }
  }

  /**
   * 在解析 select|insert|update|delete 节点失败时，放入 configuration 的 XMLStatementBuilder 继续再次解析
   */
  private void parsePendingStatements() {
    //1.从 configuration 获取 XMLStatementBuilder 集合
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      //2.遍历解析 XMLStatementBuilder
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          //执行解析
          iter.next().parseStatementNode();
          //从 incompleteStatements 集合删除元素
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
          //解析失败，不抛出异常
        }
      }
    }
  }

  /**
   * 解析 cache-ref 节点
   * @param context cache-ref 节点
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      //1.获取 cache-ref 节点的 namespace 属性，
      // 并将当前mapper配置文件的namespace与被引用的cache所在的namespace添加到 configuration.cacheRefMap集合中
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      //2.创建该 namespace 对应的 CacheRefResolver 对象
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        //3.解析 Cache 引用，底层设置 builderAssistant 中的 currentCache属性和 unresolvedCacheRef属性
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        //3.解析失败，因为此处指向的 Cache 对象可能未初始化，添加到 configuration 的IncompleteCacheRef中
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  /**
   * 解析 cache 节点
   * @param context cache 节点
   */
  private void cacheElement(XNode context) {
    if (context != null) {
      //1.获取 cache 节点的 type 属性，即cache类型，默认为 PERPETUAL 类型
      String type = context.getStringAttribute("type", "PERPETUAL");
      //2.解析 type 属性为 class 对象
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      //3.获取 cache 节点的 eviction 属性，即清理策略，默认为 LRU 策略
      String eviction = context.getStringAttribute("eviction", "LRU");
      //4.解析 eviction 属性为 Cache 装饰器类型class对象
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      //5.获取 cache 节点的 flushInterval 属性
      Long flushInterval = context.getLongAttribute("flushInterval");
      //6.获取 cache 节点的 size 属性
      Integer size = context.getIntAttribute("size");
      //7.获取 cache 节点的 readOnly 属性
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      //8.获取 cache 节点的 blocking 属性
      boolean blocking = context.getBooleanAttribute("blocking", false);
      //9.获取 cache 节点的子节点为 Properties 对象
      Properties props = context.getChildrenAsProperties();
      //10.通过 builderAssistant 属性的对象创建 Cache对象，并添加到 Configuration.caches集合中保存
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  /**
   * 解析 resultMap 节点集合
   * @param list resultMap 节点集合
   * @throws Exception
   */
  private void resultMapElements(List<XNode> list) throws Exception {
    //1.遍历 resultMap 节点们
    for (XNode resultMapNode : list) {
      try {
        //2.处理单个 resultMap 节点
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  /**
   * 解析单个 resultMap 节点
   * @param resultMapNode resultMap 节点
   * @return
   * @throws Exception
   */
  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList(), null);
  }

  /**
   * 底层解析单个 resultMap 节点方法
   * @param resultMapNode  resultMap 节点
   * @param additionalResultMappings
   * @param enclosingType
   * @return
   * @throws Exception
   */
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    //1.获取 resultMap 节点的 id 属性。默认值会拼装所有父节点的id或value或property属性
    String id = resultMapNode.getStringAttribute("id",
        resultMapNode.getValueBasedIdentifier());
    //2.获取 resultMap 节点的 type 属性。表示结果将被映射成 type 指定的类型对象。默认值会选择ofType、resultType、javaType属性
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    //3.获取 resultMap 节点的 extends 属性。该属性指定了该resultMap节点的继承关系
    String extend = resultMapNode.getStringAttribute("extends");
    //4.获取 resultMap 节点的 autoMapping 属性。
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    //5.解析 type 对应的 class 对象
    Class<?> typeClass = resolveClass(type);
    if (typeClass == null) {
      typeClass = inheritEnclosingType(resultMapNode, enclosingType);
    }
    Discriminator discriminator = null;
    //6.创建 resultMap 节点的子节点集合，即 ResultMapping 集合
    List<ResultMapping> resultMappings = new ArrayList<>();
    resultMappings.addAll(additionalResultMappings);
    //7.获取并遍历处理 resultMap 节点的子节点
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      //8.子节点为 constructor 节点
      if ("constructor".equals(resultChild.getName())) {
        //处理 constructor 节点
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        //8.子节点为 discriminator 节点，处理
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        //8.子节点为 其它 节点
        List<ResultFlag> flags = new ArrayList<>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        // 创建 ResultMapping 对象，并添加到 resultMappings 集合中
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    //9.创建 ResultMapResolver 对象，执行解析
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      //10.解析返回 ResultMap 对象，底层依赖 MapperBuilderAssistant 构建 ResultMap 对象
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      //10.解析 resultMap 失败，添加到 configuration 中
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
    if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      String property = resultMapNode.getStringAttribute("property");
      if (property != null && enclosingType != null) {
        MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
        return metaResultType.getSetterType(property);
      }
    } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      return enclosingType;
    }
    return null;
  }

  /**
   * 解析 resultMap 节点下的 constructor 节点
   * 底层调用 {@link #buildResultMappingFromContext(XNode, Class, List)} 实现 constructor 节点处理，
   * 将 constructor 节点的子节点作为 resultMapping 对象添加到resultMappings集合
   * @param resultChild constructor 节点
   * @param resultType resultMap 节点的 type 属性
   * @param resultMappings resultMappings集合对象
   * @throws Exception 异常
   */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    //1.获取 constructor 节点的子节点
    List<XNode> argChildren = resultChild.getChildren();
    //2.遍历子节点
    for (XNode argChild : argChildren) {
      //3.创建标志 ResultFlag 集合
      List<ResultFlag> flags = new ArrayList<>();
      //4.添加 ResultFlag.CONSTRUCTOR 标志到 ResultFlag 集合
      flags.add(ResultFlag.CONSTRUCTOR);
      //5.如果子节点为 idArg 节点，则添加 ResultFlag.ID 标志到 ResultFlag 集合
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      //6.将 constructor 节点的子节点构建成 ResultMapping 对象，并添加到 resultMappings 集合
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  /**
   * 解析 resultMap 节点下的 discriminator 节点（鉴别器）
   * 底层调用 {@link #processNestedResultMappings(XNode, List, Class)} 来处理 case 子节点
   * @param context discriminator 节点
   * @param resultType resultMap 节点的 type 属性
   * @param resultMappings resultMappings集合对象
   * @return 解析成功的Discriminator对象
   * @throws Exception 异常
   */
  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    //1.解析 discriminator 节点的 column、javaType、jdbcType、typeHandler 属性
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    //2.解析获得 javaType、typeHandler、typeHandler 属性指定的 class 对象
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(typeHandler);
    //3.创建 discriminatorMap 映射对象（以 case 节点的 value 属性为key，以 case 节点的 resultMap 属性为 value）
    Map<String, String> discriminatorMap = new HashMap<>();
    //4.遍历 discriminator 节点的子节点
    for (XNode caseChild : context.getChildren()) {
      //获取子节点case的 value 属性
      String value = caseChild.getStringAttribute("value");
      //获取子节点case的 resultMap 属性
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
      //以 case 节点的 value 属性为key，以 case 节点的 resultMap 属性为 value，添加到 discriminatorMap 集合
      discriminatorMap.put(value, resultMap);
    }
    //5.创建 Discriminator 对象
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  /**
   * 解析 sql节点集合，由 {@link #configurationElement(XNode)} 调用
   * @param list
   */
  private void sqlElement(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  /**
   * 解析 sql 节点集合，由 {@link #sqlElement(List)} 调用
   * @param list
   * @param requiredDatabaseId
   */
  private void sqlElement(List<XNode> list, String requiredDatabaseId) {
    //1.遍历所有的 sql 节点
    for (XNode context : list) {
      //2.获取 sql 节点的 databaseId 属性
      String databaseId = context.getStringAttribute("databaseId");
      //3.获取 sql 节点的 id 属性
      String id = context.getStringAttribute("id");
      //4.id属性拼接namespace,得到完整的id属性
      id = builderAssistant.applyCurrentNamespace(id, false);
      //5.判断 databaseId 是否匹配
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        //添加到 configuration.sqlFragments 中
        sqlFragments.put(id, context);
      }
    }
  }

  /**
   * 判断 databaseId 属性是否匹配configuration中的databaseId
   * @param id sql节点的编号
   * @param databaseId sql节点的 databaseId 属性
   * @param requiredDatabaseId configuration中的databaseId属性
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    //1.如果configuration中的databaseId属性不为空
    if (requiredDatabaseId != null) {
      //1.1如果 sql节点的databaseId属性 与 configuration中的databaseId属性 不相同，返回 false
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      //1.如果configuration中的databaseId属性为空
      //1.1如果sql节点的 databaseId 属性不为空，返回 false
      if (databaseId != null) {
        return false;
      }
      // skip this fragment if there is a previous one with a not null databaseId
      //1.2判断是否已经存在该sql节点
      if (this.sqlFragments.containsKey(id)) {
        XNode context = this.sqlFragments.get(id);
        //若存在，则判断原有的 sqlFragment 是否 databaseId 为空，不为空则返回 false
        if (context.getStringAttribute("databaseId") != null) {
          return false;
        }
      }
    }
    //2.其他情况返回 true
    return true;
  }

  /**
   * 解析当前节点构建成 ResultMapping 对象。解析节点包括： id|result|association|collection|idArg|arg
   * @param context 指定 resultMap 节点下的子节点
   * @param resultType resultMap 节点的 type 属性
   * @param flags 节点标志集合
   * @return ResultMapping对象
   * @throws Exception 异常
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    //1.获取当前节点的 property 属性
    String property;
    //1.如果节点有 CONSTRUCTOR 标志，则使用 name 属性作为当前节点的 property 属性
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      //1.如果节点没有 CONSTRUCTOR 标志，则直接使用 property 属性
      property = context.getStringAttribute("property");
    }
    //2.获取当前节点的 column、javaType、jdbcType 属性
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    //3.获取当前节点的 select、resultMap、resultSet 等属性
    String nestedSelect = context.getStringAttribute("select");
    //如果未指定 association|collection 节点的 resultMap 属性，则是匿名的嵌套映射，
    //通过processNestedResultMappings（）方法解析匿名的嵌套映射
    String nestedResultMap = context.getStringAttribute("resultMap",
        processNestedResultMappings(context, Collections.<ResultMapping> emptyList(), resultType));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    //2.解析出 javaType、jdbcType、typeHandler 属性对应的 class 对象
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    //3.使用 MapperBuilderAssistant 对象构建  ResultMapping 对象
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  /**
   * 处理内嵌套的 ResultMapping 节点
   * 底层递归调用 {@link #resultMapElement(XNode, List, Class)} 处理
   * @param context association|collection|case节点
   * @param resultMappings resultMappings集合
   * @param enclosingType
   * @return
   * @throws Exception
   */
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) throws Exception {
    //1.当前节点为 association|collection|case 节点时，则可能有嵌套的 ResultMapping
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      //1.1如果当前节点的 select 属性为空，则进行处理否则什么都不做
      if (context.getStringAttribute("select") == null) {
        //1.1.1校验 collection 节点是否合法
        validateCollection(context, enclosingType);
        //1.1.2递归调用 resultMapElement 获取嵌套的 ResultMap 对象
        //创建 ResultMap 对象，并添加到 Configuration.resultMaps 集合中
        ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
        return resultMap.getId();
      }
    }
    return null;
  }

  protected void validateCollection(XNode context, Class<?> enclosingType) {
    if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
      && context.getStringAttribute("resultType") == null) {
      MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
      String property = context.getStringAttribute("property");
      if (!metaResultType.hasSetter(property)) {
        throw new BuilderException(
          "Ambiguous collection type for property '" + property + "'. You must specify 'resultType' or 'resultMap'.");
      }
    }
  }

  /**
   * 完成映射配置文件与对应 Mapper 接口的绑定
   */
  private void bindMapperForNamespace() {
    //1.获取当前 mapper.xml 的命名空间
    String namespace = builderAssistant.getCurrentNamespace();
    //如果 namespace 不为空则进行处理，为空在前面解析时就抛出异常结束了，所以这里一定不为空
    if (namespace != null) {
      //2.获得 Mapper.xml 映射配置文件对应的 Mapper 接口，实际上类名就是 namespace
      Class<?> boundType = null;
      try {
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
      }
      //3.加载的类 class 对象不为空
      if (boundType != null) {
        //4.configuration不存在该 Mapper 接口，则进行注册添加
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          //标记 namespace 已经添加，避免 MapperAnnotationBuilder#loadXmlResource(...) 重复加载
          configuration.addLoadedResource("namespace:" + namespace);
          //添加到 configuration 中
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
