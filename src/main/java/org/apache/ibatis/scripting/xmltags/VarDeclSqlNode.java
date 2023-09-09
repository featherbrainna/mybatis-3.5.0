/**
 *    Copyright 2009-2015 the original author or authors.
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

/**
 * <bind /> 标签的 SqlNode 实现类（叶子节点）
 * @author Frank D. Martinez [mnesarco]
 */
public class VarDeclSqlNode implements SqlNode {

  /**
   * bind 节点的 name 属性
   */
  private final String name;
  /**
   * bind 节点的 value 属性
   */
  private final String expression;

  public VarDeclSqlNode(String var, String exp) {
    name = var;
    expression = exp;
  }

  @Override
  public boolean apply(DynamicContext context) {
    //1.解析 OGNL 表达式获取值
    final Object value = OgnlCache.getValue(expression, context.getBindings());
    //2.将 name 和 表达式的值 存入 DynamicContext.bindings 集合中
    context.bind(name, value);
    return true;
  }

}
