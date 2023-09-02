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
 * 每个 XML Node 会解析成对应的 SQL Node 对象
 * @author Clinton Begin
 */
public interface SqlNode {
  /**
   * 应用当前 SQL Node 节点
   *
   * 该方法根据用户传入的实参解析该 SqlNode 所记录的动态 SQL 节点，
   * 并调用 DynamicContext.appendSql()方法将解析后的 SQL 片段追加到 DynamicContext.sqlBuilder 中保存
   * 当 SQL 节点下的所有 SqlNode 完成解析后，就可以从 DynamicContext 获取一条动态生成的、完整的 SQL 语句
   * @param context 用户传入的参数上下文
   * @return 当前 SQL Node 节点是否应用成功
   */
  boolean apply(DynamicContext context);
}
