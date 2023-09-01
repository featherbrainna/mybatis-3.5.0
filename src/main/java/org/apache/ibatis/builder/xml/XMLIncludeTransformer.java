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

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * include 节点解析器
 * 将 include 节点替换成 sql 节点中定义的 SQL 片段
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  /**
   * 解析 source 节点下的 include 节点
   * @param source
   */
  public void applyIncludes(Node source) {
    //1.获取 mybatis-config.xml 中 properties 节点下定义的变量集合
    Properties variablesContext = new Properties();
    Properties configurationVariables = configuration.getVariables();
    if (configurationVariables != null) {
      variablesContext.putAll(configurationVariables);
    }
    //2.处理 source 节点下的 include 子节点
    applyIncludes(source, variablesContext, false);
  }

  /**
   * 递归的解析 source 节点下的 include 节点
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    //1.如果 source 节点是 include 节点
    if (source.getNodeName().equals("include")) {
      //1.1.查找 source 节点 refid 属性引用的 sql 节点，返回的是深克隆的 Node 对象
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      //1.2.解析 include 节点下的 property 节点，将得到的键值和configuration.variables的键值合并形成新的 Properties 对象，用于替换占位符
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      //1.3.递归处理 include 节点，在 sql 节点中可能会使用 include 引用了其它SQL片段
      applyIncludes(toInclude, toIncludeContext, true);
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      //1.4.将 include 节点替换成 sql 节点
      source.getParentNode().replaceChild(toInclude, source);
      //1.5.将 sql 节点的文本子节点添加到 sql 节点前面
      while (toInclude.hasChildNodes()) {
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      //1.6.删除 sql 节点
      toInclude.getParentNode().removeChild(toInclude);
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      //2.如果节点类型为 Node.ELEMENT_NODE
      //2.1如果在处理 sql 节点中，则替换其上的属性，例如 <sql id="123" lang="${cpu}"> 的情况，lang 属性是可以被替换的
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      //2.2遍历子节点，递归调用 #applyIncludes(...)方法
      // 【注意】：文本内容也属于子节点！！！
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        applyIncludes(children.item(i), variablesContext, included);
      }
    } else if (included && source.getNodeType() == Node.TEXT_NODE
        && !variablesContext.isEmpty()) {
      //3.在处理 sql 节点中，并且节点类型为 Node.TEXT_NODE，并且变量非空则进行变量的替换，并修改原节点source
      // replace variables in text node
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  /**
   * 在configuration获取  refid 的 sql 节点
   * @param refid 指定的 sql 节点完整id
   * @param variables 全局变量
   * @return 获取指定 sql节点id 的 sql节点
   */
  private Node findSqlFragment(String refid, Properties variables) {
    //1.解析 refid 中的属性变量引用
    refid = PropertyParser.parse(refid, variables);
    //2.拼接 namespace
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      //3.从 configuration.sqlFragments 中查找指定的 sql 节点
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      //4.转换成克隆 Node 节点返回
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  /**
   * 获取 node 节点的 name 属性值
   * @param node 指定节点
   * @param name 指定属性
   * @return 返回指定节点指定属性值
   */
  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * 从 include 节点和 全局配置 获取属性键值 Properties 对象
   * Read placeholders and their values from include node definition.
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    Map<String, String> declaredProperties = null;
    //1.获取 include 节点子节点 property
    NodeList children = node.getChildNodes();
    //2.遍历子节点
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        //3.获取子节点 property 的 name 属性
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        //4.获取子节点 property 的 value 属性并解析其中的占位符
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<>();
        }
        //5.属性键值放入 declaredProperties 映射集合
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    //6.将解析include子节点的属性与全局属性合并返回
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
