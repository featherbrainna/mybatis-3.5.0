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

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  /**
   * MapperBuilderAssistant 对象，构建对象小助手
   */
  private final MapperBuilderAssistant builderAssistant;
  /**
   * 当前 mapper.xml 中的节点，例如 select\insert\update\delete 节点
   */
  private final XNode context;
  /**
   * 要求的 databaseId。即 configuration 中的 databaseId
   */
  private final String requiredDatabaseId;

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  /**
   * 构造器，初始化所有属性。由 XMLMapperBuilder#buildStatementFromContext(List, String) 方法调用
   * @param configuration
   * @param builderAssistant
   * @param context
   * @param databaseId
   */
  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  /**
   * 执行 Statement 解析。解析 SQL 节点的入口函数
   */
  public void parseStatementNode() {
    //1.获取SQL节点的 id 属性以及 databaseId 属性
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");

    //2.如果当前节点的 databaseId 与需要的 configuration 的 databaseId 不匹配，则不加载该 SQL 节点
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }

    //3.获取SQL节点的各种属性。如：parameterType、resultType、resultMap等
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);
    String resultMap = context.getStringAttribute("resultMap");
    String resultType = context.getStringAttribute("resultType");
    String lang = context.getStringAttribute("lang");
    //4.获取 lang 对应的 LanguageDriver 对象
    LanguageDriver langDriver = getLanguageDriver(lang);

    //5.获取 resultType 对应的类class对象
    Class<?> resultTypeClass = resolveClass(resultType);
    //6.获取SQL节点的 resultSetType 属性，枚举值
    String resultSetType = context.getStringAttribute("resultSetType");
    //7.获取SQL节点的 statementType 属性对应的枚举值
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);

    //8.根据 SQL节点的标签名 获取SQL节点的 SqlCommandType 枚举值
    String nodeName = context.getNode().getNodeName();
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    //9.获取SQL节点的 flushCache、useCache、resultOrdered 属性
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    //insert|update|delete 语句会清空缓存
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    //select 语句会使用缓存
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // Include Fragments before parsing
    //10.创建 XMLIncludeTransformer 对象，用于解析SQL节点中的 include 节点
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    // Parse selectKey after includes and remove them.
    //11.解析 selectKey 节点
    processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    //12.解析SQL节点文本内容，通过 LanguageDriver 对象创建 SqlSource 对象
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    //13.获取 KeyGenerator 对象
    String resultSets = context.getStringAttribute("resultSets");
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");
    //13.1优先，从 configuration 中获得 KeyGenerator 对象。如果存在，意味着是 selectKey 节点配置的
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    if (configuration.hasKeyGenerator(keyStatementId)) {
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      //13.2其次，根据标签属性的情况，判断是否使用对应的 Jdbc3KeyGenerator 或者 NoKeyGenerator 对象
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",// 优先，基于 useGeneratedKeys 属性判断
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))// 其次，基于全局的 useGeneratedKeys 配置 + 是否为插入语句类型
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    //14.创建 MappedStatement 对象，并添加到 configuration 对象
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  /**
   * 解析 selectKey 节点，底层调用 {@link #parseSelectKeyNodes(String, List, Class, LanguageDriver, String)} 执行真正的解析
   * @param id SQL 节点的 id 属性
   * @param parameterTypeClass 参数类型 class 对象
   * @param langDriver LanguageDriver对象
   */
  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    //1.获取 selectKey 集合
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");
    //2.获取 configuration.databaseId 执行解析 selectKey 节点
    if (configuration.getDatabaseId() != null) {
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    //3.移除 selectKey 节点集合
    removeSelectKeyNodes(selectKeyNodes);
  }

  /**
   * 解析 selectKey 节点集合。主要用于选择匹配的 databaseId 的 selectKey 节点进行解析
   * 底层调用 {@link #parseSelectKeyNode(String, XNode, Class, LanguageDriver, String)} 实现
   * @param parentId selectKey 节点的父节点 id 属性
   * @param list selectKey 节点集合
   * @param parameterTypeClass 参数类型class对象
   * @param langDriver LanguageDriver对象
   * @param skRequiredDatabaseId databaseId值
   */
  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    //1.遍历 selectKey 节点集合
    for (XNode nodeToHandle : list) {
      //2.获取完整的 id，格式为 ${id}!selectKey
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      //3.获取 selectKey 节点的 databaseId  属性
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      //4.判断当前selectKey节点的 databaseId 是否匹配，匹配则进行解析
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  /**
   * 执行解析单个 selectKey 节点
   * @param id 完整id
   * @param nodeToHandle 待解析的 selectKey 节点
   * @param parameterTypeClass SQL节点参数类型class对象
   * @param langDriver LanguageDriver对象
   * @param databaseId 匹配的databaseId
   */
  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    //1.获取 selectKey 节点的 resultType、keyProperty、keyColumn 等属性
    String resultType = nodeToHandle.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    //defaults
    //2.设置创建 MappedStatement 需要的默认属性
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    //3.创建 sqlSource 对象
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    //selectKey 节点只能配置 select 语句
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    //4.创建 MappedStatement 对象，并添加到 configuration
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

    //5.获取 selectKeyGenerator 的编号，格式为 ${namespace}.${id}
    id = builderAssistant.applyCurrentNamespace(id, false);

    //6.依据 id 获取添加到 configuration 的 MappedStatement 对象
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    //7.将 MappedStatement 封装为 SelectKeyGenerator 对象，添加到 configuration.keyGenerators 映射集合
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  /**
   * 判断 databaseId 是否匹配
   * @param id 节点的 id 属性
   * @param databaseId 节点的 databaseId 属性
   * @param requiredDatabaseId configuration的 databaseId 属性
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    //1.如果requiredDatabaseId不为空且与databaseId不匹配，则返回 false
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      //2.如果 requiredDatabaseId 为空，但是 databaseId 存在，说明还是不匹配，则返回false
      if (databaseId != null) {
        return false;
      }
      // skip this statement if there is a previous one with a not null databaseId
      //判断 mappedStatements 是否已经存在
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (this.configuration.hasStatement(id, false)) {
        MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
        //若存在，则判断原有的 sqlFragment 是否 databaseId 为空。因为，当前 databaseId 为空，这样两者才能匹配。
        if (previous.getDatabaseId() != null) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * 获得 lang 对应的 LanguageDriver 对象
   * @param lang
   * @return
   */
  private LanguageDriver getLanguageDriver(String lang) {
    //1.解析 lang 对应的class类对象
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang);
    }
    //2.从 configuration 获得 LanguageDriver 对象
    return builderAssistant.getLanguageDriver(langClass);
  }

}
