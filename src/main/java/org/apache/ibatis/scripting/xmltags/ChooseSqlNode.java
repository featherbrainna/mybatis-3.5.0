/**
 *    Copyright 2009-2017 the original author or authors.
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

import java.util.List;

/**
 * <choose /> 标签的 SqlNode 实现类（树枝节点）
 * MyBatis 会将<choose>标签解析成 ChooseSqINode ，将<when>标签解析成 IfSqlNode ，将<otherwise>标签解析成 MixedSqINode。
 * @author Clinton Begin
 */
public class ChooseSqlNode implements SqlNode {
  /**
   * otherwise 节点对应的 SqlNode
   */
  private final SqlNode defaultSqlNode;
  /**
   * when 节点对应的 IfSqlNode 集合
   */
  private final List<SqlNode> ifSqlNodes;

  public ChooseSqlNode(List<SqlNode> ifSqlNodes, SqlNode defaultSqlNode) {
    this.ifSqlNodes = ifSqlNodes;
    this.defaultSqlNode = defaultSqlNode;
  }

  @Override
  public boolean apply(DynamicContext context) {
    //1.遍历 when 节点集合的 ifSqlNode 集合，尝试应用，
    // 若应用成功只应用该一个节点返回 true，若都无法应用则继续
    for (SqlNode sqlNode : ifSqlNodes) {
      if (sqlNode.apply(context)) {
        return true;
      }
    }
    //2.再判断 otherwise 节点，是否存在，如存在则进行应用
    if (defaultSqlNode != null) {
      defaultSqlNode.apply(context);
      return true;
    }
    //3.返回失败
    return false;
  }
}
