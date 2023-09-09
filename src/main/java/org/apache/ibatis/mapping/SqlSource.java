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
package org.apache.ibatis.mapping;

/**
 * SQL来源接口。它代表从 Mapper XML 或 方法注解 上，读取的一条 SQL 内容
 * Represents the content of a mapped statement read from an XML file or an annotation.
 * It creates the SQL that will be passed to the database out of the input parameter received from the user.
 *
 * @author Clinton Begin
 */
public interface SqlSource {

  /**
   * 根据映射文件或注解描述的SQL语句，以及传入的参数，返回可执行的 BoundSql 对象
   * BoundSql其中封装了包含 "?" 占位符的 SQL 语句，以及绑定的实参
   * @param parameterObject 参数对象
   * @return BoundSql对象
   */
  BoundSql getBoundSql(Object parameterObject);

}
