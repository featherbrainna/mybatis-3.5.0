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

import ognl.Ognl;
import ognl.OgnlException;
import org.apache.ibatis.builder.BuilderException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OGNL 缓存类
 * Caches OGNL parsed expressions.
 *
 * @author Eduardo Macarron
 *
 * @see <a href='http://code.google.com/p/mybatis/issues/detail?id=342'>Issue 342</a>
 */
public final class OgnlCache {

  /**
   * OgnlMemberAccess 单例对象
   */
  private static final OgnlMemberAccess MEMBER_ACCESS = new OgnlMemberAccess();
  /**
   * OgnlClassResolver 单例对象
   */
  private static final OgnlClassResolver CLASS_RESOLVER = new OgnlClassResolver();
  /**
   * OGNL表达式的缓存的映射 单例对象
   * key:OGNL表达式
   * value:解析的表达式对象 {@link #parseExpression(String)}
   */
  private static final Map<String, Object> expressionCache = new ConcurrentHashMap<>();

  /**
   * 私有化构造器，静态工具方法
   */
  private OgnlCache() {
    // Prevent Instantiation of Static Class
  }

  /**
   * 根据 root 和 ONGL表达式 获取对应的结果
   * @param expression ONGL表达式
   * @param root root对象
   * @return Object类型的值
   */
  public static Object getValue(String expression, Object root) {
    try {
      //1.创建 OGNL Context 对象
      Map context = Ognl.createDefaultContext(root, MEMBER_ACCESS, CLASS_RESOLVER, null);
      //2.解析表达式
      //3.获得表达式对应的值
      return Ognl.getValue(parseExpression(expression), context, root);
    } catch (OgnlException e) {
      throw new BuilderException("Error evaluating expression '" + expression + "'. Cause: " + e, e);
    }
  }

  /**
   * 解析 ONGL表达式
   * @param expression ONGL表达式
   * @return 解析结果
   * @throws OgnlException
   */
  private static Object parseExpression(String expression) throws OgnlException {
    //1.查找缓存
    Object node = expressionCache.get(expression);
    if (node == null) {
      //2.解析表达式
      node = Ognl.parseExpression(expression);
      //3.缓存表达式解析结果
      expressionCache.put(expression, node);
    }
    return node;
  }

}
