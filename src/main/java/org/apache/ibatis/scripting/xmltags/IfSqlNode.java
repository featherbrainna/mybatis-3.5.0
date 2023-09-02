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

/**
 * <if /> 标签的 SqlNode 实现类（树枝节点）
 * @author Clinton Begin
 */
public class IfSqlNode implements SqlNode {
  /**
   * OGNL 表达式计算器对象。用于解析 if 节点的 test 表达式的值
   */
  private final ExpressionEvaluator evaluator;
  /**
   * 记录 if 节点中的 test 表达式
   */
  private final String test;
  /**
   * 内嵌的 SqlNode 节点，记录 if 节点的子节点
   */
  private final SqlNode contents;

  public IfSqlNode(SqlNode contents, String test) {
    this.test = test;
    this.contents = contents;
    this.evaluator = new ExpressionEvaluator();
  }

  @Override
  public boolean apply(DynamicContext context) {
    //1.检测 test 表达式是否符合条件
    if (evaluator.evaluateBoolean(test, context.getBindings())) {
      //2.test表达式为true，则执行子节点的apply（）方法
      contents.apply(context);
      return true;
    }
    return false;
  }

}
