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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * XML 动态语句(SQL)构建器，负责将 SQL 解析成 SqlSource 对象（动态SQL重要核心组件）
 * 用于解析带sql语句的节点，并生成 SqlSource 对象返回。SqlSource 中封装了 SqlNode 集合
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

  /**
   * xml的SQL节点，包括SQL节点和selectKey节点
   */
  private final XNode context;
  /**
   * 标记是否是动态的
   */
  private boolean isDynamic;
  /**
   * SQL参数类型
   */
  private final Class<?> parameterType;
  /**
   * 动态 SQL 的 NodeHandler 映射集合，构造器中初始化集合元素
   */
  private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();

  public XMLScriptBuilder(Configuration configuration, XNode context) {
    this(configuration, context, null);
  }

  /**
   * 构造器，初始化参数
   * @param configuration
   * @param context
   * @param parameterType
   */
  public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
    super(configuration);
    this.context = context;
    this.parameterType = parameterType;
    //初始化 nodeHandlerMap 属性集合元素
    initNodeHandlerMap();
  }

  /**
   * 初始化 nodeHandlerMap 属性集合元素
   */
  private void initNodeHandlerMap() {
    nodeHandlerMap.put("trim", new TrimHandler());
    nodeHandlerMap.put("where", new WhereHandler());
    nodeHandlerMap.put("set", new SetHandler());
    nodeHandlerMap.put("foreach", new ForEachHandler());
    nodeHandlerMap.put("if", new IfHandler());
    nodeHandlerMap.put("choose", new ChooseHandler());
    nodeHandlerMap.put("when", new IfHandler());
    nodeHandlerMap.put("otherwise", new OtherwiseHandler());
    nodeHandlerMap.put("bind", new BindHandler());
  }

  /**
   * 解析 SQL节点和selectKey节点 创建 SqlSource 对象。核心方法
   * @return
   */
  public SqlSource parseScriptNode() {
    //1.解析动态SQL，并封装为 MixedSqlNode 对象
    MixedSqlNode rootSqlNode = parseDynamicTags(context);
    SqlSource sqlSource = null;
    //2.根据是否是动态SQL，创建相应的 SqlSource 对象(isDynamic在解析动态SQL时设置)
    if (isDynamic) {
      //2.1包含 动态SQL 节点或未解析的 "${}" 占位符，即包含树枝节点或 TextSqlNode 叶子节点的 rootSqlNode
      sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
      //2.1只包含"#{}"占位符的sql语句，即只包含 StaticTextSqlNode 叶子节点不包含树枝节点的 rootSqlNode
      sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
  }

  /**
   * 解析当前node节点的 SQL 子节点成 MixedSqlNode 对象
   * @param node 指定节点
   * @return
   */
  protected MixedSqlNode parseDynamicTags(XNode node) {
    //用于记录生成的sqlNode集合
    List<SqlNode> contents = new ArrayList<>();
    //1.获取当前节点的所有子节点 NodeList 对象
    NodeList children = node.getNode().getChildNodes();
    //2.遍历当前节点的子节点
    for (int i = 0; i < children.getLength(); i++) {
      //3.获取子节点，该过程会将能解析的“${}”都解析掉
      XNode child = node.newXNode(children.item(i));
      //4.如果子节点类型为文本节点
      if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
        //4.1获取文本内容
        String data = child.getStringBody("");
        //4.2封装文本内容创建 textSqlNode 对象
        TextSqlNode textSqlNode = new TextSqlNode(data);
        //4.3如果 textSqlNode 是动态节点，则直接添加到 contents 集合，再设置 isDynamic 属性为true
        // 如果含有未解析的 ${}，则为动态SQL
        if (textSqlNode.isDynamic()) {
          contents.add(textSqlNode);
          isDynamic = true;
        } else {
          //4.3如果 textSqlNode 不是动态节点，则封装文本内容为 StaticTextSqlNode 对象，添加到 contents 集合
          contents.add(new StaticTextSqlNode(data));
        }
      } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
        //4.如果子节点是一个元素节点，那么一定是动态SQL，并且根据不同的动态标签生成
        //4.1获取节点标签名
        String nodeName = child.getNode().getNodeName();
        //4.2获取标签对应的动态sql处理器 NodeHandler 对象
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        //4.3 handler 查找不到抛出异常
        if (handler == null) {
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
        //4.4解析处理动态 sql，并将解析得到的 SqlNode 对象放入 contents 集合保存（递归的处理）
        handler.handleNode(child, contents);
        //标记 isDynamic 为 true
        isDynamic = true;
      }
    }
    //5.使用 sqlNode 集合创建 MixedSqlNode 对象
    return new MixedSqlNode(contents);
  }

  /**
   * Node 处理器接口
   */
  private interface NodeHandler {
    /**
     * 处理 XNode
     * @param nodeToHandle 要处理的 XNode 节点
     * @param targetContents 目标的 SqlNode 数组。被处理的 XNode 节点会创建成对应的 SqlNode 对象
     */
    void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
  }

  /**
   * <bind /> 标签的处理器
   */
  private class BindHandler implements NodeHandler {
    public BindHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.解析nodeToHandle获取 name、value 属性
      final String name = nodeToHandle.getStringAttribute("name");
      final String expression = nodeToHandle.getStringAttribute("value");
      //2.创建 VarDeclSqlNode 对象
      final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
      //3.添加到 targetContents 中
      targetContents.add(node);
    }
  }

  /**
   * <trim /> 标签的处理器
   */
  private class TrimHandler implements NodeHandler {
    public TrimHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.解析nodeToHandle内部的 SQL 子节点，成 MixedSqlNode 对象（递归的调用parseDynamicTags）
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //2.获取 nodeToHandle 节点的 prefix、prefixOverrides、suffix、suffixOverrides 属性
      String prefix = nodeToHandle.getStringAttribute("prefix");
      String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
      String suffix = nodeToHandle.getStringAttribute("suffix");
      String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
      //3.创建 TrimSqlNode 对象
      TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
      //4.添加到 targetContents 中
      targetContents.add(trim);
    }
  }

  /**
   * <where /> 标签的处理器
   */
  private class WhereHandler implements NodeHandler {
    public WhereHandler() {
      // Prevent Synthetic Access
    }

    /**
     * 处理节点
     * @param nodeToHandle 目标 where 节点
     * @param targetContents 目标 SqlNode 集合对象
     */
    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.调用 parseDynamicTags（）方法，解析 where 节点的子节点，包装为 MixedSqlNode 对象
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //2.再包装为 WhereSqlNode 对象
      WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
      //3.添加到 targetContents 集合
      targetContents.add(where);
    }
  }

  /**
   * <set /> 标签的处理器
   */
  private class SetHandler implements NodeHandler {
    public SetHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.解析set节点的 SQL 子节点，成 MixedSqlNode 对象
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //2.创建 SetSqlNode 对象
      SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
      //3.添加到 targetContents 中
      targetContents.add(set);
    }
  }

  /**
   * <foreach /> 标签的处理器
   */
  private class ForEachHandler implements NodeHandler {
    public ForEachHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.解析foreach节点的 SQL 子节点，成 MixedSqlNode 对象（递归的调用parseDynamicTags）
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //2.解析获取 foreach 节点的 collection、item、index、open、close、separator 属性
      String collection = nodeToHandle.getStringAttribute("collection");
      String item = nodeToHandle.getStringAttribute("item");
      String index = nodeToHandle.getStringAttribute("index");
      String open = nodeToHandle.getStringAttribute("open");
      String close = nodeToHandle.getStringAttribute("close");
      String separator = nodeToHandle.getStringAttribute("separator");
      //3.创建 ForEachSqlNode 对象
      ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
      //4.添加到 targetContents 中
      targetContents.add(forEachSqlNode);
    }
  }

  /**
   * <if /> 标签的处理器
   */
  private class IfHandler implements NodeHandler {
    public IfHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.解析if节点的 SQL 子节点，成 MixedSqlNode 对象
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //2.解析获取 if 节点的 test 属性
      String test = nodeToHandle.getStringAttribute("test");
      //3.创建 IfSqlNode 对象
      IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
      //4.添加到 targetContents 中
      targetContents.add(ifSqlNode);
    }
  }

  /**
   * <otherwise /> 标签的处理器
   * 对于 <otherwise /> 标签，解析的结果是 MixedSqlNode 对象
   */
  private class OtherwiseHandler implements NodeHandler {
    public OtherwiseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.解析otherwise节点的 SQL 子节点，成 MixedSqlNode 对象
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //2.添加到 targetContents 中
      targetContents.add(mixedSqlNode);
    }
  }

  /**
   * <choose /> 标签的处理器
   * 通过组合 IfHandler 和 OtherwiseHandler 两个处理器，实现对子节点们的解析
   */
  private class ChooseHandler implements NodeHandler {
    public ChooseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //1.创建 when子节点 和 otherwise子节点 对应的 SqlNode 集合
      List<SqlNode> whenSqlNodes = new ArrayList<>();
      List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
      //2.解析 when子节点 和 otherwise子节点
      handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
      //3.获取 otherwise子节点 对应的 MixedSqlNode 对象
      SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
      //4.创建 ChooseSqlNode 对象
      ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
      //5.添加到 targetContents 中
      targetContents.add(chooseSqlNode);
    }

    /**
     * 解析 choose节点 的子节点，包括when子节点和otherwise子节点
     * @param chooseSqlNode choose节点对象
     * @param ifSqlNodes when子节点对应的SqlNode集合
     * @param defaultSqlNodes otherwise子节点对应的SqlNode集合
     */
    private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
      //1.获取 choose节点 的子节点集合
      List<XNode> children = chooseSqlNode.getChildren();
      //2.遍历子节点集合
      for (XNode child : children) {
        //3.获取子节点标签名
        String nodeName = child.getNode().getNodeName();
        //4.从 nodeHandlerMap 映射集合中依据标签名获取 NodeHandler 对象
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        //5.如果 NodeHandler 对象是 IfHandler 类型，即 when 节点
        if (handler instanceof IfHandler) {
          //处理when节点，添加到 ifSqlNodes 集合
          handler.handleNode(child, ifSqlNodes);
        } else if (handler instanceof OtherwiseHandler) {
          //5.如果 NodeHandler 对象是 OtherwiseHandler 类型，即 otherwise 节点
          //处理otherwise节点，添加到 defaultSqlNodes 集合
          handler.handleNode(child, defaultSqlNodes);
        }
      }
    }

    /**
     * 获取 otherwise 子节点对应的 MixedSqlNode 对象
     * @param defaultSqlNodes
     * @return
     */
    private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
      SqlNode defaultSqlNode = null;
      //1.defaultSqlNodes集合元素只有一个，则获取返回
      if (defaultSqlNodes.size() == 1) {
        defaultSqlNode = defaultSqlNodes.get(0);
      } else if (defaultSqlNodes.size() > 1) {
        //2.defaultSqlNodes集合元素有多个，则抛出异常
        throw new BuilderException("Too many default (otherwise) elements in choose statement.");
      }
      return defaultSqlNode;
    }
  }

}
